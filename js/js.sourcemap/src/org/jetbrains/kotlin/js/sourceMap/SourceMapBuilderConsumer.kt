/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.js.sourceMap

import org.jetbrains.kotlin.js.backend.SourceLocationConsumer
import org.jetbrains.kotlin.js.backend.ast.JsLocation
import org.jetbrains.kotlin.js.backend.ast.JsLocationWithSource
import org.jetbrains.kotlin.utils.addToStdlib.popLast
import java.io.File
import java.io.IOException

class SourceMapBuilderConsumer(
    private val sourceBaseDir: File,
    private val mappingConsumer: SourceMapMappingConsumer,
    private val pathResolver: SourceFilePathResolver,
    private val provideExternalModuleContent: Boolean
) : SourceLocationConsumer {

    private val sourceStack = mutableListOf<JsLocationWithSource?>()
    private var ignoreLocationDepth = 0

    override fun newLine() {
        mappingConsumer.newLine()
    }

    override fun pushSourceInfo(info: JsLocationWithSource?) {
        if (info?.asSimpleLocation() == JsLocation.Ignored)
            ignoreLocationDepth++
        sourceStack.add(info)
        addMapping(info)
    }

    override fun popSourceInfo() {
        if (sourceStack.popLast()?.asSimpleLocation() == JsLocation.Ignored)
            ignoreLocationDepth--
        addMapping(sourceStack.lastOrNull())
    }

    private fun addMapping(sourceInfo: JsLocationWithSource?) {
        if (ignoreLocationDepth > 0) {
            mappingConsumer.addMapping(
                JsLocation.Ignored.file,
                JsLocation.Ignored.fileIdentity,
                { null },
                JsLocation.Ignored.startLine,
                JsLocation.Ignored.startChar,
                JsLocation.Ignored.name
            )
            return
        }

        if (sourceInfo == null) {
            mappingConsumer.addEmptyMapping()
            return
        }

        val contentSupplier = if (provideExternalModuleContent) sourceInfo.sourceProvider else {
            { null }
        }
        val sourceFile = File(sourceInfo.file)
        val absFile = if (sourceFile.isAbsolute) sourceFile else File(sourceBaseDir, sourceInfo.file)
        val path = if (absFile.isAbsolute) {
            try {
                pathResolver.getPathRelativeToSourceRoots(absFile)
            } catch (e: IOException) {
                sourceInfo.file
            }
        } else {
            sourceInfo.file
        }
        mappingConsumer.addMapping(
            path,
            sourceInfo.fileIdentity,
            contentSupplier,
            sourceInfo.startLine,
            sourceInfo.startChar,
            sourceInfo.name
        )
    }
}
