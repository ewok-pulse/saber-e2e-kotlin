/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.standalone.base.declarations

import com.github.benmanes.caffeine.cache.Caffeine
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.cli.jvm.index.JavaFileExtension
import org.jetbrains.kotlin.cli.jvm.index.JavaFileExtensions
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndexBase
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.SmartList

/**
 * The Standalone implementation of [JvmDependenciesIndex][org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndex].
 *
 * In contrast to the compiler implementation, this implementation caches the virtual files of each class for each package. While Standalone
 * workloads benefit from this optimization, this is not true for compiler workloads. So the optimization is limited to Standalone.
 *
 * The Standalone JVM dependencies index always returns all possible virtual files from [findClassVirtualFiles], as Standalone does not
 * necessarily operate under a single-module view. Since class virtual files are cached, the performance impact from finding all files
 * instead of only the first is negligible.
 */
internal class KotlinStandaloneJvmDependenciesIndex(roots: List<JavaRoot>) : JvmDependenciesIndexBase(roots) {
    /**
     * For each package [FqName], caches a list of virtual files for every class in the package. The [String] key of the map represents each
     * class's relative name.
     *
     * The cache is built on demand for every package. However, each "relative class name -> virtual files" map is built exhaustively from
     * all roots and can be used reliably.
     *
     * Each map contains all virtual files for all possible [JavaFileExtension]s. If only a subset of extensions is requested, the list has
     * to be post-processed to filter out unwanted virtual files.
     *
     * In the vast majority of use cases, the cache will function as if it were unlimited. The maximum size has been chosen to limit the
     * memory footprint in pathological cases.
     */
    private val classVirtualFilesByPackage =
        Caffeine.newBuilder()
            .maximumSize(5000)
            .build<FqName, Map<String, List<VirtualFile>>>()

    override fun findClassVirtualFiles(
        classId: ClassId,
        acceptedExtensions: JavaFileExtensions,
    ): Collection<VirtualFile> {
        val classVirtualFiles = classVirtualFilesByPackage.get(classId.packageFqName, ::computeClassVirtualFiles)!!
        val files = classVirtualFiles[classId.relativeClassName.asString()] ?: return emptyList()

        // We don't need to filter the files if all extensions are requested.
        if (acceptedExtensions.extensions.size == JavaFileExtension.entries.size) {
            return files
        }

        return files.filter { file ->
            val extension = file.extension ?: return@filter false
            extension in acceptedExtensions
        }
    }

    private fun computeClassVirtualFiles(packageFqName: FqName): Map<String, List<VirtualFile>> {
        val result = HashMap<String, SmartList<VirtualFile>>()
        traverseVirtualFilesInPackage(packageFqName, ALL_ROOT_TYPES) { virtualFile, _ ->
            val extension = virtualFile.extension
            if (extension != null && extension in CACHED_EXTENSIONS && !virtualFile.isDirectory) {
                val relativeClassName = virtualFile.nameWithoutExtension.replace('$', '.')
                result.getOrPut(relativeClassName, ::SmartList).add(virtualFile)
            }
            true // continue
        }
        return result
    }

    companion object {
        private val ALL_ROOT_TYPES = JavaRoot.RootType.entries.toSet()
        private val CACHED_EXTENSIONS = JavaFileExtension.entries.mapTo(HashSet()) { it.extension }
    }
}
