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
import org.jetbrains.kotlin.fir.resolve.dependencies.dependencyGraphBuilder
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asEnumEntryEntity
import org.jetbrains.kotlin.fir.symbols.SymbolInternals

object FirUninitializedEnumEntryChecker : FirEnumEntryChecker(MppCheckerKind.Common) {

    @OptIn(SymbolInternals::class)
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirEnumEntry) {
        val enumEntryEntity = declaration.symbol.asEnumEntryEntity()
        val enumEntryIndex = enumEntryEntity.beginSubgraphIndex
        val dependencyGraph = declaration.moduleData.dependencyGraphBuilder.graph
        if (dependencyGraph.isPoisoned(enumEntryIndex)) {
            reporter.reportOn(
                declaration.source,
                FirErrors.POTENTIALLY_UNINITIALIZED_PROPERTY,
                dependencyGraph.mutuallyDependentEntities(enumEntryEntity).mapTo(mutableListOf(), EnclosingEntity<*>::symbol)
            )
            dependencyGraph.poisoningAccessesFor(enumEntryIndex).forEach { (access, symbols) ->
                reporter.reportOn(access.source, FirErrors.POTENTIALLY_UNINITIALIZED_ACCESSES, symbols)
            }
        }
    }
}
