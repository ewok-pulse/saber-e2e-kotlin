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
     * Performs smart deletion of [element].
     */
    fun deleteElement(element: KtElement)

    /**
     * Performs smart deletion of [blockExpression].
     */
    fun deleteBlockExpression(blockExpression: KtBlockExpression)

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
     * Performs smart deletion of [enumEntry].
     */
    fun deleteEnumEntry(enumEntry: KtEnumEntry)

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
     * Adds a semicolon to [enumEntry], reusing an existing sibling semicolon when possible.
     */
    fun addSemicolon(enumEntry: KtEnumEntry): PsiElement

    /**
     * Returns the existing primary constructor for [klass], or creates one if missing.
     */
    fun getOrCreatePrimaryConstructor(klass: KtClass): KtPrimaryConstructor

    /**
     * Returns the existing primary constructor parameter list for [klass], or creates one if missing.
     */
    fun getOrCreatePrimaryConstructorParameterList(klass: KtClass): KtParameterList

    /**
     * Renames [declaration], including operator-specific modifier adjustments.
     */
    fun setNamedDeclarationStubName(declaration: KtNamedDeclarationStub<*>, name: String): PsiElement?

    /**
     * Renames [declaration] by replacing its name identifier directly.
     */
    fun setNamedDeclarationName(declaration: KtNamedDeclaration, name: String): PsiElement

    /**
     * Renames [expression] by replacing its target label.
     */
    fun setLabeledExpressionName(expression: KtLabeledExpression, name: String): PsiElement

    /**
     * Renames [importAlias].
     */
    fun setImportAliasName(importAlias: KtImportAlias, name: String): PsiElement

    /**
     * Renames [declaration], adding an explicit identifier when necessary.
     */
    fun setObjectDeclarationName(declaration: KtObjectDeclaration, name: String): PsiElement

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
