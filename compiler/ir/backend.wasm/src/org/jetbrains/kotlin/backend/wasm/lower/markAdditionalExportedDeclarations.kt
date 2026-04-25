/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.ir.builders.irAnnotation
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.FqName

/**
 * Mark declaration with [fqName] fqn with @JsExport annotation.
 * The declaration must be a function with no parameters, returning `kotlin.String`.
 */
fun markExportedDeclaration(context: WasmBackendContext, irFile: IrFile, fqName: FqName) {
    if (irFile.packageFqName != fqName.parent()) return

    val exportConstructor = when (context.isWasmJsTarget) {
        true -> context.wasmSymbols.jsRelatedSymbols.jsExportConstructor
        else -> context.wasmSymbols.wasmExportConstructor
    }

    irFile.declarations.find {
        it is IrFunction && it.parameters.isEmpty() &&
                (it.returnType.isString() ||
                        it.returnType.isUnit()) && // Parts of stepping tests using `box` fun returning `Unit`. 
                it.fqNameWhenAvailable == fqName
    }?.let {
        val builder = context.createIrBuilder(irFile.symbol)
        it.annotations += builder.irAnnotation(exportConstructor, typeArguments = emptyList())
    }
}
