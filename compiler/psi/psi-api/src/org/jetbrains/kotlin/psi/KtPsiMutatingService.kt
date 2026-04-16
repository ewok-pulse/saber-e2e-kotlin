/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi

import com.intellij.openapi.application.ApplicationManager

/**
 * Service responsible for Kotlin PSI mutation operations whose implementation is provided by the Kotlin plugin environment.
 */
@KtNonPublicApi
@SubclassOptInRequired(KtImplementationDetail::class)
interface KtPsiMutatingService {
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
