/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement

/**
 * Service responsible for Kotlin PSI mutation operations whose implementation is provided by the Kotlin plugin environment.
 */
@KtNonPublicApi
@SubclassOptInRequired(KtImplementationDetail::class)
interface KtPsiMutatingService {
    /**
     * Adds [superTypeListEntry] to [declaration].
     */
    fun addSuperTypeListEntry(declaration: KtClassOrObject, superTypeListEntry: KtSuperTypeListEntry): KtSuperTypeListEntry

    /**
     * Adds [superTypeListEntry] to [superTypeList].
     */
    fun addSuperTypeListEntry(superTypeList: KtSuperTypeList, superTypeListEntry: KtSuperTypeListEntry): KtSuperTypeListEntry

    /**
     * Removes [superTypeListEntry] from [declaration].
     */
    fun removeSuperTypeListEntry(declaration: KtClassOrObject, superTypeListEntry: KtSuperTypeListEntry)

    /**
     * Removes [superTypeListEntry] from [superTypeList].
     */
    fun removeSuperTypeListEntry(superTypeList: KtSuperTypeList, superTypeListEntry: KtSuperTypeListEntry)

    /**
     * Deletes [superTypeList], removing the preceding colon when needed.
     */
    fun deleteSuperTypeList(superTypeList: KtSuperTypeList)

    /**
     * Performs smart deletion of [declaration].
     */
    fun deleteClassOrObject(declaration: KtClassOrObject)

    /**
     * Adds [declaration] to [classOrObject], creating a body when needed.
     */
    fun <T : KtDeclaration> addDeclaration(classOrObject: KtClassOrObject, declaration: T): T

    /**
     * Adds [declaration] after [anchor] in [classOrObject], or appends it when [anchor] is `null`.
     */
    fun <T : KtDeclaration> addDeclarationAfter(classOrObject: KtClassOrObject, declaration: T, anchor: PsiElement?): T

    /**
     * Adds [declaration] before [anchor] in [classOrObject], or prepends it when [anchor] is `null`.
     */
    fun <T : KtDeclaration> addDeclarationBefore(classOrObject: KtClassOrObject, declaration: T, anchor: PsiElement?): T

    /**
     * Returns the existing body for [classOrObject], or creates one if missing.
     */
    fun getOrCreateBody(classOrObject: KtClassOrObject): KtClassBody

    /**
     * Deletes a trailing semicolon belonging to [element] when it should disappear together with the element.
     */
    fun deleteSemicolon(element: KtElement)

    /**
     * Adds a semicolon to [enumEntry], reusing an existing sibling semicolon when possible.
     */
    fun addSemicolon(enumEntry: KtEnumEntry): PsiElement

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
