/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.Name

/**
 * transformation:
 * ```
 * val seq = sequenceOf(1, 2, 3).map { it * 2 }.map { it + 1 }
 * for (x in seq) println(x)
 * ```
 * becomes
 * ```
 * val seq = sequenceOf(1, 2, 3)
 * val f1 = { y -> { x -> x * 2 }(y) + 1 }
 * for (x in seq) println(f1(x))
 * ```
 */
class SequenceFusionLowering(val context: JvmBackendContext) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        val transformer = SequenceFusionTransformer(context)
        irFile.transformChildrenVoid(transformer)
    }
}

data class MapFunctionType(val argumentType: IrType, val returnType: IrType)

private class SequenceFusionTransformer(val context: JvmBackendContext) : IrElementTransformerVoidWithContext() {
    private var sequenceVariableData: MutableMap<IrSymbol, SequenceData> = mutableMapOf()

    private inner class SequenceData(
        val hasNext: (IrExpression) -> IrExpression, // takes in the induction variable and returns whether the sequence has more elements (unused for now)
        val onNextType: MapFunctionType,
        val onNext: (IrExpression) -> IrExpression = { it }, // takes in a sequence element and returns its transformation
    ) {
        fun lift(builder: IrBuilderWithScope, function: IrRichFunctionReference): SequenceData {
            val newOnNext = combineFunctions(
                this.onNext
            ) { argument ->
                builder.callRichFunctionReference(function, argument)
            }
            val newSequenceData =
                SequenceData(
                    this.hasNext,
                    MapFunctionType(this.onNextType.argumentType, function.type),
                    newOnNext
                )
            return newSequenceData
        }

        fun lift(builder: IrBuilderWithScope, function: IrBlock): SequenceData {
            val newOnNext = combineFunctions(
                this.onNext
            ) { argument ->
                builder.callLoweredFunctionReference(function, argument)
            }
            val newSequenceData =
                SequenceData(
                    this.hasNext,
                    MapFunctionType(this.onNextType.argumentType, function.type),
                    newOnNext
                )
            return newSequenceData
        }
    }

    private fun combineFunctions(
        accumulator: (IrExpression) -> IrExpression,
        newFunction: (IrExpression) -> IrExpression
    ): (IrExpression) -> IrExpression = { x -> newFunction(accumulator(x)) }

    private fun IrBuilderWithScope.callRichFunctionReference(ref: IrRichFunctionReference, arg: IrExpression): IrExpression {
        val freshRef = ref.deepCopyWithSymbols()
        val parent = scope.scopeOwnerSymbol.owner as IrDeclarationParent
        freshRef.patchDeclarationParents(parent)
        return irCall(freshRef.overriddenFunctionSymbol).apply {
            dispatchReceiver = freshRef
            arguments[1] = arg
        }
    }

    private fun IrBuilderWithScope.callLoweredFunctionReference(
        functionRefExpr: IrExpression,
        arg: IrExpression
    ): IrExpression {
        val parent = scope.scopeOwnerSymbol.owner as IrDeclarationParent
        val freshRef = functionRefExpr.deepCopyWithSymbols().apply { patchDeclarationParents(parent) }
        val functionClassSymbol = context.irBuiltIns.functionN(1).symbol
        val invokeFun = functionClassSymbol.owner.functions.single { it.name == Name.identifier("invoke") }
        return irCall(invokeFun.symbol).apply {
            dispatchReceiver = freshRef
            arguments[1] = arg
        }
    }

