/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.utils.isEnumEntry
import org.jetbrains.kotlin.fir.resolve.dependencies.dependencyGraphBuilder
import org.jetbrains.kotlin.fir.resolve.dependencies.hasImplementation
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asEnumEntryEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asFileEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asInstancedPropertyEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asObjectEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.NodeIndex
import org.jetbrains.kotlin.fir.resolve.getContainingSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousObjectSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFileSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.isPrimitiveOrNullablePrimitive
import org.jetbrains.kotlin.fir.types.isString
import org.jetbrains.kotlin.fir.types.isUnit

object FirUninitializedStaticPropertyChecker : FirPropertyChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        if (declaration.isVar) return
        declaration.symbol.getContainingSymbol(context.session)?.let { containingSymbol ->
            val enclosingEntity = when (containingSymbol) {
                is FirRegularClassSymbol if containingSymbol.classKind.isObject -> containingSymbol.asObjectEntity() ?: return
                is FirAnonymousObjectSymbol if containingSymbol.isEnumEntry -> containingSymbol.asEnumEntryEntity() ?: return
                is FirFileSymbol -> containingSymbol.asFileEntity()
                else -> return
            }
            val index = if (declaration.initializer != null && declaration.getter?.let { !it.symbol.hasImplementation } ?: true) {
                declaration.symbol.resolvedReturnType.let { type ->
                    if (type.isPrimitiveOrNullablePrimitive || type.isString || type.isUnit || type.isNothing) {
                        NodeIndex.PrimitivePropertyIndex(enclosingEntity, declaration.symbol)
                    } else {
                        declaration.symbol.asInstancedPropertyEntity(enclosingEntity).beginSubgraphIndex
                    }
                }
            } else NodeIndex.FunctionLikeIndex(enclosingEntity, declaration.symbol)
            val dependencyGraph = declaration.moduleData.dependencyGraphBuilder.graph
            if (dependencyGraph.isPoisoned(index)) {
                reporter.reportOn(
                    declaration.source,
                    FirErrors.POTENTIALLY_UNINITIALIZED_PROPERTY,
                    dependencyGraph.mutuallyDependentEntities(enclosingEntity).mapTo(mutableListOf(), EnclosingEntity<*>::symbol)
                )
                dependencyGraph.poisoningAccessesFor(index).forEach { (access, symbols) ->
                    reporter.reportOn(access.source, FirErrors.POTENTIALLY_UNINITIALIZED_ACCESSES, symbols)
                }
            }
        }
    }
}
