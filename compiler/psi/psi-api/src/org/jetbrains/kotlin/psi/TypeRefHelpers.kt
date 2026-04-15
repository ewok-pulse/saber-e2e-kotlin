/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(KtNonPublicApi::class)

package org.jetbrains.kotlin.psi.typeRefHelpers

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.siblings
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull

fun getTypeReference(declaration: KtCallableDeclaration): KtTypeReference? {
    return declaration.firstChild!!.siblings(forward = true)
        .dropWhile { it.node!!.elementType != KtTokens.COLON }
        .firstIsInstanceOrNull<KtTypeReference>()
}

fun setTypeReference(declaration: KtCallableDeclaration, addAfter: PsiElement?, typeRef: KtTypeReference?): KtTypeReference? {
    return KtPsiMutatingService.getInstance().setCallableTypeReference(declaration, addAfter, typeRef)
}

fun KtCallableDeclaration.setReceiverTypeReference(typeRef: KtTypeReference?) =
    KtPsiMutatingService.getInstance().setCallableReceiverTypeReference(this, typeRef)

fun KtFunctionType.setReceiverTypeReference(typeRef: KtTypeReference?) =
    KtPsiMutatingService.getInstance().setFunctionTypeReceiverTypeReference(this, typeRef)