    private fun createHasNext(size: Int): ((IrExpression) -> IrExpression) = { iv ->
        val lessThanSymbol = context.irBuiltIns.lessFunByOperandType[context.irBuiltIns.intType.classifierOrFail]!!
        val builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, iv.startOffset, iv.endOffset)
        builder.irCall(lessThanSymbol).apply {
            arguments[0] = iv
            arguments[1] = builder.irInt(size)
        }
    }

    private fun gatherSequenceDataFromExpression(expression: IrExpression): SequenceData? {
        val sequenceSymbol = context.symbols.sequence ?: return null
        if (!expression.type.isSubtypeOfClass(sequenceSymbol)) return null
        return when (expression) {
            is IrCall -> {
                val functionName = expression.symbol.owner.name.asString()
                when (functionName) {
                    "sequenceOf" -> {
                        val block = expression.arguments.getOrNull(0) as? IrBlock ?: return null
                        val blockSize = block.statements.size
                        if (blockSize < 2) return null // can this even happen?
                        val numberOfArguments = blockSize - 2
                        SequenceData(
                            createHasNext(numberOfArguments),
                            MapFunctionType(expression.type, expression.type)
                        )
                    }
                    "map" -> {
                        val mapReceiver = expression.arguments.getOrNull(0) ?: return null
                        val childData = gatherSequenceDataFromExpression(mapReceiver)
                        val builder =
                            context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset)
                        when (val mappedFunction = expression.arguments.getOrNull(1)) {
                            is IrRichFunctionReference -> {
                                val sequenceData = childData?.lift(builder, mappedFunction)
                                sequenceData
                            }
                            // it is a lowered function reference
                            is IrBlock -> {
                                val sequenceData = childData?.lift(builder, mappedFunction)
                                sequenceData
                            }
                            else -> null
                        }
                    }
                    // TODO: other functions than sequenceOf and map not yet supported
                    else -> null
                }
            }
            is IrGetValue -> {
                sequenceVariableData[expression.symbol] ?: error("Variable ${expression.symbol} was not stored in sequenceVariableData")
            }
            else -> null
        }
    }

    override fun visitVariable(declaration: IrVariable): IrStatement {
        val sequenceSymbol = context.symbols.sequence ?: return super.visitVariable(declaration)
        if (!declaration.type.isSubtypeOfClass(sequenceSymbol)) return super.visitVariable(declaration)
        val sequenceData = gatherSequenceDataFromExpression(declaration.initializer!!) ?: return super.visitVariable(declaration)
        sequenceVariableData[declaration.symbol] = sequenceData
        return super.visitVariable(declaration)
    }

    override fun visitBlock(expression: IrBlock): IrExpression {
        if (expression.origin != IrStatementOrigin.FOR_LOOP) return super.visitBlock(expression)
        val iteratorVariable = expression.statements.firstOrNull() as? IrVariable ?: return super.visitBlock(expression)
        val loop = expression.statements.getOrNull(1) as? IrWhileLoop ?: return super.visitBlock(expression)
        if (iteratorVariable.origin != IrDeclarationOrigin.FOR_LOOP_ITERATOR
            || loop.origin != IrStatementOrigin.FOR_LOOP_INNER_WHILE
        ) return super.visitBlock(expression)
        val initializer = iteratorVariable.initializer as? IrCall ?: return super.visitBlock(expression)
        val iterable = initializer.arguments.firstOrNull() ?: return super.visitBlock(expression)
        val sequenceClass = context.symbols.sequence ?: return super.visitBlock(expression)
        if (!iterable.type.isSubtypeOfClass(sequenceClass)) return super.visitBlock(expression)
        // TODO: finding all occurrences of the iterator in loop and replacing it by onNext(iv) with while(hasNext(iv)) condition
        // with sequenceOf the argument is just an array, so it should be simple
        // also replace the iterator variable with a simple inductionVariable

        val sequenceData = gatherSequenceDataFromExpression(iterable) ?: return super.visitBlock(expression)
        val transformer = SequenceForLoopBodyTransformer(sequenceData.onNext)
        loop.transformChildrenVoid(transformer)
        return super.visitBlock(expression)
    }

    private fun hasLambdaCapturedVariables(lambda: IrSimpleFunction): Boolean {
        val localsAndParameters = HashSet<IrValueSymbol>()

        for (parameter in lambda.parameters) {
            (parameter as? IrValueDeclaration)?.symbol?.let(localsAndParameters::add)
        }

        lambda.body?.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitDeclaration(declaration: IrDeclarationBase) {
                (declaration as? IrValueDeclaration)?.symbol?.let(localsAndParameters::add)
                super.visitDeclaration(declaration)
            }
        })
        var anyCaptured = false
        lambda.body?.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitGetValue(expression: IrGetValue) {
                if (expression.symbol !in localsAndParameters) {
                    anyCaptured = true
                }
            }
        })

        return anyCaptured
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val sequenceClass = context.symbols.sequence ?: return super.visitCall(expression)
        if (!expression.type.isSubtypeOfClass(sequenceClass)) return super.visitCall(expression)
        val functionName = expression.symbol.owner.name.asString()
        when (functionName) {
            "map" -> {
                // remove calls of 'map'
                // TODO: find out when not to remove them
                val receiver = expression.arguments.getOrNull(0) ?: return super.visitCall(expression)
                val mappedFunction = expression.arguments.getOrNull(1) ?: return super.visitCall(expression)
                when (mappedFunction) {
                    is IrRichFunctionReference -> {
                        val hasLambdaCapturedVariables = hasLambdaCapturedVariables(mappedFunction.invokeFunction)
                        if (hasLambdaCapturedVariables) return super.visitCall(expression)
                    }
                }
                return when (receiver) {
                    is IrCall -> visitCall(receiver)
                    else -> receiver
                }
            }
        }
        return super.visitCall(expression)
    }

    private inner class SequenceForLoopBodyTransformer(
        val onNext: (IrExpression) -> IrExpression,
    ) : IrElementTransformerVoidWithContext() {
        override fun visitCall(expression: IrCall): IrExpression {
            if (expression.origin != IrStatementOrigin.FOR_LOOP_NEXT)
                return super.visitCall(expression)
            return onNext(expression)
        }
    }
}
