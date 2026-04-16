/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi

import com.intellij.openapi.application.ApplicationManager

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
