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
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irWhile
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isImmutable
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

private const val ITERATOR = "iterator"
private const val HAS_NEXT = "hasNext"
private const val NEXT = "next"
private const val SEQUENCE_OF = "sequenceOf"
private const val AS_SEQUENCE = "asSequence"
private const val GENERATE_SEQUENCE = "generateSequence"
private const val MAP = "map"
private const val FILTER = "filter"

/**
 * transformation:
 * ```
 * fun myFun(seq: Sequence<Int>) {
 *     val seq2 = seq.map { it * 2 }.map { it + 1 }
 *     for (x in seq) println(x)
 * }
 * ```
 * becomes
 * ```
 * fun myFun(seq: Sequence<Int>) {
 *     val seq2 = seq.map { it * 2 }.map { it + 1 }
 *     for (x in seq) println({ y -> { x -> x * 2 }(y) + 1 }(x))
 * }
 * ```
 *
 * ```
 * val seq = sequenceOf(1, 2, 3).map { it * 2 }.map { it + 1 }
 * for (x in seq) println(x)
 * ```
 * becomes
 * ```
 * val seq = sequenceOf(1, 2, 3).map { it * 2 }.map { it + 1 }
 * {
 *     println({ y -> { x -> x * 2 }(y) + 1 }(1))
 *     println({ y -> { x -> x * 2 }(y) + 1 }(2))
 *     println({ y -> { x -> x * 2 }(y) + 1 }(3))
 * }
 * ```
 */


class SequenceFusionLowering(val context: JvmBackendContext) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        val transformer = SequenceFusionTransformer(context)
        irFile.transformChildrenVoid(transformer)
    }
}

sealed class GenerateInitialValue {
    class InitialValue(val expression: IrExpression) : GenerateInitialValue()
    class InitialFunction(val function: IrRichFunctionReference) : GenerateInitialValue()
    class NoInitialValue : GenerateInitialValue()
}

// sequenceSource is what the sequence was created from, to be substituted if the loop is to be fused
private sealed class SequenceSource {
    class SequenceOf(val elements: List<IrExpression>) : SequenceSource()
    class Variable(val variable: IrValueSymbol) : SequenceSource()
    class GenerateSequence(val initialValue: GenerateInitialValue, val generatingFunction: IrRichFunctionReference) : SequenceSource()
    class AsSequence(val iterable: IrExpression) : SequenceSource()
}

private class SequenceData(
    val mapReplacement: MapReplacement = { _, argument -> argument },
    val sequenceSource: SequenceSource? = null,
    val filterReplacement: FilterReplacement = { _, valueGenerator, expressionModifier -> expressionModifier(valueGenerator) },
) {
    // mapReplacement for a given sequence expression stores a composition of functions applied to the base sequence via `map`
    typealias MapReplacement = (IrBuilderWithScope, IrExpression) -> IrExpression

    fun applyMap(function: IrRichFunctionReference): SequenceData =
        SequenceData(
            composeMapReplacements(
                this.mapReplacement
            ) { builder, argument ->
                builder.callRichFunctionReference(function, argument)
            },
            this.sequenceSource,
            this.filterReplacement
        )

    private fun composeMapReplacements(
        accumulator: MapReplacement,
        newFunction: MapReplacement,
    ): MapReplacement = { builder, argument -> newFunction(builder, accumulator(builder, argument)) }
    /**
     * Filter replacement is constructed like this:
     * ```
     *    { initialValue, expressionDependentOnValue ->
     *        val value1 = firstMapReplacement(initialValue)
     *       val isNotFiltered1 = firstFilter(value1)
     *       if (isNotFiltered1) {
     *           val value2 = secondMapReplacement(value1)
     *           val isNotFiltered2 = secondFilter(value2)
     *           if (isNotFiltered2) {
     *               ... {
     *                   expressionDependentOnValue(finalValue)
     *               }
     *           }
     *        }
     *    }
     * ```
     */
    typealias FilterReplacement = (IrBuilderWithScope, IrExpression, (IrExpression) -> IrExpression) -> IrExpression

    fun createNewFilterSegment(
        filterFunction: IrRichFunctionReference,
    ): FilterReplacement = { builder, valueGenerator, expressionDependentOnValue ->
        builder.irBlock {
            val newValue = irTemporary(mapReplacement(builder, valueGenerator))
            val willStay = irTemporary(callRichFunctionReference(filterFunction, irGet(newValue)))
            +irIfThen(context.irBuiltIns.unitType, irGet(willStay), expressionDependentOnValue(irGet(newValue)))
        }
    }

    fun composeFilterReplacements(accumulator: FilterReplacement, nextSegment: FilterReplacement): FilterReplacement =
        { builder, valueGenerator, expressionDependentOnValue ->
            accumulator(builder, valueGenerator) { nextValue -> nextSegment(builder, nextValue, expressionDependentOnValue) }
        }

    fun applyFilter(
        filterFunction: IrRichFunctionReference,
    ): SequenceData {
        val newFilterReplacement = composeFilterReplacements(filterReplacement, createNewFilterSegment(filterFunction))
        // NOTE: mapReplacement is not reassigned, it is reset back to identity
        return SequenceData(
            sequenceSource = sequenceSource,
            filterReplacement = newFilterReplacement,
        )
    }
}

