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
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.utils.isEnumEntry
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.dependencies.dependencyGraph
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asEnumEntryEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asObjectEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.NodeIndex
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousObjectSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol

object FirUninitializedAccessInStaticAnonymousInitializerChecker : FirAnonymousInitializerChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirAnonymousInitializer) {
        val enclosingEntity = when (val containingSymbol = declaration.containingDeclarationSymbol) {
            is FirRegularClassSymbol if containingSymbol.classKind.isObject -> containingSymbol.asObjectEntity() ?: return
            is FirAnonymousObjectSymbol if containingSymbol.isEnumEntry -> containingSymbol.asEnumEntryEntity() ?: return
            else -> return
        }
        val dependencyGraph = context.session.dependencyGraph
        val index = NodeIndex.AnonymousInitializerIndex(enclosingEntity, declaration.symbol)
        if (dependencyGraph.isPoisoned(index)) {
            dependencyGraph.poisoningAccessesFor(index).forEach {
                when (it) {
                    is FirResolvedQualifier -> it.symbol?.let { symbol ->
                        reporter.reportOn(it.source, FirErrors.UNINITIALIZED_ACCESS, symbol)
                    }
                    else -> it.toResolvedCallableSymbol(context.session)
                        ?.let { symbol -> reporter.reportOn(it.source, FirErrors.UNINITIALIZED_ACCESS, symbol) }
                }
            }
        }
    }
}