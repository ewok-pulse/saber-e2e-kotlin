/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi

import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken

/**
 * Service responsible for Kotlin PSI mutation operations whose implementation is provided by the Kotlin plugin environment.
 *
 * The Kotlin PSI API keeps these entry points for compatibility, but the mutation logic itself is hosted outside the PSI module so that
 * it can be tested and evolved together with IDE-side code.
 */
@KtNonPublicApi
@SubclassOptInRequired(KtImplementationDetail::class)
interface KtPsiMutatingService {
    /**
     * Adds [superTypeListEntry] to [declaration].
     */
    fun addSuperTypeListEntry(declaration: KtClassOrObject, superTypeListEntry: KtSuperTypeListEntry): KtSuperTypeListEntry

    /**
     * Removes [superTypeListEntry] from [declaration].
     */
    fun removeSuperTypeListEntry(declaration: KtClassOrObject, superTypeListEntry: KtSuperTypeListEntry)

    /**
     * Performs smart deletion of [declaration].
     */
    fun deleteClassOrObject(declaration: KtClassOrObject)

    /**
     * Replaces the existing modifier list on [owner] with [newModifierList], or adds it if missing.
     */
    fun setModifierList(owner: KtModifierListOwner, newModifierList: KtModifierList)

    /**
     * Adds [modifier] to [owner].
     */
    fun addModifier(owner: KtModifierListOwner, modifier: KtModifierKeywordToken)

    /**
     * Adds [modifier] to [constructor] using primary-constructor-specific behavior.
     */
    fun addConstructorModifier(constructor: KtPrimaryConstructor, modifier: KtModifierKeywordToken)

    /**
     * Removes [modifier] from [owner].
     */
    fun removeModifier(owner: KtModifierListOwner, modifier: KtModifierKeywordToken)

    /**
     * Removes [modifier] from [constructor] using primary-constructor-specific behavior.
     */
    fun removeConstructorModifier(constructor: KtPrimaryConstructor, modifier: KtModifierKeywordToken)

    /**
     * Adds [annotationEntry] to [owner].
     */
    fun addAnnotationEntry(owner: KtModifierListOwner, annotationEntry: KtAnnotationEntry): KtAnnotationEntry

    /**
     * Adds [annotationEntry] to [constructor] using primary-constructor-specific behavior.
     */
    fun addConstructorAnnotationEntry(constructor: KtPrimaryConstructor, annotationEntry: KtAnnotationEntry): KtAnnotationEntry

    /**
     * Removes the redundant `constructor` keyword and the following whitespace from [constructor].
     */
    fun removeRedundantConstructorKeywordAndSpace(constructor: KtPrimaryConstructor)

    /**
     * Replaces the implicit delegation call in [constructor] with an explicit `this()` or `super()` call.
     */
    fun replaceImplicitDelegationCallWithExplicit(constructor: KtSecondaryConstructor, isThis: Boolean): KtConstructorDelegationCall

    @KtNonPublicApi
    companion object {
        /**
         * Returns the registered Kotlin PSI mutating service.
         */
        @JvmStatic
        fun getInstance(): KtPsiMutatingService =
            ApplicationManager.getApplication().getService(KtPsiMutatingService::class.java)
                ?: throw IllegalStateException("Cannot mutate Kotlin PSI because KtPsiMutatingService is missing")
    }
}
