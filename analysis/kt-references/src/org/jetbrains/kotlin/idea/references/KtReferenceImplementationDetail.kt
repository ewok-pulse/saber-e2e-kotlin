/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.references

import org.jetbrains.kotlin.psi.KtImplementationDetail

/**
 * Marker for implementation classes of Kotlin references.
 *
 * Compared to [org.jetbrains.kotlin.psi.KtImplementationDetail], this annotation isn't an opt-in one.
 * Rather, it marks intermediate reference classes so they are excluded from the test output of a `AbstractResolveReferenceTest`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
internal annotation class KtReferenceImplementationDetail
