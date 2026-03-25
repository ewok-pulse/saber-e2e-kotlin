/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.components

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaTypeCreatorProvider
import org.jetbrains.kotlin.analysis.api.lifetime.withValidityAssertion
import org.jetbrains.kotlin.analysis.api.types.KaCapturedType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaDefinitelyNotNullType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.analysis.api.types.typeCreation.KaCapturedTypeBuilder
import org.jetbrains.kotlin.analysis.api.types.typeCreation.KaClassTypeBuilder
import org.jetbrains.kotlin.analysis.api.types.typeCreation.KaDefinitelyNotNullTypeBuilder
import org.jetbrains.kotlin.analysis.api.types.typeCreation.KaFlexibleTypeBuilder
import org.jetbrains.kotlin.analysis.api.types.typeCreation.KaFunctionTypeBuilder
import org.jetbrains.kotlin.analysis.api.types.typeCreation.KaTypeParameterTypeBuilder

@KaImplementationDetail
abstract class KaBaseTypeCreatorProvider<T : KaSession> : KaBaseSessionComponent<T>(), KaTypeCreatorProvider {
    @KaExperimentalApi
    override fun <T : KaClassType> T.copy(init: KaClassTypeBuilder.() -> Unit): KaClassType = with(analysisSession) {
        val sourceSymbol = this@copy.symbol
        val sourceIsMarkedNullable = this@copy.isMarkedNullable
        val sourceAnnotationClassIds = this@copy.annotations.classIds.toList()
        val sourceTypeArguments = this@copy.typeArguments

        typeCreator.classType(sourceSymbol) {
            isMarkedNullable = sourceIsMarkedNullable
            typeArguments { sourceTypeArguments }
            annotations(sourceAnnotationClassIds)
            init()
        } as KaClassType
    }

    @KaExperimentalApi
    override fun KaFunctionType.copy(init: KaFunctionTypeBuilder.() -> Unit): KaFunctionType = withValidityAssertion {
        with(analysisSession) {
            val sourceIsMarkedNullable = this@copy.isMarkedNullable
            val sourceIsSuspend = this@copy.isSuspend
            val sourceIsReflectType = this@copy.isReflectType
            val sourceContextReceivers = this@copy.contextReceivers
            val sourceReceiverType = this@copy.receiverType
            val sourceParameters = this@copy.parameters
            val sourceReturnType = this@copy.returnType
            val sourceAnnotationClassIds = this@copy.annotations.classIds.toList()

            typeCreator.functionType {
                isMarkedNullable = sourceIsMarkedNullable
                isSuspend = sourceIsSuspend
                isReflectType = sourceIsReflectType
                for (contextReceiver in sourceContextReceivers) {
                    contextParameter(contextReceiver.type)
                }
                receiverType = sourceReceiverType
                for (parameter in sourceParameters) {
                    valueParameter(parameter.name, parameter.type)
                }
                returnType = sourceReturnType
                annotations(sourceAnnotationClassIds)
                init()
            }
        }
    }

    @KaExperimentalApi
    override fun KaTypeParameterType.copy(init: KaTypeParameterTypeBuilder.() -> Unit): KaTypeParameterType = withValidityAssertion {
        with(analysisSession) {
            val sourceSymbol = this@copy.symbol
            val sourceIsMarkedNullable = this@copy.isMarkedNullable
            val sourceAnnotationClassIds = this@copy.annotations.classIds.toList()

            typeCreator.typeParameterType(sourceSymbol) {
                isMarkedNullable = sourceIsMarkedNullable
                annotations(sourceAnnotationClassIds)
                init()
            }
        }
    }

    @KaExperimentalApi
    override fun KaCapturedType.copy(init: KaCapturedTypeBuilder.() -> Unit): KaCapturedType = withValidityAssertion {
        with(analysisSession) {
            val sourceIsMarkedNullable = this@copy.isMarkedNullable
            val sourceAnnotationClassIds = this@copy.annotations.classIds.toList()

            typeCreator.capturedType(this@copy) {
                isMarkedNullable = sourceIsMarkedNullable
                annotations(sourceAnnotationClassIds)
                init()
            }
        }
    }

    @KaExperimentalApi
    override fun KaDefinitelyNotNullType.copy(
        init: KaDefinitelyNotNullTypeBuilder.() -> Unit
    ): KaDefinitelyNotNullType = withValidityAssertion {
        with(analysisSession) {
            val sourceAnnotationClassIds = this@copy.annotations.classIds.toList()

            val result = when (val sourceOriginalType = this@copy.original) {
                is KaCapturedType -> typeCreator.definitelyNotNullType(sourceOriginalType) {
                    annotations(sourceAnnotationClassIds)
                    init()
                }
                is KaTypeParameterType -> typeCreator.definitelyNotNullType(sourceOriginalType) {
                    annotations(sourceAnnotationClassIds)
                    init()
                }
                else -> {
                    error("Unexpected original type kind in ${KaDefinitelyNotNullType::class.simpleName}: ${sourceOriginalType::class}")
                }
            }

            result as KaDefinitelyNotNullType
        }
    }

    @KaExperimentalApi
    override fun KaFlexibleType.copy(init: KaFlexibleTypeBuilder.() -> Unit): KaFlexibleType? = withValidityAssertion {
        with(analysisSession) {
            val sourceAnnotationClassIds = this@copy.annotations.classIds.toList()

            typeCreator.flexibleType(this@copy) {
                annotations(sourceAnnotationClassIds)
                init()
            } as KaFlexibleType?
        }
    }
}
