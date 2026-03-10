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
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

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
    class GenerateSequence(val initialValue: GenerateInitialValue, val function: IrRichFunctionReference) : SequenceSource()
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
    val freshRef = ref.deepCopyWithSymbols()
    val parent = getParentFromBuilder(this)
    freshRef.patchDeclarationParents(parent)
    return irCall(freshRef.overriddenFunctionSymbol).apply {
        dispatchReceiver = freshRef
        var index = 1
        for (arg in args) {
            arguments[index++] = arg
        }
    }
}

fun IrBuilderWithScope.irNotNull(value: IrExpression): IrExpression {
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

private fun getParentFromBuilder(builder: IrBuilderWithScope): IrDeclarationParent {
    return builder.scope.scopeOwnerSymbol.owner as? IrDeclarationParent
        ?: error("Provided builder didn't have scopeOwnerSymbol as an IrDeclarationParent")
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

    // assigns the sequence data found on the right-hand side to the value declaration
    private inline fun <reified T : IrElement, reified R> visitLValue(
        node: T,
        sequenceDataProvider: (T) -> SequenceData?,
        sequenceDataConsumer: (T) -> IrValueDeclaration,
        check: (T) -> Boolean,
        superVisit: (T) -> R
    ): R {
        val visitResult = superVisit(node)
        val lValueAfterVisit = visitResult as? T ?: return visitResult
        if (!check(lValueAfterVisit)) return visitResult
        sequenceDataConsumer(lValueAfterVisit).sequenceDataOfVariable = sequenceDataProvider(lValueAfterVisit)
        return lValueAfterVisit as? R ?: visitResult
    }

    override fun visitVariable(declaration: IrVariable): IrStatement {
        return visitLValue(
            node = declaration,
            sequenceDataProvider = { it.initializer?.sequenceDataOfExpression },
            sequenceDataConsumer = { it.symbol.owner },
            check = { isElementSequence(it) },
            superVisit = { super.visitVariable(it) }
        )
    }

    override fun visitSetValue(expression: IrSetValue): IrExpression {
        return visitLValue(
            node = expression,
            sequenceDataProvider = { it.value.sequenceDataOfExpression },
            sequenceDataConsumer = { it.symbol.owner },
            check = { isElementSequence(it.value) },
            superVisit = { super.visitSetValue(it) }
        )
    }

    private fun hasLambdaCapturedVariables(lambda: IrSimpleFunction): Boolean {
        val localsAndParameters = HashSet<IrValueSymbol>()
        for (parameter in lambda.parameters) {
            (parameter as? IrValueDeclaration)?.symbol?.let(localsAndParameters::add)
        }

        var anyCaptured = false
        lambda.body?.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (anyCaptured) return
                element.acceptChildrenVoid(this)
            }

            override fun visitGetValue(expression: IrGetValue) {
                if (expression.symbol !in localsAndParameters) {
                    anyCaptured = true
                }
            }

            override fun visitDeclaration(declaration: IrDeclarationBase) {
                (declaration as? IrValueDeclaration)?.symbol?.let(localsAndParameters::add)
                super.visitDeclaration(declaration)
            }
        })
        return anyCaptured
    }

    private inline fun tryToApplyFunction(
        call: IrCall,
        applyFunction: (SequenceData, IrRichFunctionReference) -> SequenceData
    ) {
        val receiver = call.arguments.getOrNull(0) ?: return
        val fnArg = call.arguments.getOrNull(1) ?: return
        val fnRef = fnArg as? IrRichFunctionReference ?: return
        if (hasLambdaCapturedVariables(fnRef.invokeFunction)) return

        val receiverData = receiver.sequenceDataOfExpression ?: return
        call.sequenceDataOfExpression = applyFunction(receiverData, fnRef)
        return
    }

    private fun matchWithGenerateSequence(expression: IrCall): Pair<GenerateInitialValue, IrRichFunctionReference>? {
        return when (expression.arguments.size) {
            1 -> {
                // generateSequence(func)
                val func = expression.arguments[0] as? IrRichFunctionReference ?: return null
                GenerateInitialValue.NoInitialValue() to func
            }
            2 -> {
                val initialValueOrFunction = expression.arguments[0]
                val func = expression.arguments[1] as? IrRichFunctionReference ?: return null
                when (initialValueOrFunction) {
                    is IrRichFunctionReference -> {
                        // generateSequence(initialFunc, func)
                        GenerateInitialValue.InitialFunction(initialValueOrFunction) to func
                    }
                    else -> {
                        // generateSequence(initialValue, func)
                        if (initialValueOrFunction !is IrExpression) return null
                        GenerateInitialValue.InitialValue(initialValueOrFunction) to func
                    }
                }
            }
            else -> null
        }
    }

    override fun visitCall(expression: IrCall): IrExpression {
        super.visitCall(expression)
        // now the children have assigned appropriate sequence data
        if (!isElementSequence(expression)) return expression
        val functionName = expression.symbol.owner.name.asString()
        when (functionName) {
            "map" -> {
                tryToApplyFunction(expression) { sequenceData, reference -> sequenceData.applyMap(reference) }
            }
            "filter" -> {
                tryToApplyFunction(expression) { sequenceData, reference -> sequenceData.applyFilter(reference) }
            }
            "generateSequence" -> {
                val (initialValue, func) = matchWithGenerateSequence(expression) ?: return expression
                expression.sequenceDataOfExpression = SequenceData(sequenceSource = SequenceSource.GenerateSequence(initialValue, func))
            }
            "sequenceOf" -> {
                // store the sequence of arguments inside the sequence source
                if (expression.arguments.size != 1) return expression
                val argument = expression.arguments.getOrNull(0) ?: return expression
                val sequenceOfArguments: List<IrExpression>
                if (argument is IrVararg) {
                    // sequenceOf(vararg arguments)
                    if (argument.elements.any { it is IrSpreadElement }) return expression // skip lowering sequenceOf with spread arguments
                    sequenceOfArguments = argument.elements.map { it as IrExpression }
                } else {
                    // sequenceOf(argument)
                    sequenceOfArguments = listOf(argument)
                }
                expression.sequenceDataOfExpression = SequenceData(
                    sequenceSource = SequenceSource.SequenceOf(sequenceOfArguments)
                )
            }
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

    private fun matchWithSequenceIteration(block: IrBlock): IrExpression? {
        if (block.origin != IrStatementOrigin.FOR_LOOP) return null

        // extract loop iterator variable and loop body from IrBlock
        if (block.statements.size != 2) return null
        val iteratorDeclaration = block.statements[0] as? IrVariable ?: return null
        if (block.statements[1] !is IrWhileLoop) return null

        val possiblySequenceInitializer = iteratorDeclaration.initializer as? IrCall ?: return null
        val iterable = possiblySequenceInitializer.arguments.firstOrNull() ?: return null
        if (!isElementSequence(iterable)) return null
        return iterable
    }

    private fun getInnerMostReceiverSequenceData(expression: IrExpression): SequenceData? {
        when (expression) {
            is IrCall -> {
                val callee = expression.symbol.owner
                if (callee.name.asString() == "sequenceOf") return expression.sequenceDataOfExpression
                val receiver = expression.arguments.getOrNull(0) ?: return null
                return getInnerMostReceiverSequenceData(receiver)
            }
            is IrGetValue -> return expression.symbol.owner.sequenceDataOfVariable
            else -> return null
        }
    }

    private fun lookupForLoopVariable(loopBody: IrBlock): Pair<Int, IrVariable>? {
        val (index, statement) = loopBody.statements.filterIsInstance<IrVariable>().withIndex()
            .singleOrNull { (_, v) -> v.origin == IrDeclarationOrigin.FOR_LOOP_VARIABLE } ?: return null
        return index to statement
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
        iterable: IrExpression,
        loop: IrWhileLoop,
        sequenceSource: SequenceSource.SequenceOf,
    ): IrExpression? {
        return builder.irBlock {
            val sequenceData = iterable.sequenceDataOfExpression ?: return null
            sequenceSource.elements.forEach { element ->
                val newElement = element.deepCopyWithSymbols()
                val parent = getParentFromBuilder(builder)
                newElement.patchDeclarationParents(parent)
                val loopBodyCopy = loop.deepCopyWithSymbols().body as? IrBlock ?: return null
                loopBodyCopy.patchDeclarationParents(parent)

                val (nextStatementIndex, _) = lookupForLoopVariable(loopBodyCopy) ?: return null
                // filterReplacement is a block of statements, which depend on an initial value, and produce a result in some other value
                // we produce a block for each element that has 'element' as the initial value and produces some result,
                // then we insert the old loop body with iterator.next() replaced with mapReplacement(result)
                +sequenceData.filterReplacement(
                    builder,
                    newElement,
                ) { result ->
                    (loopBodyCopy.statements[nextStatementIndex] as IrVariable).initializer = sequenceData.mapReplacement(builder, result)
                    loopBodyCopy
                }
            }
        }
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
     * cannot be lowered, because there is no way of applying { it * 2 } before { it + 1 }.
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
        iterable: IrExpression,
        sequenceSource: SequenceSource.Variable,
        innerMostExpressionData: SequenceData,
        loopBlock: IrBlock,
    ) {
        // if iterable is not IrGetValue, we do not lower, we cannot substitute sequenceSource for iterable
        if (iterable !is IrGetValue) {
            return
        }
        val iteratorDeclaration = loopBlock.statements[0] as IrVariable
        val loop = loopBlock.statements[1] as IrWhileLoop
        val iteratorRHS = iteratorDeclaration.initializer as? IrCall ?: return
        val loopBody = loop.body as? IrBlock ?: return
        val (nextStatementIndex, nextStatement) = lookupForLoopVariable(loopBody) ?: return
        val nextExpression = nextStatement.initializer ?: return
        // we replace iterable with the sequence source, then we replace next() calls
        // with our constructed value
        iteratorRHS.arguments[0] = builder.irGet(sequenceSource.variable.owner)
        loop.body = innerMostExpressionData.filterReplacement(builder, nextExpression) { value ->
            (loopBody.statements[nextStatementIndex] as IrVariable).initializer = innerMostExpressionData.mapReplacement(builder, value)
            loopBody
        }
    }

    fun createInductionVariable(
        initialExpression: IrExpression,
        nextExpression: (IrVariable) -> IrExpression,
        builder: IrBuilderWithScope,
    ): Triple<IrVariable, IrExpression, IrExpression> {
        val sequenceElement = builder.scope.createTemporaryVariable(
            initialExpression,
            isMutable = true,
            irType = initialExpression.type.makeNullable(),
        )
        val condition = builder.irNotEquals(builder.irGet(sequenceElement), builder.irNull())
        val next = nextExpression(sequenceElement)
        return Triple(sequenceElement, condition, next)
    }

    private fun lowerFromGenerateSequence(
        builder: IrBuilderWithScope,
        iterable: IrExpression,
        sequenceSource: SequenceSource.GenerateSequence,
        oldLoopBlock: IrBlock,
    ): IrExpression? {
        val newBlock = oldLoopBlock.deepCopyWithSymbols()
        val parent = getParentFromBuilder(builder)
        newBlock.patchDeclarationParents(parent)
        val sequenceData = iterable.sequenceDataOfExpression ?: return null
        val generatingFunction = sequenceSource.function
        val nextFromCurrent: (IrVariable) -> IrExpression = {
            builder.callRichFunctionReference(generatingFunction, builder.irNotNull(builder.irGet(it)))
        }
        val (inductionVariableDeclaration, newCondition, newNext) = when (val initialValue = sequenceSource.initialValue) {
            is GenerateInitialValue.InitialValue -> {
                createInductionVariable(
                    initialValue.expression.deepCopyWithSymbols(),
                    nextFromCurrent,
                    builder
                )
            }
            is GenerateInitialValue.InitialFunction -> {
                createInductionVariable(
                    builder.callRichFunctionReference(initialValue.function),
                    nextFromCurrent,
                    builder
                )
            }
            is GenerateInitialValue.NoInitialValue -> {
                createInductionVariable(
                    builder.callRichFunctionReference(generatingFunction),
                    { builder.callRichFunctionReference(generatingFunction) },
                    builder
                )
            }
        }
        val oldLoop = newBlock.statements[1] as? IrWhileLoop ?: return null
        val newBody = oldLoop.body as? IrBlock ?: return null
        val (nextStatementIndex, nextStatement) = lookupForLoopVariable(newBody) ?: return null
        val newLoop = builder.irWhile().apply {
            condition = newCondition
            body = builder.irBlock {
                val currentSequenceElement = irTemporary(irNotNull(irGet(inductionVariableDeclaration)))
                +irSet(inductionVariableDeclaration, newNext)
                +sequenceData.filterReplacement(this, irGet(currentSequenceElement)) { value ->
                    nextStatement.initializer = sequenceData.mapReplacement(this, value)
                    newBody.statements[nextStatementIndex] = nextStatement
                    newBody
                }
            }
        }

        newBlock.statements[0] = inductionVariableDeclaration
        newBlock.statements[1] = newLoop
        newBlock.origin = null
        return newBlock
    }

    // This is where the actual transformation takes place
    override fun visitBlock(expression: IrBlock): IrExpression {
        val result = super.visitBlock(expression)
        if (result !is IrBlock) return result

        val iterable = matchWithSequenceIteration(result) ?: return result
        val innerMostReceiverSequenceData = getInnerMostReceiverSequenceData(iterable) ?: return result
        val builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset)
        val sequenceSource = innerMostReceiverSequenceData.sequenceSource ?: return result

        when (sequenceSource) {
            is SequenceSource.SequenceOf -> {
                return lowerFromSequenceOf(builder, iterable, result.statements[1] as IrWhileLoop, sequenceSource) ?: result
            }
            is SequenceSource.Variable -> {
                lowerFromUnknownVariable(builder, iterable, sequenceSource, innerMostReceiverSequenceData, result)
                return result
            }
            is SequenceSource.GenerateSequence -> {
                return lowerFromGenerateSequence(builder, iterable, sequenceSource, result) ?: result
            }
        }
    }
}