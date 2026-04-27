/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.klib

import org.jetbrains.kotlin.config.LanguageVersion.Companion.LATEST_STABLE
import org.jetbrains.kotlin.test.WrappedException
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.frontend.objcinterop.ObjCInteropFacade
import org.jetbrains.kotlin.test.klib.CustomKlibCompilerTestDirectives
import org.jetbrains.kotlin.test.model.TestFailureSuppressor
import org.jetbrains.kotlin.test.services.TestServices

/*
 * Suppresses errors of earlier versions of cinterop tool while compiling newer tests for newer platformlibs
 */
class CustomFirstStageCInteropTestSuppressor(
    testServices: TestServices,
) : TestFailureSuppressor(testServices) {
    override val directiveContainers: List<DirectivesContainer>
        get() = listOf(CustomKlibCompilerTestDirectives)

    override fun suppressIfNeeded(failedAssertions: List<WrappedException>): List<WrappedException> {
        return failedAssertions.mapNotNull { wrappedException ->
            if (customNativeCompilerSettings.defaultLanguageVersion < LATEST_STABLE
                && wrappedException is WrappedException.FromFacade
                && wrappedException.facade is ObjCInteropFacade
            ) {
                val exceptionString = wrappedException.cause.toString()
                if (exceptionString.contains("Unknown option -Xccall-mode")
                    || exceptionString.contains("could not build module")
                    || exceptionString.contains("error: unknown type name 'va_list'")
                ) null else wrappedException
            } else wrappedException
        }
    }

    override fun checkIfTestShouldBeUnmuted() {}
}
