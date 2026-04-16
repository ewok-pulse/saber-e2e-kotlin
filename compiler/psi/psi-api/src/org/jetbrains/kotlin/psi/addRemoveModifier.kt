/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:OptIn(KtNonPublicApi::class)

package org.jetbrains.kotlin.psi.addRemoveModifier

import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens.*
import org.jetbrains.kotlin.psi.*

@Deprecated(
    "Use KtPsiMutatingService.getInstance().setModifierList(this, newModifierList) instead",
    ReplaceWith("KtPsiMutatingService.getInstance().setModifierList(this, newModifierList)"),
)
fun KtModifierListOwner.setModifierList(newModifierList: KtModifierList) {
    KtPsiMutatingService.getInstance().setModifierList(this, newModifierList)
}

@Deprecated(
    "Use KtPsiMutatingService.getInstance().addModifier(owner, modifier) instead",
    ReplaceWith("KtPsiMutatingService.getInstance().addModifier(owner, modifier)"),
)
fun addModifier(owner: KtModifierListOwner, modifier: KtModifierKeywordToken) {
    KtPsiMutatingService.getInstance().addModifier(owner, modifier)
}

@Deprecated(
    "Use KtPsiMutatingService.getInstance().addAnnotationEntry(owner, annotationEntry) instead",
    ReplaceWith("KtPsiMutatingService.getInstance().addAnnotationEntry(owner, annotationEntry)"),
)
fun addAnnotationEntry(owner: KtModifierListOwner, annotationEntry: KtAnnotationEntry): KtAnnotationEntry =
    KtPsiMutatingService.getInstance().addAnnotationEntry(owner, annotationEntry)

@Deprecated(
    "Use KtPsiMutatingService.getInstance().removeModifier(owner, modifier) instead",
    ReplaceWith("KtPsiMutatingService.getInstance().removeModifier(owner, modifier)"),
)
fun removeModifier(owner: KtModifierListOwner, modifier: KtModifierKeywordToken) {
    KtPsiMutatingService.getInstance().removeModifier(owner, modifier)
}

fun sortModifiers(modifiers: List<KtModifierKeywordToken>): List<KtModifierKeywordToken> {
    return modifiers.sortedBy {
        val index = MODIFIER_KEYWORDS_ARRAY.indexOf(it)
        if (index == -1) Int.MAX_VALUE else index
    }
}

@Deprecated(
    "Use `KtTokens.MODIFIER_KEYWORDS_ARRAY` directly",
    ReplaceWith("KtTokens.MODIFIER_KEYWORDS_ARRAY", "org.jetbrains.kotlin.lexer.KtTokens"),
)
val MODIFIERS_ORDER: List<KtModifierKeywordToken> get() = MODIFIER_KEYWORDS_ARRAY.asList()
