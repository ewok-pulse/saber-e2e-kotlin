/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.codebaseTest

import org.jetbrains.kotlin.AbstractAnalysisApiInternalApiTest
import org.jetbrains.kotlin.AnalysisApiNonPublicMarkers.KA_IMPLEMENTATION_DETAIL_ANNOTATION
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * Verifies that every public declaration in `analysis/analysis-api-impl-base/src` is marked. Suggests `@KaImplementationDetail` for
 * unmarked declarations.
 *
 * See [AbstractAnalysisApiInternalApiTest] for the precise rules and rationale.
 */
class AnalysisApiBaseInternalApiTest : AbstractAnalysisApiInternalApiTest() {
    // We avoid the `@Test` annotation as it would make the test run twice with different JUnit runners.
    fun testInternalApiMarking() = doTest()

    override val sourceDirectories: List<SourceDirectory.ForValidation> =
        listOf(SourceDirectory.ForValidation(sourcePaths = listOf("src")))

    override fun suggestedAnnotation(declaration: KtDeclaration): String = KA_IMPLEMENTATION_DETAIL_ANNOTATION
}
