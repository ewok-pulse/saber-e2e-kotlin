/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.components

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaSymbolInformationProvider
import org.jetbrains.kotlin.analysis.api.lifetime.withValidityAssertion
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget

@KaImplementationDetail
abstract class KaBaseSymbolInformationProvider<T : KaSession> : KaBaseSessionComponent<T>(), KaSymbolInformationProvider {
    protected abstract fun computeAnnotationApplicableTargets(symbol: KaClassSymbol): Set<KotlinTarget>?

    override val KaSymbol.isDeprecated: Boolean
        get() = withValidityAssertion { deprecation != null }

    override val KaKotlinPropertySymbol.isInline: Boolean
        get() = withValidityAssertion {
            getter?.isInline == true && (isVal || setter?.isInline == true)
        }

    @Deprecated("Use 'applicableAnnotationTargets' instead", level = DeprecationLevel.HIDDEN)
    override val KaClassSymbol.annotationApplicableTargets: Set<KotlinTarget>?
        get() = withValidityAssertion {
            computeAnnotationApplicableTargets(this)
        }

    @KaExperimentalApi
    override val KaClassSymbol.applicableAnnotationTargets: Set<AnnotationTarget>?
        get() = withValidityAssertion {
            computeAnnotationApplicableTargets(this)?.mapNotNullTo(mutableSetOf()) { it.toAnnotationTarget() }
        }
}

@KaImplementationDetail
fun KotlinTarget.toAnnotationTarget(): AnnotationTarget? = when (this) {
    KotlinTarget.CLASS -> AnnotationTarget.CLASS
    KotlinTarget.ANNOTATION_CLASS -> AnnotationTarget.ANNOTATION_CLASS
    KotlinTarget.TYPE_PARAMETER -> AnnotationTarget.TYPE_PARAMETER
    KotlinTarget.PROPERTY -> AnnotationTarget.PROPERTY
    KotlinTarget.FIELD -> AnnotationTarget.FIELD
    KotlinTarget.LOCAL_VARIABLE -> AnnotationTarget.LOCAL_VARIABLE
    KotlinTarget.VALUE_PARAMETER -> AnnotationTarget.VALUE_PARAMETER
    KotlinTarget.CONSTRUCTOR -> AnnotationTarget.CONSTRUCTOR
    KotlinTarget.FUNCTION -> AnnotationTarget.FUNCTION
    KotlinTarget.PROPERTY_GETTER -> AnnotationTarget.PROPERTY_GETTER
    KotlinTarget.PROPERTY_SETTER -> AnnotationTarget.PROPERTY_SETTER
    KotlinTarget.TYPE -> AnnotationTarget.TYPE
    KotlinTarget.EXPRESSION -> AnnotationTarget.EXPRESSION
    KotlinTarget.FILE -> AnnotationTarget.FILE
    KotlinTarget.TYPEALIAS -> AnnotationTarget.TYPEALIAS
    else -> null
}