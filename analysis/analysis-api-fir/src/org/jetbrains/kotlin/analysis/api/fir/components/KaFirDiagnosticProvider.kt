/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.components

import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticProvider
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.fir.KaFirSession
import org.jetbrains.kotlin.analysis.api.impl.base.components.KaBaseSessionComponent
import org.jetbrains.kotlin.analysis.api.impl.base.components.withPsiValidityAssertion
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.DiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.getDiagnostics
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.plus
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.diagnostics
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile

internal class KaFirDiagnosticProvider(
    override val analysisSessionProvider: () -> KaFirSession,
) : KaBaseSessionComponent<KaFirSession>(), KaDiagnosticProvider, KaFirSessionComponent {
    @Deprecated("Use KtElement.directDiagnostics instead", replaceWith = ReplaceWith("directDiagnostics(filter)"))
    override fun KtElement.diagnostics(filter: KaDiagnosticCheckerFilter): Collection<KaDiagnosticWithPsi<*>> {
        return directDiagnostics(filter)
    }

    override fun KtElement.directDiagnostics(
        filter: KaDiagnosticCheckerFilter,
    ): Collection<KaDiagnosticWithPsi<*>> = withPsiValidityAssertion {
        getDiagnostics(resolutionFacade, filter.asLLFilter()).map { it.asKaDiagnostic() }
    }

    override fun KtFile.collectDiagnostics(
        filter: KaDiagnosticCheckerFilter,
        ignoreSuppression: Boolean,
    ): Collection<KaDiagnosticWithPsi<*>> = withPsiValidityAssertion {
        diagnostics(filter, ignoreSuppression).toList()
    }

    override fun KtFile.diagnostics(
        filter: KaDiagnosticCheckerFilter,
        ignoreSuppression: Boolean,
    ): Sequence<KaDiagnosticWithPsi<*>> = withPsiValidityAssertion {
        diagnostics(resolutionFacade, filter.asLLFilter(ignoreSuppression))
            .map { it.asKaDiagnostic() }
    }

    private fun KaDiagnosticCheckerFilter.asLLFilter(ignoreSuppression: Boolean = false) = when (this) {
        KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS ->
            DiagnosticCheckerFilter.ONLY_DEFAULT_CHECKERS.copy(ignoreSuppression = ignoreSuppression)
        KaDiagnosticCheckerFilter.ONLY_EXTENDED_CHECKERS ->
            DiagnosticCheckerFilter.ONLY_EXTRA_CHECKERS.copy(ignoreSuppression = ignoreSuppression)
        KaDiagnosticCheckerFilter.ONLY_EXPERIMENTAL_CHECKERS ->
            DiagnosticCheckerFilter.ONLY_EXPERIMENTAL_CHECKERS.copy(ignoreSuppression = ignoreSuppression)
        KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS ->
            (DiagnosticCheckerFilter.ONLY_DEFAULT_CHECKERS + DiagnosticCheckerFilter.ONLY_EXTRA_CHECKERS)
                .copy(ignoreSuppression = ignoreSuppression)
    }
}
