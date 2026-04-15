/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.name.FqName

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
     * Replaces the explicit return type on [declaration] with [typeRef], adds it if missing, or removes it when [typeRef] is `null`.
     */
    fun setCallableTypeReference(declaration: KtCallableDeclaration, addAfter: PsiElement?, typeRef: KtTypeReference?): KtTypeReference?

    /**
     * Replaces the receiver type on [declaration] with [typeRef], adds it if missing, or removes it when [typeRef] is `null`.
     */
    fun setCallableReceiverTypeReference(declaration: KtCallableDeclaration, typeRef: KtTypeReference?): KtTypeReference?

    /**
     * Replaces the receiver type on [functionType] with [typeRef], adds it if missing, or removes it when [typeRef] is `null`.
     */
    fun setFunctionTypeReceiverTypeReference(functionType: KtFunctionType, typeRef: KtTypeReference?): KtTypeReference?

    /**
     * Replaces the initializer on [property] with [initializer], adds it if missing, or removes it when [initializer] is `null`.
     */
    fun setPropertyInitializer(property: KtProperty, initializer: KtExpression?): KtExpression?

    /**
     * Replaces the extends bound on [typeParameter] with [typeReference], adds it if missing, or removes it when [typeReference] is `null`.
     */
    fun setTypeParameterExtendsBound(typeParameter: KtTypeParameter, typeReference: KtTypeReference?): KtTypeReference?

    /**
     * Replaces the package name of [packageDirective] with [fqName].
     */
    fun setPackageDirectiveFqName(packageDirective: KtPackageDirective, fqName: FqName)

    /**
     * Replaces the receiver expression on [expression] with [newReceiverExpression], or adds it if missing.
     */
    fun setDoubleColonReceiverExpression(expression: KtDoubleColonExpression, newReceiverExpression: KtExpression)

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
     * Removes [entry] from [annotation], deleting the annotation when it becomes empty.
     */
    fun removeAnnotationEntry(annotation: KtAnnotation, entry: KtAnnotationEntry)

    /**
     * Removes the redundant `constructor` keyword and the following whitespace from [constructor].
     */
    fun removeRedundantConstructorKeywordAndSpace(constructor: KtPrimaryConstructor)

    /**
     * Replaces the implicit delegation call in [constructor] with an explicit `this()` or `super()` call.
     */
    fun replaceImplicitDelegationCallWithExplicit(constructor: KtSecondaryConstructor, isThis: Boolean): KtConstructorDelegationCall

    /**
     * Adds [parameter] to [parameterList].
     */
    fun addParameter(parameterList: KtParameterList, parameter: KtParameter): KtParameter

    /**
     * Adds [parameter] to [parameterList] before [anchor].
     */
    fun addParameterBefore(parameterList: KtParameterList, parameter: KtParameter, anchor: KtParameter?): KtParameter

    /**
     * Adds [parameter] to [parameterList] after [anchor].
     */
    fun addParameterAfter(parameterList: KtParameterList, parameter: KtParameter, anchor: KtParameter?): KtParameter

    /**
     * Adds [typeParameter] to [typeParameterList].
     */
    fun addTypeParameter(typeParameterList: KtTypeParameterList, typeParameter: KtTypeParameter): KtTypeParameter

    /**
     * Adds [argument] to [argumentList].
     */
    fun addValueArgument(argumentList: KtValueArgumentList, argument: KtValueArgument): KtValueArgument

    /**
     * Adds [argument] to [argumentList] after [anchor].
     */
    fun addValueArgumentAfter(argumentList: KtValueArgumentList, argument: KtValueArgument, anchor: KtValueArgument?): KtValueArgument

    /**
     * Adds [argument] to [argumentList] before [anchor].
     */
    fun addValueArgumentBefore(argumentList: KtValueArgumentList, argument: KtValueArgument, anchor: KtValueArgument?): KtValueArgument

    /**
     * Removes [argument] from [argumentList].
     */
    fun removeValueArgument(argumentList: KtValueArgumentList, argument: KtValueArgument)

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