private fun IrBuilderWithScope.callRichFunctionReference(ref: IrRichFunctionReference, vararg args: IrExpression): IrExpression {
    val freshRef = deepCopyAndPatch(ref, this)
    val functionType =
        freshRef.type as? IrSimpleType ?: error("Expected rich function reference to have IrSimpleType, got: ${freshRef.type}")
    val returnType = functionType.arguments.lastOrNull()?.typeOrNull
        ?: error("Expected function type with return type argument, got: ${freshRef.type}")
    return irCall(freshRef.overriddenFunctionSymbol, returnType).apply {
        dispatchReceiver = freshRef
        var index = 1
        for (arg in args) {
            arguments[index++] = arg
        }
    }
}

private fun IrBuilderWithScope.irNotNull(value: IrExpression): IrExpression {
    val nonNullType = value.type.makeNotNull()
    return IrTypeOperatorCallImpl(
        startOffset,
        endOffset,
        nonNullType,
        IrTypeOperator.IMPLICIT_NOTNULL,
        nonNullType,
        value
    )
}

private inline fun <reified T : IrElement> deepCopyAndPatch(element: T, builder: IrBuilderWithScope): T {
    val elementCopy = element.deepCopyWithSymbols()
    val parent = builder.scope.scopeOwnerSymbol.owner as? IrDeclarationParent
        ?: error("Provided builder didn't have scopeOwnerSymbol as an IrDeclarationParent")
    elementCopy.patchDeclarationParents(parent)
    return elementCopy
}

// this is stored for expressions, intended to be passed either to value declarations or to for loops iterated over the expression result
private var IrExpression.sequenceDataOfExpression: SequenceData? by irAttribute(true)

// this is stored to be one of the future sources of sequence data of expressions
private var IrValueDeclaration.sequenceDataOfVariable: SequenceData? by irAttribute(true)
// In general, sequence data is gathered from `sequenceOf` or existing sequence variables, modified `by` map calls,
// and consumed by for loops and variable declarations

private class SequenceFusionTransformer(val context: JvmBackendContext) : IrElementTransformerVoidWithContext() {
    private fun isElementSequence(element: IrElement): Boolean {
        val sequenceSymbol = context.symbols.sequence ?: return false
        val type = when (element) {
            is IrExpression -> element.type
            is IrVariable -> element.type
            else -> return false
        }
        return type.isSubtypeOfClass(sequenceSymbol)
    }


    override fun visitVariable(declaration: IrVariable): IrStatement {
        val visitResult = super.visitVariable(declaration)
        if (declaration.isVar) return visitResult
        val lValueAfterVisit = visitResult as? IrVariable ?: return visitResult
        if (!isElementSequence(lValueAfterVisit)) return visitResult
        lValueAfterVisit.symbol.owner.sequenceDataOfVariable = lValueAfterVisit.initializer?.sequenceDataOfExpression ?: SequenceData(
            sequenceSource = SequenceSource.Variable(lValueAfterVisit.symbol)
        )
        return lValueAfterVisit
    }

