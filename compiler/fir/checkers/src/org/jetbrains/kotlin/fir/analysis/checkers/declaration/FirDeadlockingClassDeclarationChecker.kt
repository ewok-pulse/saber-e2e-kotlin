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
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.resolve.dependencies.dependencyGraphBuilder
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asEntity

object FirDeadlockingClassDeclarationChecker : FirRegularClassChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        if (declaration.isCompanion) return
        val dependencyGraph = declaration.moduleData.dependencyGraphBuilder.graph
        declaration.symbol.asEntity()?.let { enclosingEntity ->
            println(enclosingEntity)
            val deadlockingEntities = dependencyGraph.deadlockingEntities(enclosingEntity).toList()
            if (deadlockingEntities.isNotEmpty()) {
                reporter.reportOn(
                    declaration.source,
                    FirErrors.POSSIBLE_INITIALIZATION_DEADLOCK,
                    deadlockingEntities.map(EnclosingEntity<*>::symbol)
                )
            }
        }
    }
}
