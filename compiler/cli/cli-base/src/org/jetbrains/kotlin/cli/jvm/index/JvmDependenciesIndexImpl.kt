/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.jvm.index

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.name.ClassId
import kotlin.concurrent.withLock

class JvmDependenciesIndexImpl(
    roots: List<JavaRoot>,
    shouldOnlyFindFirstClass: Boolean,
) : JvmDependenciesIndexBase(roots, shouldOnlyFindFirstClass) {
    // holds the request and the result last time we searched for class
    // helps improve several scenarios, LazyJavaResolverContext.findClassInJava being the most important
    private var lastClassVirtualFileSearch: Pair<ClassSearchRequest, Collection<VirtualFile>>? = null

    override fun findClassVirtualFiles(
        classId: ClassId,
        acceptedExtensions: Collection<JavaFileExtension>,
    ): Collection<VirtualFile> {
        lock.withLock {
            // TODO: KT-58327 probably should be changed to thread local to fix fast-path
            // make a decision based on information saved from last class search
            val cachedClasses = lastClassVirtualFileSearch?.let { (cachedRequest, cachedResult) ->
                if (cachedRequest.classId != classId) return@let null

                val isMatchingRequest = if (cachedResult.isEmpty()) {
                    cachedRequest.acceptedExtensions.containsAll(acceptedExtensions)
                } else {
                    // The accepted extensions have to match exactly. Otherwise, the cache might produce files with unexpected extensions.
                    // The order must also be the same, as it determines which files are resolved first in each root, so the result might be
                    // different.
                    cachedRequest.acceptedExtensions == acceptedExtensions
                }

                if (isMatchingRequest) cachedResult else null
            }

            if (cachedClasses != null) return cachedClasses

            val result = searchClasses(classId, acceptedExtensions)
            lastClassVirtualFileSearch = ClassSearchRequest(classId, acceptedExtensions) to result
            return result
        }
    }

    private data class ClassSearchRequest(
        val classId: ClassId,
        val acceptedExtensions: Collection<JavaFileExtension>,
    )
}