    private fun hasLambdaCapturedVariables(function: IrFunction): Boolean {
        val localSymbols = hashSetOf<IrValueSymbol>()
        var hasCaptured = false

        function.parameters.forEach { localSymbols += it.symbol }

        function.body?.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (!hasCaptured) {
                    element.acceptChildrenVoid(this)
                }
            }

            override fun visitFunction(declaration: IrFunction) {} // skip nested functions

            override fun visitDeclaration(declaration: IrDeclarationBase) {
                if (declaration is IrValueDeclaration) {
                    localSymbols += declaration.symbol
                }
                super.visitDeclaration(declaration)
            }

            override fun visitGetValue(expression: IrGetValue) {
                if (expression.symbol !in localSymbols) {
                    hasCaptured = true
                }
            }
        })

        return hasCaptured
    }

    private fun isSafeToLower(reference: IrRichFunctionReference): Boolean {
        if (reference.boundValues.isNotEmpty()) return false
        if (reference.invokeFunction.dispatchReceiverParameter != null) return false
        if (hasLambdaCapturedVariables(reference.invokeFunction)) return false
        return true
    }

    // checks if the applied function is safe to be lowered, then updates the sequence data if it is
    private inline fun tryToApplyFunction(
        call: IrCall,
        applyFunction: (SequenceData, IrRichFunctionReference) -> SequenceData
    ) {
        val receiver = call.arguments.getOrNull(0) ?: return
        val fnArg = call.arguments.getOrNull(1) ?: return
        val fnRef = fnArg as? IrRichFunctionReference ?: return
        if (!isSafeToLower(fnRef)) return

        val receiverData = receiver.sequenceDataOfExpression ?: return
        call.sequenceDataOfExpression = applyFunction(receiverData, fnRef)
    }

    private fun containsMutable(expression: IrExpression): Boolean {
        var found = false
        expression.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (!found) {
                    element.acceptChildrenVoid(this)
                }
            }

            override fun visitGetValue(expression: IrGetValue) {
                val variable = expression.symbol.owner as? IrVariable ?: return
                if (variable.isVar) {
                    found = true
                }
            }
        })
        return found
    }

    private fun matchWithGenerateSequence(expression: IrCall): IrExpression? {
        val (initialValue, func) = when (expression.arguments.size) {
            1 -> {
                // generateSequence(() -> T?)
                // we do not lower this overload, since it is not reusable,
                // and it is not possible to remove the iterator without breaking its exception throwing on reuse
                // if it is assigned to a variable, then the unknown variable lowering still occurs
                return null
            }
            2 -> {
                val initialValueOrFunction = expression.arguments.getOrNull(0)
                val func = expression.arguments.getOrNull(1) as? IrRichFunctionReference ?: return null
                when (initialValueOrFunction) {
                    is IrRichFunctionReference -> {
                        // generateSequence(() -> T?, (T) -> T?)
                        GenerateInitialValue.InitialFunction(initialValueOrFunction) to func
                    }
                    else -> {
                        // generateSequence(T?, (T) -> T?)
                        if (initialValueOrFunction == null) return null
                        if (containsMutable(initialValueOrFunction)) return null
                        GenerateInitialValue.InitialValue(initialValueOrFunction) to func
                    }
                }
            }
            else -> {
                null
            }
        } ?: return null
        expression.sequenceDataOfExpression = SequenceData(sequenceSource = SequenceSource.GenerateSequence(initialValue, func))
        return expression
    }


    private fun matchWithSequenceOf(expression: IrCall) {
        // store the sequence of arguments inside the sequence source
        if (expression.arguments.size != 1) return
        val argument = expression.arguments.getOrNull(0) ?: return
        val sequenceOfArguments: List<IrExpression>
        if (argument is IrVararg) {
            // sequenceOf(vararg arguments)
            if (argument.elements.any { it is IrSpreadElement }) return // skip lowering sequenceOf with spread arguments
            if (argument.elements.any { it !is IrExpression }) return
            if (argument.elements.filterIsInstance<IrGetValue>().any { !it.symbol.owner.isImmutable }) return
            sequenceOfArguments = argument.elements.map { it as IrExpression }
        } else {
            // sequenceOf(argument)
            if (argument is IrGetValue && !argument.symbol.owner.isImmutable) return
            sequenceOfArguments = listOf(argument)
        }
        expression.sequenceDataOfExpression = SequenceData(
            sequenceSource = SequenceSource.SequenceOf(sequenceOfArguments)
        )
    }

    private fun matchWithAsSequence(expression: IrCall) {
        val innerMostReceiver = getInnerMostReceiver(expression) ?: return
        val receiver = expression.arguments.getOrNull(0) ?: return
        if (innerMostReceiver !is IrGetValue) return
        val receiverVariable = innerMostReceiver.symbol.owner
        if (!receiverVariable.isImmutable || !receiverVariable.type.isSubtypeOfClass(context.irBuiltIns.iterableClass)) return
        expression.sequenceDataOfExpression = SequenceData(
            sequenceSource = SequenceSource.AsSequence(receiver)
        )
    }

    override fun visitCall(expression: IrCall): IrExpression {
        super.visitCall(expression)
        if (!isElementSequence(expression)) return expression
        val functionName = expression.symbol.owner.name.asString()
        when (functionName) {
            MAP -> tryToApplyFunction(expression, SequenceData::applyMap)
            FILTER -> tryToApplyFunction(expression, SequenceData::applyFilter)
            GENERATE_SEQUENCE -> return matchWithGenerateSequence(expression) ?: expression
            SEQUENCE_OF -> matchWithSequenceOf(expression)
            AS_SEQUENCE -> matchWithAsSequence(expression)
        }
        return expression
    }

    // assigns sequence data of the variable to the corresponding expression
    override fun visitGetValue(expression: IrGetValue): IrExpression {
        super.visitGetValue(expression)
        // now the children have assigned appropriate sequence data
        if (!isElementSequence(expression)) return expression
        expression.sequenceDataOfExpression = expression.symbol.owner.sequenceDataOfVariable
                // even if we know nothing about the variable, it could be the case that it will be transformed later, and this can be lowered
            ?: SequenceData(sequenceSource = SequenceSource.Variable(expression.symbol))
        return expression
    }

    private class LoopData(
        val block: IrBlock,
    ) {
        val iteratorDeclaration: IrVariable
            get() = block.statements[0] as IrVariable

        val loop: IrWhileLoop
            get() = block.statements[1] as IrWhileLoop

        val iterable: IrExpression
            get() = (iteratorDeclaration.initializer as IrCall).arguments[0]!!

        val loopBody: IrBlock
            get() = loop.body as IrBlock

        val nextDeclaration: IrVariable
            get() = loopBody.statements[0] as IrVariable

        fun deepCopy(builder: IrBuilderWithScope): LoopData =
            LoopData(deepCopyAndPatch(block, builder))
    }

    private fun matchWithSequenceIteration(block: IrBlock): LoopData? {
        if (block.origin != IrStatementOrigin.FOR_LOOP) return null

        // extract loop iterator variable and loop body from IrBlock
        if (block.statements.size != 2) return null
        val iteratorDeclaration = block.statements[0] as? IrVariable ?: return null
        val loop = block.statements[1] as? IrWhileLoop ?: return null

        val possiblySequenceInitializer = iteratorDeclaration.initializer as? IrCall ?: return null
        val iterable = possiblySequenceInitializer.arguments.firstOrNull() ?: return null
        if (!isElementSequence(iterable)) return null
        if (loop.body !is IrBlock) return null
        return LoopData(block)
    }

    private fun isSequenceProducer(expression: IrCall): Boolean {
        if (!isElementSequence(expression)) return false
        if (expression.arguments.any { argument -> argument?.let { isElementSequence(it) } ?: false }) return false
        // no arguments are sequences, yet it returns a sequence
        return true
    }

    private fun getInnerMostReceiver(expression: IrExpression): IrExpression? {
        when (expression) {
            is IrCall -> {
                if (isSequenceProducer(expression)) return expression
                val receiver = expression.arguments.getOrNull(0) ?: return null
                return getInnerMostReceiver(receiver)
            }
            is IrGetValue -> return expression
            else -> return null
        }
    }

    private fun lookupForLoopVariable(loopBody: IrBlock): IrVariable? = loopBody.statements.filterIsInstance<IrVariable>()
        .singleOrNull { v -> v.origin == IrDeclarationOrigin.FOR_LOOP_VARIABLE }

    private fun IrBuilderWithScope.rebuildCallWithDifferentReceiver(
        receiver: IrExpression,
        receiverType: IrType,
        functionName: String,
    ): IrCall? {
        val function = receiverType.getClass()?.functions?.singleOrNull { function ->
            function.name.asString() == functionName && function.parameters.size == 1
        } ?: return null
        return irCall(function.symbol).apply {
            arguments[0] = receiver
        }
    }

    private class VariableReplacer(val builder: IrBuilderWithScope, val oldVariable: IrVariable, val newVariable: IrVariable) :
        IrElementTransformerVoidWithContext() {
        override fun visitGetValue(expression: IrGetValue): IrExpression {
            if (expression.symbol == oldVariable.symbol) {
                check(expression.type == newVariable.type)
                return builder.irGet(newVariable)
            }
            return super.visitGetValue(expression)
        }
    }

    /**
     * Transforms loop body:
     * ```
     *  {
     *      val next = iterator.next()
     *      body(next)
     *  }
     * ```
     * into
     * ```
     *  {
     *      val mappedValue = mapReplacement(filterReplacement(initialValue))
     *      body(mappedValue)
     *  }
     * ```
     */
    private fun transformLoopBody(
        builder: IrBuilderWithScope,
        bodyRewriter: (IrVariable) -> IrBlock,
        sequenceData: SequenceData,
        initialValue: IrExpression,
    ): IrExpression {
        return sequenceData.filterReplacement(
            builder,
            initialValue,
        ) { filteredValue ->
            val mappedValue = sequenceData.mapReplacement(builder, filteredValue)
            builder.irBlock {
                val newLoopVariable = irTemporary(mappedValue)
                +bodyRewriter(newLoopVariable)
            }
        }
    }

    private fun createBodyExpectingNewLoopVariable(
        builder: IrBuilderWithScope,
        oldLoopVariable: IrVariable,
        body: IrBlock,
    ): (IrVariable) -> IrBlock = { newLoopVariable ->
        body.transformChildrenVoid(VariableReplacer(builder, oldLoopVariable, newLoopVariable))
        body
    }

    /**
     * If we know that a sequence is a transformation of sequenceOf to which we know the arguments to,
     * we transform a loop into a block evaluating the loop body on each element of the sequence.
     * ```
     * val seq = sequenceOf(1, 2).map { it - 1 }
     * for (el in seq) println(el)
     * ```
     * becomes
     * ```
     * {
     * println({ it - 1 }(1))
     * println({ it - 1 }(2))
     * }
     * ```
     * */
    private fun lowerFromSequenceOf(
        builder: IrBuilderWithScope,
        sequenceData: SequenceData,
        loopData: LoopData,
        sequenceSource: SequenceSource.SequenceOf,
    ): IrExpression? {
        var wasBreak = false
        val breakContinueFinder = object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (!wasBreak) element.acceptChildrenVoid(this)
            }

            override fun visitBreak(jump: IrBreak) {
                if (jump.loop == loopData.loop) {
                    wasBreak = true
                }
            }

            override fun visitContinue(jump: IrContinue) {
                if (jump.loop == loopData.loop) {
                    wasBreak = true
                }
            }
        }
        loopData.loopBody.acceptVoid(breakContinueFinder)
        if (wasBreak) return null
        return builder.irBlock {
            sequenceSource.elements.forEach { sequenceOfValue ->
                val sequenceOfValueCopy = deepCopyAndPatch(sequenceOfValue, builder)
                val newBody = deepCopyAndPatch(loopData.loopBody, builder)
                newBody.origin = null // we remove the LOOP_INNER_WHILE origin, as the result is not a while loop anymore
                val loopVariable = lookupForLoopVariable(newBody) ?: return null
                newBody.statements.remove(loopVariable)
                val bodyRewriter = createBodyExpectingNewLoopVariable(builder, loopVariable, newBody)
                +transformLoopBody(
                    builder,
                    bodyRewriter,
                    sequenceData,
                    sequenceOfValueCopy
                )
            }
        }
    }

    private fun transformLoopBodyPreservingLoopVariable(
        builder: IrBuilderWithScope,
        sequenceData: SequenceData,
        loopData: LoopData,
    ): IrBlock? {
        val copiedLoopData = loopData.deepCopy(builder)
        val loopVariable = lookupForLoopVariable(copiedLoopData.loopBody) ?: return null
        val bodyRewriter = createBodyExpectingNewLoopVariable(builder, loopVariable, copiedLoopData.loopBody)
        copiedLoopData.loopBody.statements.remove(loopVariable)
        copiedLoopData.loop.body = builder.irBlock {
            +loopVariable
            +transformLoopBody(builder, bodyRewriter, sequenceData, builder.irGet(loopVariable))
        }
        return copiedLoopData.block
    }

    /**
     * We cannot fuse if we iterate over some transformation of a variable, for example,
     * ```
     * fun myFun(sequence: Sequence<Int>) {
     *     val seq2 = sequence.map { it * 2 }
     *     for (el in seq2.map { it + 1 }) {
     *         println(el)
     *     }
     * }
     * ```
     * cannot be lowered, because there is no way of applying { it * 2 } before { it + 1 } without changing the declaration of seq2.
     * But
     * ```
     * fun myFun(sequence: Sequence<Int>) {
     *     val seq2 = sequence.map { it * 2 }
     *     for (el in seq2) {
     *         println(el)
     *     }
     * }
     * ```
     * can be lowered into
     * ```
     * fun myFun(sequence: Sequence<Int>) {
     *     val seq2 = sequence.map { it * 2 }
     *     for (el in seq) {
     *         println({ it * 2 }(el))
     *     }
     * }
     * ```
     * */
    private fun lowerFromUnknownVariable(
        builder: IrBuilderWithScope,
        sequenceData: SequenceData,
        loopData: LoopData,
        sequenceSource: SequenceSource.Variable,
    ): IrBlock? {
        val wasUpdated = updateIteratorCalls(
            loopData,
            builder,
            builder.irGet(sequenceSource.variable.owner)
        )
        if (!wasUpdated) return null
        return transformLoopBodyPreservingLoopVariable(builder, sequenceData, loopData)
    }

    private data class IteratorReplacementForGenerateSequence(
        val iteratorVariable: IrVariable,
        val nextExpression: IrExpression,
        val hasNextReplacement: IrExpression,
    )

    private fun createIteratorReplacement(
        initialExpression: IrExpression,
        evaluateNext: (IrVariable) -> IrExpression,
        builder: IrBuilderWithScope,
    ): IteratorReplacementForGenerateSequence = with(builder) {
        val sequenceElement = scope.createTemporaryVariable(
            initialExpression,
            isMutable = true,
            irType = initialExpression.type.makeNullable(),
        )
        val condition = irNotEquals(irGet(sequenceElement), irNull())
        val next = evaluateNext(sequenceElement)
        return IteratorReplacementForGenerateSequence(sequenceElement, condition, next)
    }

    private fun buildGenerateSequenceBody(
        builder: IrBuilderWithScope,
        sequenceData: SequenceData,
        loopBody: IrBlock,
        inductionVariable: IrVariable,
        newCondition: IrExpression,
        iteratorNextReplacement: IrExpression,
    ): IrExpression? = builder.irBlock {
        +inductionVariable
        +irWhile().apply {
            condition = newCondition
            body = irBlock {
                val currentSequenceElement = irTemporary(irNotNull(irGet(inductionVariable)))
                +irSet(inductionVariable, iteratorNextReplacement)
                val newBody = deepCopyAndPatch(loopBody, this)
                val loopVariable = lookupForLoopVariable(newBody) ?: return null
                newBody.statements.remove(loopVariable)
                val bodyRewriter = createBodyExpectingNewLoopVariable(this, loopVariable, newBody)

                +transformLoopBody(this, bodyRewriter, sequenceData, irGet(currentSequenceElement))
            }
        }
    }

    private fun lowerFromGenerateSequence(
        sequenceSource: SequenceSource.GenerateSequence,
        builder: IrBuilderWithScope,
        sequenceData: SequenceData,
        loopBody: IrBlock,
        generatingFunction: IrRichFunctionReference,
    ): IrExpression? {
        val nextFromCurrent: (IrVariable) -> IrExpression = { variable ->
            builder.callRichFunctionReference(generatingFunction, builder.irNotNull(builder.irGet(variable)))
        }

        val (initialExpression, nextEvaluator) = when (val initialValue = sequenceSource.initialValue) {
            is GenerateInitialValue.InitialValue -> {
                deepCopyAndPatch(initialValue.expression, builder) to nextFromCurrent
            }
            is GenerateInitialValue.InitialFunction -> {
                builder.callRichFunctionReference(initialValue.function) to nextFromCurrent
            }
            is GenerateInitialValue.NoInitialValue -> {
                return null
            }
        }
        val (inductionVariable, newCondition, iteratorNextReplacement) =
            createIteratorReplacement(initialExpression, nextEvaluator, builder)
        return buildGenerateSequenceBody(builder, sequenceData, loopBody, inductionVariable, newCondition, iteratorNextReplacement)
    }

    // updates .iterator() .hasNext() and .next() calls to be called on newIteratorTarget
    private fun updateIteratorCalls(
        loopData: LoopData,
        builder: IrBuilderWithScope,
        newIteratorTarget: IrExpression,
    ): Boolean {
        with(builder) {
            val baseType = (newIteratorTarget.type as? IrSimpleType)?.arguments?.getOrNull(0)?.typeOrNull ?: return false
            val iteratorType = context.irBuiltIns.iteratorClass.typeWith(baseType)
            val iteratorCall = rebuildCallWithDifferentReceiver(newIteratorTarget, newIteratorTarget.type, ITERATOR) ?: return false
            val nextCall = rebuildCallWithDifferentReceiver(irGet(loopData.iteratorDeclaration), iteratorType, NEXT) ?: return false
            val hasNextCall = rebuildCallWithDifferentReceiver(irGet(loopData.iteratorDeclaration), iteratorType, HAS_NEXT) ?: return false

            loopData.apply {
                iteratorDeclaration.apply {
                    initializer = iteratorCall
                    type = iteratorType
                }
                nextDeclaration.apply {
                    initializer = nextCall
                    type = baseType
                }
                loop.condition = hasNextCall
            }
            return true
        }
    }

    private fun lowerFromAsSequence(
        builder: IrBuilderWithScope,
        sequenceData: SequenceData,
        loopData: LoopData,
        sequenceSource: SequenceSource.AsSequence,
    ): IrExpression? {
        val wasUpdateSuccessful = updateIteratorCalls(
            loopData,
            builder,
            sequenceSource.iterable
        )
        if (!wasUpdateSuccessful) return null
        return transformLoopBodyPreservingLoopVariable(builder, sequenceData, loopData)
    }

    // This is where the actual transformation takes place
    override fun visitBlock(expression: IrBlock): IrExpression {
        val result = super.visitBlock(expression)
        if (result !is IrBlock) return result

        val loopData = matchWithSequenceIteration(result) ?: return result
        val innerMostReceiverSequenceData = getInnerMostReceiver(loopData.iterable)?.sequenceDataOfExpression ?: return result
        val builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset)
        val sequenceSource = innerMostReceiverSequenceData.sequenceSource ?: return result
        val sequenceData = loopData.iterable.sequenceDataOfExpression ?: return result

        return when (sequenceSource) {
            is SequenceSource.SequenceOf -> {
                lowerFromSequenceOf(builder, sequenceData, loopData, sequenceSource) ?: result
            }
            is SequenceSource.Variable -> {
                // if iterable is not IrGetValue, we do not lower, we cannot substitute sequenceSource for sequence.map(...) or sequence.filter(...)
                if (loopData.iterable !is IrGetValue) {
                    return result
                }
                lowerFromUnknownVariable(builder, innerMostReceiverSequenceData, loopData, sequenceSource) ?: result
            }
            is SequenceSource.GenerateSequence -> {
                lowerFromGenerateSequence(sequenceSource, builder, sequenceData, loopData.loopBody, sequenceSource.generatingFunction)
                    ?: result
            }
            is SequenceSource.AsSequence -> {
                lowerFromAsSequence(builder, sequenceData, loopData, sequenceSource) ?: result
            }
        }
    }
}