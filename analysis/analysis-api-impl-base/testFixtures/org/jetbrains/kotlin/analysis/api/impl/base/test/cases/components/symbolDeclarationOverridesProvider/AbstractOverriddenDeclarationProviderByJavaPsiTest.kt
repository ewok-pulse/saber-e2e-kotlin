/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.test.cases.components.symbolDeclarationOverridesProvider

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.callableSymbol
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSyntheticJavaPropertySymbol
import org.jetbrains.kotlin.analysis.test.framework.projectStructure.KtTestModule
import org.jetbrains.kotlin.analysis.test.framework.services.expressionMarkerProvider
import org.jetbrains.kotlin.analysis.utils.errors.unexpectedElementError
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.test.services.TestServices

abstract class AbstractOverriddenDeclarationProviderByJavaPsiTest : AbstractOverriddenDeclarationProviderTest() {
    context(_: KaSession)
    override fun getCallableSymbol(mainFile: KtFile, mainModule: KtTestModule, testServices: TestServices): KaCallableSymbol {
        val referenceExpression = testServices.expressionMarkerProvider.getBottommostElementOfTypeAtCaret<KtReferenceExpression>(mainFile)
        val symbolByReference = referenceExpression.mainReference.resolveToSymbol() ?: error("Failed to resolve reference")

        val javaPsi = when (symbolByReference) {
            is KaSyntheticJavaPropertySymbol -> symbolByReference.setter?.psi ?: symbolByReference.getter.psi
            else -> symbolByReference.psi
        }

        return when (javaPsi) {
            is PsiMember -> javaPsi.callableSymbol ?: error("Failed to find callable symbol for Java PSI")
            null -> error("Failed to find Java PSI for symbol")
            else -> unexpectedElementError<PsiElement>(javaPsi)
        }
    }
}
