/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.diagnostics

import com.intellij.psi.PsiElement
import com.intellij.util.SmartList
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.DiagnosticCheckerFilter
import org.jetbrains.kotlin.diagnostics.KtPsiDiagnostic

internal class FileStructureElementDiagnostics(private val retriever: FileStructureElementDiagnosticRetriever) {
    private val diagnosticByDefaultCheckers: FileStructureElementDiagnosticList by lazy {
        retriever.retrieve(DiagnosticCheckerFilter.ONLY_DEFAULT_CHECKERS)
    }

    private val diagnosticByExtraCheckers: FileStructureElementDiagnosticList by lazy {
        retriever.retrieve(DiagnosticCheckerFilter.ONLY_EXTRA_CHECKERS)
    }

    private val diagnosticByExperimentalCheckers: FileStructureElementDiagnosticList by lazy {
        retriever.retrieve(DiagnosticCheckerFilter.ONLY_EXPERIMENTAL_CHECKERS)
    }

    private val diagnosticByDefaultCheckersIgnoringSuppression: FileStructureElementDiagnosticList by lazy {
        retriever.retrieve(DiagnosticCheckerFilter.ONLY_DEFAULT_CHECKERS.copy(ignoreSuppression = true))
    }

    private val diagnosticByExtraCheckersIgnoringSuppression: FileStructureElementDiagnosticList by lazy {
        retriever.retrieve(DiagnosticCheckerFilter.ONLY_EXTRA_CHECKERS.copy(ignoreSuppression = true))
    }

    private val diagnosticByExperimentalCheckersIgnoringSuppression: FileStructureElementDiagnosticList by lazy {
        retriever.retrieve(DiagnosticCheckerFilter.ONLY_EXPERIMENTAL_CHECKERS.copy(ignoreSuppression = true))
    }

    private fun defaultCheckersList(ignoreSuppression: Boolean) =
        if (ignoreSuppression) diagnosticByDefaultCheckersIgnoringSuppression else diagnosticByDefaultCheckers

    private fun extraCheckersList(ignoreSuppression: Boolean) =
        if (ignoreSuppression) diagnosticByExtraCheckersIgnoringSuppression else diagnosticByExtraCheckers

    private fun experimentalCheckersList(ignoreSuppression: Boolean) =
        if (ignoreSuppression) diagnosticByExperimentalCheckersIgnoringSuppression else diagnosticByExperimentalCheckers

    fun diagnosticsFor(filter: DiagnosticCheckerFilter, element: PsiElement): List<KtPsiDiagnostic> =
        SmartList<KtPsiDiagnostic>().apply {
            if (filter.runDefaultCheckers) {
                addAll(defaultCheckersList(filter.ignoreSuppression).diagnosticsFor(element))
            }
            if (filter.runExtraCheckers) {
                addAll(extraCheckersList(filter.ignoreSuppression).diagnosticsFor(element))
            }
            if (filter.runExperimentalCheckers) {
                addAll(experimentalCheckersList(filter.ignoreSuppression).diagnosticsFor(element))
            }
        }

    inline fun forEach(filter: DiagnosticCheckerFilter, action: (List<KtPsiDiagnostic>) -> Unit) {
        if (filter.runDefaultCheckers) {
            defaultCheckersList(filter.ignoreSuppression).forEach(action)
        }
        if (filter.runExtraCheckers) {
            extraCheckersList(filter.ignoreSuppression).forEach(action)
        }
        if (filter.runExperimentalCheckers) {
            experimentalCheckersList(filter.ignoreSuppression).forEach(action)
        }
    }
}