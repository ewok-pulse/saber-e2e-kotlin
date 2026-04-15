/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.dependencies.dependencyGraphBuilder
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asEnumEntryEntity
import org.jetbrains.kotlin.fir.symbols.SymbolInternals

object FirUninitializedEnumEntryChecker : FirEnumEntryChecker(MppCheckerKind.Common) {

    @OptIn(SymbolInternals::class)
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirEnumEntry) {
        val enumEntryIndex = declaration.symbol.asEnumEntryEntity().beginSubgraphIndex
        val dependencyGraph = declaration.moduleData.dependencyGraphBuilder.graph
        if (dependencyGraph.isPoisoned(enumEntryIndex)) {
            reporter.reportOn(declaration.source, FirErrors.UNINITIALIZED_PROPERTY)
            dependencyGraph.poisoningAccessesFor(enumEntryIndex).forEach {
                when (it) {
                    is FirResolvedQualifier -> it.symbol?.let { symbol ->
                        reporter.reportOn(it.source, FirErrors.UNINITIALIZED_ACCESS, symbol)
                    }
                    else -> it.toResolvedCallableSymbol(context.session)?.let { symbol ->
                        reporter.reportOn(it.source, FirErrors.UNINITIALIZED_ACCESS, symbol)
                    }
                }
            }
        }
    }
}
