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
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irBreak
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irComposite
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.builders.irWhile
import org.jetbrains.kotlin.ir.declarations.IrClass
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
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrBreakContinue
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name
import kotlin.collections.get

private const val ITERATOR = "iterator"
private const val HAS_NEXT = "hasNext"
private const val NEXT = "next"
private const val SEQUENCE_OF = "sequenceOf"
private const val AS_SEQUENCE = "asSequence"
private const val GENERATE_SEQUENCE = "generateSequence"
private const val MAP = "map"
private const val FILTER = "filter"
private const val TAKE = "take"
private const val FOR_EACH = "forEach"

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
        val reuseMarker = ReusedSequenceMarker(context)
        irFile.acceptChildrenVoid(reuseMarker)
        val gatherer = SequenceDataGatherer(context)
        irFile.acceptChildrenVoid(gatherer)
        val transformer = SequenceFusionTransformer(context)
        irFile.transformChildrenVoid(transformer)
    }
}

private sealed class GenerateSequenceInitialValue {
    class InitialValue(val expression: IrExpression) : GenerateSequenceInitialValue()
    class InitialFunction(val function: IrRichFunctionReference) : GenerateSequenceInitialValue()
    object NoInitialValue : GenerateSequenceInitialValue()
}

// sequenceSource is what the sequence was created from, to be substituted if the loop is to be fused
private sealed class SequenceSource {
    class SequenceOf(val elements: List<IrExpression>, val type: IrType) : SequenceSource()
    class Variable(val variable: IrValueSymbol) : SequenceSource()
    class AsSequence(val iterable: IrExpression) : SequenceSource()
    class GenerateSequence(val initialValue: GenerateSequenceInitialValue, val generatingFunction: IrRichFunctionReference) :
        SequenceSource()
}

private typealias IrBuilderWithParent = Pair<IrBuilderWithScope, IrDeclarationParent>

private class SequenceData(
    val mapReplacement: MapReplacement,
    val sequenceSource: SequenceSource,
    val newLoopPrologue: LoopPrologue,
    val takeVariableDeclarations: (IrBuilderWithScope) -> MutableList<IrVariable>,
    val offsets: List<Pair<Int, Int>>,
) {
    // mapReplacement for a given sequence expression stores a composition of functions applied to the base sequence via `map`
    private typealias MapReplacement = (IrBuilderWithParent, IrExpression) -> IrExpression

    fun applyMap(function: IrRichFunctionReference, offsets: Pair<Int, Int>): SequenceData {
        val newMapReplacement = { (builder, parent): IrBuilderWithParent, argument: IrExpression ->
            builder.callRichFunctionReference(function, parent, argument)
        }

        return SequenceData(
            composeMapReplacements(this.mapReplacement, newMapReplacement),
            this.sequenceSource,
            this.newLoopPrologue,
            this.takeVariableDeclarations,
            this.offsets + offsets
        )
    }

    private fun composeMapReplacements(
        accumulator: MapReplacement,
        newFunction: MapReplacement,
    ): MapReplacement = { builder, argument -> newFunction(builder, accumulator(builder, argument)) }
    /**
     * Filter replacement is constructed like this:
     * ``Block`
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
    private typealias LoopPrologue = (IrBuilderWithParent, IrLoop, IrExpression, (IrExpression) -> IrExpression) -> IrExpression

    private fun createNewFilterSegment(
        filterFunction: IrRichFunctionReference,
    ): LoopPrologue = { (builder, parent), _, valueGenerator, expressionDependentOnValue ->
        builder.irBlock {
            val newValue = irTemporary(mapReplacement(builder to parent, valueGenerator))
            val willStay = irTemporary(callRichFunctionReference(filterFunction, parent, irGet(newValue)))
            +irIfThen(context.irBuiltIns.unitType, irGet(willStay), expressionDependentOnValue(irGet(newValue)))
        }
    }

    private fun composeFilterReplacements(accumulator: LoopPrologue, nextSegment: LoopPrologue): LoopPrologue =
        { builder, loop, valueGenerator, expressionDependentOnValue ->
            accumulator(builder, loop, valueGenerator) { nextValue -> nextSegment(builder, loop, nextValue, expressionDependentOnValue) }
        }

    fun applyFilter(
        filterFunction: IrRichFunctionReference,
        offsets: Pair<Int, Int>,
    ): SequenceData {
        val newLoopPrologue = composeFilterReplacements(this@SequenceData.newLoopPrologue, createNewFilterSegment(filterFunction))
        return SequenceData(
            defaultMapReplacement,
            sequenceSource,
            newLoopPrologue,
            takeVariableDeclarations,
            this.offsets + offsets
        )
    }

    /**
     * Take replacement has two parts: the take variable declaration and the actual place where we check if we have taken enough
     * ```
     * seq.someMapsAndFilters.take(n)
     * for (i in seq) {body(i)}
     * ```
     * becomes
     * ```
     * val takeCount = 0
     * while(...) {
     *     filterReplacement
     *     mapReplacement
     *     takeCount++
     *     if (takeCount > n) break
     *     body(mapReplacementValue)
     * }
     * ```
     * filterReplacement + mapReplacement is inside the loop already
     */

    private fun createNewTakeVariable(builder: IrBuilderWithScope): IrVariable {
        return builder.scope.createTemporaryVariable(
            builder.irInt(0),
            isMutable = true,
            nameHint = "takeVar"
        )
    }

    private fun createTakeReplacement(
        builderWithParent: IrBuilderWithParent,
        valueGenerator: IrExpression,
        expressionDependentOnValue: (IrExpression) -> IrExpression,
        getOrCreateTakeVariable: (IrBuilderWithScope) -> IrVariable,
        loop: IrLoop,
        takeArgument: IrExpression,
    ): IrExpression {
        val (builder, parent) = builderWithParent
        val takeVariable = getOrCreateTakeVariable(builder)
        val classifier = takeVariable.type.classifierOrNull
        val lessThanSymbol = builder.context.irBuiltIns.lessFunByOperandType[classifier]
            ?: error("No lessThan function found for type ${takeVariable.type}")
        return builder.irBlock {
            val tmp = irTemporary(mapReplacement(builder to parent, valueGenerator))

            // takeVariable++
            +irSet(takeVariable, irCall(context.irBuiltIns.intPlusSymbol).apply {
                dispatchReceiver = irGet(takeVariable)
                arguments[1] = irInt(1)
            })

            // if (takeVariable > takeArgument) break
            +irIfThen(
                builder.context.irBuiltIns.unitType,
                irCall(lessThanSymbol).apply {
                    arguments[0] = takeArgument.deepCopyWithSymbols(parent)
                    arguments[1] = irGet(takeVariable)
                },
                irBreak(loop)
            )
            +expressionDependentOnValue(irGet(tmp))
        }
    }

    fun applyTake(
        takeArgument: IrExpression,
        offsets: Pair<Int, Int>,
    ): SequenceData {
        val takeVariableCell = object {
            var value: IrVariable? = null
        }
        val getOrCreateTakeVariable = { builder: IrBuilderWithScope ->
            takeVariableCell.value ?: createNewTakeVariable(builder).also {
                takeVariableCell.value = it
            }
        }

        val newFilterReplacement = composeFilterReplacements(
            this.newLoopPrologue
        ) { builderWithParent, loop, valueGenerator, expressionDependentOnValue ->
            createTakeReplacement(
                builderWithParent,
                valueGenerator,
                expressionDependentOnValue,
                getOrCreateTakeVariable,
                loop,
                takeArgument,
            )
        }

        val newTakeVariableDeclarations = { builder: IrBuilderWithScope ->
            val takeVariable = getOrCreateTakeVariable(builder)
            val declarations = takeVariableDeclarations(builder)
            declarations.add(takeVariable)
            declarations
        }

        return SequenceData(
            defaultMapReplacement,
            this.sequenceSource,
            newFilterReplacement,
            newTakeVariableDeclarations,
            this.offsets + offsets
        )
    }

    companion object {
        val defaultMapReplacement: MapReplacement = { _, value -> value }
        val defaultLoopPrologue: LoopPrologue = { _, _, value, expressionExpectingValue -> expressionExpectingValue(value) }
        val defaultTakeVariableDeclarations: (IrBuilderWithScope) -> MutableList<IrVariable> =
            { _ -> mutableListOf() }
    }
}

private fun IrBuilderWithScope.callRichFunctionReference(
    ref: IrRichFunctionReference,
    parent: IrDeclarationParent,
    vararg args: IrExpression,
): IrExpression {
    val freshRef = ref.deepCopyWithSymbols(parent)
    val functionType = freshRef.type as? IrSimpleType
    val returnType = functionType?.arguments?.lastOrNull()?.typeOrNull ?: freshRef.overriddenFunctionSymbol.owner.returnType
    return irCall(freshRef.overriddenFunctionSymbol, returnType).apply {
        dispatchReceiver = freshRef
        args.forEachIndexed { index, arg -> arguments[index + 1] = arg }
    }
}

private fun IrBuilderWithScope.irAsNotNull(value: IrExpression): IrExpression {
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

// this is stored for expressions, intended to be passed either to value declarations or to for loops iterated over the expression result
private var IrExpression.sequenceDataOfExpression: SequenceData? by irAttribute(true)

// this is stored to be one of the future sources of sequence data of expressions
private var IrValueDeclaration.sequenceDataOfVariable: SequenceData? by irAttribute(true)
// In general, sequence data is gathered from `sequenceOf` or existing sequence variables, modified `by` map calls,
// and consumed by for loops and variable declarations

private var IrValueDeclaration.usageCounter: Int? by irAttribute(false)

private fun isElementSequence(context: JvmBackendContext, element: IrElement): Boolean {
    val sequenceSymbol = context.symbols.sequence ?: return false
    val type = when (element) {
        is IrExpression -> element.type
        is IrVariable -> element.type
        else -> return false
    }
    return type.isSubtypeOfClass(sequenceSymbol)
}

private fun getInnerMostReceiver(expression: IrExpression): IrExpression? {
    when (expression) {
        is IrCall -> {
            val receiver = expression.arguments.getOrNull(0) ?: return null
            return getInnerMostReceiver(receiver)
        }
        is IrGetValue -> return expression
        else -> return null
    }
}

private class SequenceDataGatherer(val context: JvmBackendContext) : IrVisitorVoid() {
    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitVariable(declaration: IrVariable) {
        super.visitVariable(declaration)
        if (declaration.isVar) return
        if (!isElementSequence(context, declaration)) return
        val expressionSequenceData = declaration.initializer?.sequenceDataOfExpression
        declaration.symbol.owner.sequenceDataOfVariable = if (expressionSequenceData?.sequenceSource is SequenceSource.GenerateSequence &&
            expressionSequenceData.sequenceSource.initialValue is GenerateSequenceInitialValue.NoInitialValue &&
            (declaration.usageCounter ?: 0) > 1
        ) {
            SequenceData(
                SequenceData.defaultMapReplacement,
                SequenceSource.Variable(declaration.symbol),
                SequenceData.defaultLoopPrologue,
                SequenceData.defaultTakeVariableDeclarations,
                emptyList(),
            )
        } else {
            expressionSequenceData?.let {
                SequenceData(
                    it.mapReplacement,
                    it.sequenceSource,
                    it.newLoopPrologue,
                    it.takeVariableDeclarations,
                    emptyList(),
                )
            }
        }
    }

    // assigns sequence data of the variable to the corresponding expression
    override fun visitGetValue(expression: IrGetValue) {
        super.visitGetValue(expression)
        // now the children have assigned appropriate sequence data
        if (!isElementSequence(context, expression)) return
        expression.sequenceDataOfExpression = expression.symbol.owner.sequenceDataOfVariable?.let {
            SequenceData(
                it.mapReplacement,
                it.sequenceSource,
                it.newLoopPrologue,
                it.takeVariableDeclarations,
                listOf(expression.startOffset to expression.endOffset)
            )
        } ?: SequenceData(
            SequenceData.defaultMapReplacement,
            SequenceSource.Variable(expression.symbol),
            SequenceData.defaultLoopPrologue,
            SequenceData.defaultTakeVariableDeclarations,
            listOf(expression.startOffset to expression.endOffset)
        )
    }

    private fun IrExpression.isSafeToMove(): Boolean {
        var safe = true
        this.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (safe) element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                if (!expression.isPrimitiveIntrinsic()) {
                    safe = false
                }
                super.visitCall(expression)
            }

            override fun visitSetValue(expression: IrSetValue) {
                safe = false
                super.visitSetValue(expression)
            }

            override fun visitSetField(expression: IrSetField) {
                safe = false
                super.visitSetField(expression)
            }

            override fun visitGetValue(expression: IrGetValue) {
                val owner = expression.symbol.owner
                if (owner is IrVariable && owner.isVar) safe = false
            }

            override fun visitConst(expression: IrConst) {}
        })
        return safe
    }

    private fun IrCall.isPrimitiveIntrinsic(): Boolean {
        val owner = symbol.owner

        val parentClass = owner.parent as? IrClass ?: return false
        return parentClass.defaultType.isPrimitiveType() || parentClass.defaultType.isString()
    }

    private fun isSafeToLower(reference: IrRichFunctionReference): Boolean {
        if (reference.boundValues.isNotEmpty()) return false
        if (reference.invokeFunction.dispatchReceiverParameter != null) return false
        return true
    }

    private fun isSafeToLower(expression: IrExpression): Boolean {
        if (containsMutable(expression)) return false
        when (expression) {
            is IrRichFunctionReference -> {
                return isSafeToLower(expression)
            }
        }
        return true
    }

    private fun isSafeToLowerFromSequenceOf(expression: IrExpression): Boolean {
        if (containsMutable(expression)) return false
        if (!expression.isSafeToMove()) return false // skip lowering if an expression contains something that has to be evaluated only once
        return true
    }

    // checks if the applied function is safe to be lowered, then updates the sequence data if it is
    private inline fun updateSequenceDataUsingFunctionReference(
        call: IrCall,
        applyFunction: (SequenceData, IrRichFunctionReference, Pair<Int, Int>) -> SequenceData
    ) {
        val receiver = call.arguments.getOrNull(0) ?: return
        val fnArg = call.arguments.getOrNull(1) ?: return
        val fnRef = fnArg as? IrRichFunctionReference ?: return
        if (!isSafeToLower(fnRef)) return

        val receiverData = receiver.sequenceDataOfExpression ?: return
        call.sequenceDataOfExpression = applyFunction(receiverData, fnRef, call.startOffset to call.endOffset)
    }

    private inline fun updateSequenceDataUsingExpression(
        call: IrCall,
        applyFunction: (SequenceData, IrExpression, Pair<Int, Int>) -> SequenceData
    ) {
        val receiver = call.arguments.getOrNull(0) ?: return
        val argumentExpression = call.arguments.getOrNull(1) ?: return
        val receiverData = receiver.sequenceDataOfExpression ?: return
        if (containsMutable(argumentExpression)) return
        call.sequenceDataOfExpression = applyFunction(receiverData, argumentExpression, call.startOffset to call.endOffset)
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

    private fun matchWithGenerateSequence(expression: IrCall) {
        val (initialValue, func) = when (expression.arguments.size) {
            1 -> {
                // generateSequence(() -> T?)
                val func = expression.arguments.getOrNull(0) as? IrRichFunctionReference ?: return
                GenerateSequenceInitialValue.NoInitialValue to func
            }
            2 -> {
                val initialValueOrFunction = expression.arguments.getOrNull(0)
                val func = expression.arguments.getOrNull(1) as? IrRichFunctionReference ?: return
                when (initialValueOrFunction) {
                    is IrRichFunctionReference -> {
                        // generateSequence(() -> T?, (T) -> T?)
                        GenerateSequenceInitialValue.InitialFunction(initialValueOrFunction) to func
                    }
                    else -> {
                        // generateSequence(T?, (T) -> T?)
                        if (initialValueOrFunction == null) return
                        if (!isSafeToLower(initialValueOrFunction)) return
                        GenerateSequenceInitialValue.InitialValue(initialValueOrFunction) to func
                    }
                }
            }
            else -> {
                return
            }
        }
        expression.sequenceDataOfExpression = SequenceData(
            SequenceData.defaultMapReplacement,
            SequenceSource.GenerateSequence(initialValue, func),
            SequenceData.defaultLoopPrologue,
            SequenceData.defaultTakeVariableDeclarations,
            listOf(expression.startOffset to expression.endOffset)
        )
    }

    private fun extractSequenceArgumentType(sequenceType: IrType): IrType? =
        (sequenceType as? IrSimpleType)?.arguments?.singleOrNull()?.let { return it.typeOrNull }

    private fun matchWithSequenceOf(expression: IrCall) {
        // store the sequence of arguments inside the sequence source
        if (expression.arguments.size > 1) return
        val elementType = extractSequenceArgumentType(expression.type) ?: return
        if (expression.arguments.isEmpty()) {
            expression.sequenceDataOfExpression = SequenceData(
                SequenceData.defaultMapReplacement,
                SequenceSource.SequenceOf(listOf(), elementType),
                SequenceData.defaultLoopPrologue,
                SequenceData.defaultTakeVariableDeclarations,
                listOf(expression.startOffset to expression.endOffset)
            )
            return
        }
        val argument = expression.arguments.getOrNull(0) ?: return
        val sequenceOfArguments = if (argument is IrVararg) {
            // sequenceOf(vararg arguments)
            if (argument.elements.any { it is IrSpreadElement }) return // skip lowering sequenceOf with spread arguments
            if (argument.elements.any { it !is IrExpression }) return
            if (argument.elements.any { !isSafeToLowerFromSequenceOf(it as IrExpression) }) return
            argument.elements.map { it as IrExpression }
        } else {
            // sequenceOf(argument)
            if (!isSafeToLowerFromSequenceOf(argument)) return
            listOf(argument)
        }
        expression.sequenceDataOfExpression = SequenceData(
            SequenceData.defaultMapReplacement,
            SequenceSource.SequenceOf(sequenceOfArguments, elementType),
            SequenceData.defaultLoopPrologue,
            SequenceData.defaultTakeVariableDeclarations,
            listOf(expression.startOffset to expression.endOffset)
        )
    }

    private fun matchWithAsSequence(expression: IrCall) {
        val innerMostReceiver = getInnerMostReceiver(expression) ?: return
        val receiver = expression.arguments.getOrNull(0) ?: return
        if (innerMostReceiver is IrGetValue) {
            if (!isSafeToLower(innerMostReceiver)) return
            if (!innerMostReceiver.type.isSubtypeOfClass(context.irBuiltIns.iterableClass)) return
        }
        expression.sequenceDataOfExpression = SequenceData(
            SequenceData.defaultMapReplacement,
            SequenceSource.AsSequence(receiver),
            SequenceData.defaultLoopPrologue,
            SequenceData.defaultTakeVariableDeclarations,
            listOf(expression.startOffset to expression.endOffset)
        )
    }

    override fun visitCall(expression: IrCall) {
        super.visitCall(expression)
        if (!isElementSequence(context, expression)) return
        val functionName = expression.symbol.owner.name.asString()
        when (functionName) {
            MAP -> updateSequenceDataUsingFunctionReference(expression, SequenceData::applyMap)
            FILTER -> updateSequenceDataUsingFunctionReference(expression, SequenceData::applyFilter)
            TAKE -> updateSequenceDataUsingExpression(expression, SequenceData::applyTake)
            GENERATE_SEQUENCE -> matchWithGenerateSequence(expression)
            SEQUENCE_OF -> matchWithSequenceOf(expression)
            AS_SEQUENCE -> matchWithAsSequence(expression)
        }
    }
}

private class LoopBodyTransformer(
    val builder: IrBuilderWithScope,
    val oldVariable: IrValueDeclaration,
    val newVariable: IrVariable,
) : IrElementTransformerVoidWithContext() {
    override fun visitGetValue(expression: IrGetValue): IrExpression {
        if (expression.symbol == oldVariable.symbol) {
            check(expression.type == newVariable.type)
            return builder.irGet(
                newVariable
            ).apply {
                startOffset = expression.startOffset
                endOffset = expression.endOffset
            }
        }
        return super.visitGetValue(expression)
    }
}

private class BreakContinueUpdater(
    val newLoop: IrLoop,
    val oldLoop: IrLoop
) : IrElementTransformerVoidWithContext() {
    override fun visitBreakContinue(jump: IrBreakContinue): IrExpression {
        if (jump.loop == oldLoop)
            jump.loop = newLoop
        return super.visitBreakContinue(jump)
    }
}

private class ReusedSequenceMarker(val context: JvmBackendContext) : IrVisitorVoid() {
    val sequences = mutableSetOf<IrVariable>()
    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitGetValue(expression: IrGetValue) {
        if (sequences.any { it == expression.symbol.owner }) {
            expression.symbol.owner.usageCounter = (expression.symbol.owner.usageCounter ?: 0) + 1
        }
        super.visitGetValue(expression)
    }

    override fun visitVariable(declaration: IrVariable) {
        if (declaration.initializer != null && isElementSequence(context, declaration.initializer!!)) sequences.add(declaration)
        super.visitVariable(declaration)
    }
}

private fun lookupForLoopVariable(loopBody: IrBlock): IrVariable? = loopBody.statements.filterIsInstance<IrVariable>()
    .singleOrNull { v -> v.origin == IrDeclarationOrigin.FOR_LOOP_VARIABLE }

private sealed class LoweringStrategy {
    abstract fun lowerLoop(
        builderWithParent: IrBuilderWithParent,
        loopBody: IrBlock,
        sequenceData: SequenceData,
        oldLoop: IrLoop?,
        oldLoopVariable: IrVariable,
    ): IrExpression?

    abstract fun lowerFunction(
        builderWithParent: IrBuilderWithParent,
        function: IrRichFunctionReference,
        sequenceData: SequenceData,
    ): IrExpression?

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
    protected fun addReplacementsToBody(
        builderWithParent: IrBuilderWithParent,
        bodyRewriter: (IrVariable) -> IrBlock,
        sequenceData: SequenceData,
        initialValue: IrExpression,
        newBodyOrigin: IrStatementOrigin?,
        loop: IrLoop,
        innerLoopVariableName: Name?,
    ): IrExpression {
        return sequenceData.newLoopPrologue(
            builderWithParent,
            loop,
            initialValue,
        ) { filteredValue ->
            val builder = builderWithParent.first
            val mappedValue = sequenceData.mapReplacement(builderWithParent, filteredValue)
            builder.irBlock(origin = newBodyOrigin) {
                val valueAfterReplacements = scope.createTemporaryVariable(
                    mappedValue,
                    origin = IrDeclarationOrigin.FOR_LOOP_VARIABLE,
                    nameHint = innerLoopVariableName?.asString(),
                    inventUniqueName = innerLoopVariableName == null,
                )
                +valueAfterReplacements
                +bodyRewriter(valueAfterReplacements)
            }
        }
    }

    protected fun addReplacementsToForEachCall(
        builderWithParent: IrBuilderWithParent,
        forEachFunction: IrRichFunctionReference,
        sequenceData: SequenceData,
        initialValue: IrExpression,
        loop: IrLoop,
    ): IrExpression {
        return sequenceData.newLoopPrologue(
            builderWithParent,
            loop,
            initialValue,
        ) { filteredValue ->
            val (builder, parent) = builderWithParent
            val mappedValue = sequenceData.mapReplacement(builderWithParent, filteredValue)
            builder.irBlock(origin = IrStatementOrigin.FOR_LOOP_INNER_WHILE) {
                val valueAfterReplacements = scope.createTemporaryVariable(
                    mappedValue,
                    origin = IrDeclarationOrigin.FOR_LOOP_VARIABLE,
                )
                +valueAfterReplacements
                +callRichFunctionReference(forEachFunction, parent, irGet(valueAfterReplacements))
            }
        }
    }

    protected fun updateLoopVariableInBody(
        builder: IrBuilderWithScope,
        oldLoopVariable: IrValueDeclaration,
        body: IrBlock,
        newLoop: IrLoop,
        oldLoop: IrLoop?,
    ): (IrVariable) -> IrBlock = { newInnerLoopVariable ->
        body.transformChildrenVoid(LoopBodyTransformer(builder, oldLoopVariable, newInnerLoopVariable))
        if (oldLoop != null) body.transformChildrenVoid(BreakContinueUpdater(newLoop, oldLoop))
        body
    }

    protected fun addTakeVariableDeclarations(
        oldLoop: IrContainerExpression,
        sequenceData: SequenceData,
        builder: IrBuilderWithScope
    ): IrExpression =
        builder.irBlock {
            +sequenceData.takeVariableDeclarations(builder)
            for ((startOffset, endOffset) in sequenceData.offsets) {
                +builder.irUnit().apply { this.startOffset = startOffset; this.endOffset = endOffset }
            }
            +oldLoop
        }

    protected fun IrElement.markAsSynthetic() {
        this.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.startOffset = UNDEFINED_OFFSET
                element.endOffset = UNDEFINED_OFFSET
                element.acceptChildrenVoid(this)
            }
        })
    }

    protected fun IrBuilderWithScope.createSequenceWhile(): IrWhileLoop =
        irWhile(IrStatementOrigin.FOR_LOOP_INNER_WHILE)

    /**
     * Given a loop body, iteratorDeclaration, and loopVariable definition, creates the outer shell of what is expected in a lowered for loop.
     */
    protected fun createLoweredLoop(
        iteratorDeclaration: IrVariable,
        outerLoopVariable: IrVariable,
        loopCondition: IrExpression,
        builder: IrBuilderWithScope,
        loopBody: IrExpression,
        sequenceData: SequenceData,
        newLoop: IrLoop,
    ): IrExpression {
        newLoop.body = builder.irBlock {
            +outerLoopVariable
            +loopBody
        }
        newLoop.condition = loopCondition
        val newBlock = builder.irBlock {
            +iteratorDeclaration
            +newLoop
        }
        return addTakeVariableDeclarations(newBlock, sequenceData, builder)
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
    class SequenceOfStrategy(val source: SequenceSource.SequenceOf) : LoweringStrategy() {
        override fun lowerLoop(
            builderWithParent: IrBuilderWithParent,
            loopBody: IrBlock,
            sequenceData: SequenceData,
            oldLoop: IrLoop?,
            oldLoopVariable: IrVariable,
        ): IrExpression {
            val builder = builderWithParent.first

            val (iteratorVariable, outerLoopVariable, iteratorNextStatement, loopCondition) = createIteratorReplacement(builderWithParent)
            val newLoop = builder.createSequenceWhile()
            val newBody = builder.irBlock {
                // iteratorVariable++
                +iteratorNextStatement
                val bodyRewriter = updateLoopVariableInBody(builder, oldLoopVariable, loopBody, newLoop, oldLoop)
                +addReplacementsToBody(
                    builderWithParent,
                    bodyRewriter,
                    sequenceData,
                    irGet(outerLoopVariable),
                    null,
                    newLoop,
                    oldLoopVariable.name,
                )
            }
            return createLoweredLoop(iteratorVariable, outerLoopVariable, loopCondition, builder, newBody, sequenceData, newLoop)
        }

        override fun lowerFunction(
            builderWithParent: IrBuilderWithParent,
            function: IrRichFunctionReference,
            sequenceData: SequenceData
        ): IrExpression {
            val builder = builderWithParent.first
            val (iteratorVariable, outerLoopVariable, iteratorNextStatement, loopCondition) = createIteratorReplacement(builderWithParent)
            val newLoop = builder.createSequenceWhile()
            val newBody = builder.irBlock {
                // iteratorVariable++
                +iteratorNextStatement
                +addReplacementsToForEachCall(
                    builderWithParent,
                    function,
                    sequenceData,
                    irGet(outerLoopVariable),
                    newLoop
                )
            }
            return createLoweredLoop(iteratorVariable, outerLoopVariable, loopCondition, builder, newBody, sequenceData, newLoop)
        }

        private data class IteratorReplacement(
            val iteratorVariable: IrVariable,
            val outerLoopVariable: IrVariable,
            val iteratorNextStatement: IrStatement,
            val condition: IrExpression,
        )

        private fun createIteratorReplacement(
            builderWithParent: IrBuilderWithParent,
        ): IteratorReplacement {
            val builder = builderWithParent.first
            val iteratorVariable = builder.scope.createTemporaryVariable(
                builder.irInt(0),
                isMutable = true,
                origin = IrDeclarationOrigin.FOR_LOOP_ITERATOR,
                nameHint = "sequenceOfIterator"
            )
            val loopCondition = with(builder) {
                irCall(context.irBuiltIns.lessFunByOperandType[context.irBuiltIns.intClass]!!).apply {
                    arguments[0] = irGet(iteratorVariable)
                    arguments[1] = irInt(source.elements.size)
                }
            }
            val iteratorNextStatement = builder.irSet(
                iteratorVariable,
                builder.irCall(builder.context.irBuiltIns.intPlusSymbol).apply {
                    dispatchReceiver = builder.irGet(iteratorVariable)
                    arguments[1] = builder.irInt(1)
                },
            )
            val outerLoopVariable = builder.scope.createTemporaryVariable(
                generateWhen(builderWithParent, source.elements, source.type, iteratorVariable)
            )

            return IteratorReplacement(iteratorVariable, outerLoopVariable, iteratorNextStatement, loopCondition)
        }

        private fun generateWhen(
            builderWithParent: IrBuilderWithParent,
            elements: List<IrExpression>,
            returnedType: IrType,
            takeIteratorVariable: IrVariable
        ): IrExpression {
            val (builder, parent) = builderWithParent
            with(builder) {
                val branches: MutableList<IrBranch> = elements.mapIndexed { index, element ->
                    val elementCopy = element.deepCopyWithSymbols(parent)
                    elementCopy.markAsSynthetic() // this is to avoid the debugger jumping to the line with a sequenceOf declaration on every iteration
                    irBranch(irEquals(irGet(takeIteratorVariable), irInt(index)), elementCopy)
                }.toMutableList()
                branches.add(
                    irElseBranch(
                        irCall(context.irBuiltIns.noWhenBranchMatchedExceptionSymbol)
                    )
                )
                return irWhen(returnedType, branches)
            }
        }
    }

    class GenerateSequenceStrategy(val source: SequenceSource.GenerateSequence) :
        LoweringStrategy() {
        override fun lowerLoop(
            builderWithParent: IrBuilderWithParent,
            loopBody: IrBlock,
            sequenceData: SequenceData,
            oldLoop: IrLoop?,
            oldLoopVariable: IrVariable,
        ): IrExpression {
            loopBody.statements.remove(oldLoopVariable)
            val builder = builderWithParent.first
            val newLoop = builder.createSequenceWhile()
            val bodyRewriter = updateLoopVariableInBody(builder, oldLoopVariable, loopBody, newLoop, oldLoop)
            val (iteratorDeclaration, outerLoopVariable, iteratorNextReplacement, newCondition) =
                createIteratorReplacement(builderWithParent)
            val newBody = builder.irComposite {
                +iteratorNextReplacement
                +addReplacementsToBody(
                    builderWithParent,
                    bodyRewriter,
                    sequenceData,
                    builder.irGet(outerLoopVariable),
                    IrStatementOrigin.FOR_LOOP_INNER_WHILE,
                    newLoop,
                    oldLoopVariable.name,
                )
            }
            return createLoweredLoop(
                iteratorDeclaration,
                outerLoopVariable,
                newCondition,
                builder,
                newBody,
                sequenceData,
                newLoop,
            )
        }

        override fun lowerFunction(
            builderWithParent: IrBuilderWithParent,
            function: IrRichFunctionReference,
            sequenceData: SequenceData
        ): IrExpression {
            val builder = builderWithParent.first
            val (iteratorDeclaration, outerLoopVariable, iteratorNextReplacement, newCondition) =
                createIteratorReplacement(builderWithParent)
            val newLoop = builder.createSequenceWhile()
            val newBody = builder.irComposite {
                +iteratorNextReplacement
                +addReplacementsToForEachCall(
                    builderWithParent,
                    function,
                    sequenceData,
                    builder.irGet(outerLoopVariable),
                    newLoop
                )
            }
            return createLoweredLoop(
                iteratorDeclaration,
                outerLoopVariable,
                newCondition,
                builder,
                newBody,
                sequenceData,
                newLoop,
            )
        }

        private fun createIteratorReplacement(
            builderWithParent: IrBuilderWithParent,
        ): IteratorReplacement {
            val (builder, parent) = builderWithParent
            val generatingFunction = source.generatingFunction
            val oneArgumentIteratingFunction: (IrVariable) -> IrExpression = { variable ->
                builder.callRichFunctionReference(generatingFunction, parent, builder.irAsNotNull(builder.irGet(variable)))
            }
            val zeroArgumentIteratingFunction: (IrVariable) -> IrExpression = { _ ->
                builder.callRichFunctionReference(generatingFunction, parent)
            }

            val (initialExpression, iteratingFunction: (IrVariable) -> IrExpression) = when (val initialValue = source.initialValue) {
                is GenerateSequenceInitialValue.InitialValue -> {
                    initialValue.expression.deepCopyWithSymbols(parent) to oneArgumentIteratingFunction
                }
                is GenerateSequenceInitialValue.InitialFunction -> {
                    builder.callRichFunctionReference(initialValue.function, parent) to oneArgumentIteratingFunction
                }
                is GenerateSequenceInitialValue.NoInitialValue -> {
                    builder.callRichFunctionReference(generatingFunction, parent) to zeroArgumentIteratingFunction
                }
            }
            return IteratorReplacement.create(initialExpression, iteratingFunction, builder)
        }

        private data class IteratorReplacement(
            val iteratorVariable: IrVariable,
            val outerLoopVariable: IrVariable,
            val iteratorNextStatement: IrStatement,
            val condition: IrExpression,
        ) {
            companion object {
                fun create(
                    initialExpression: IrExpression,
                    evaluateNext: (IrVariable) -> IrExpression,
                    builder: IrBuilderWithScope,
                ): IteratorReplacement = with(builder) {
                    val iteratorVariable = scope.createTemporaryVariable(
                        initialExpression,
                        isMutable = true,
                        irType = initialExpression.type.makeNullable(),
                        origin = IrDeclarationOrigin.FOR_LOOP_ITERATOR
                    )
                    val condition = irNotEquals(irGet(iteratorVariable), irNull())
                    val next = evaluateNext(iteratorVariable)
                    val outerLoopVariable =
                        builder.scope.createTemporaryVariable(
                            builder.irAsNotNull(builder.irGet(iteratorVariable)),
                            nameHint = "outerLoopVariable",
                        )
                    val iteratorNextStatement = irSet(iteratorVariable, next)
                    return IteratorReplacement(iteratorVariable, outerLoopVariable, iteratorNextStatement, condition)
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
    class UnknownVariableStrategy(val newIteratorTarget: IrExpression) :
        LoweringStrategy() {
        override fun lowerLoop(
            builderWithParent: IrBuilderWithParent,
            loopBody: IrBlock,
            sequenceData: SequenceData,
            oldLoop: IrLoop?,
            oldLoopVariable: IrVariable,
        ): IrExpression? {
            val bodyCreator = { iteratorDeclaration: IrVariable, outerLoopVariable: IrVariable, loopCondition: IrExpression ->
                val builder = builderWithParent.first
                val newLoop = builder.createSequenceWhile()
                val bodyRewriter = updateLoopVariableInBody(builder, oldLoopVariable, loopBody, newLoop, oldLoop)
                val newBody = addReplacementsToBody(
                    builderWithParent,
                    bodyRewriter,
                    sequenceData,
                    builder.irGet(outerLoopVariable),
                    IrStatementOrigin.FOR_LOOP_INNER_WHILE,
                    newLoop,
                    oldLoopVariable.name,
                )
                createLoweredLoop(
                    iteratorDeclaration,
                    outerLoopVariable,
                    loopCondition,
                    builder,
                    newBody,
                    sequenceData,
                    newLoop
                )
            }
            return lowerBody(builderWithParent, bodyCreator)
        }

        override fun lowerFunction(
            builderWithParent: IrBuilderWithParent,
            function: IrRichFunctionReference,
            sequenceData: SequenceData
        ): IrExpression? {
            val bodyCreator = { iteratorDeclaration: IrVariable, outerLoopVariable: IrVariable, loopCondition: IrExpression ->
                val builder = builderWithParent.first
                val newLoop = builder.createSequenceWhile()
                val newBody =
                    addReplacementsToForEachCall(builderWithParent, function, sequenceData, builder.irGet(outerLoopVariable), newLoop)
                createLoweredLoop(
                    iteratorDeclaration,
                    outerLoopVariable,
                    loopCondition,
                    builder,
                    newBody,
                    sequenceData,
                    newLoop,
                )
            }
            return lowerBody(builderWithParent, bodyCreator)
        }

        private fun lowerBody(
            builderWithParent: IrBuilderWithParent,
            bodyCreator: (IrVariable, IrVariable, IrExpression) -> IrExpression
        ): IrExpression? {
            // if iterable is not IrGetValue, we do not lower, we cannot substitute sequenceSource for sequence.map(...) or sequence.filter(...)
            if (newIteratorTarget !is IrGetValue) {
                return null
            }
            val (iteratorDeclaration, outerLoopVariable, loopCondition) = buildIteratorCalls(
                builderWithParent,
                newIteratorTarget,
            ) ?: return null
            return bodyCreator(iteratorDeclaration, outerLoopVariable, loopCondition)
        }

        // builds .iterator(), .hasNext() and .next() called on newIteratorTarget
        private fun buildIteratorCalls(
            builderWithParent: IrBuilderWithParent,
            newIteratorTarget: IrExpression,
        ): Triple<IrVariable, IrVariable, IrExpression>? {
            with(builderWithParent.first) {
                val parent = builderWithParent.second
                val baseType = (newIteratorTarget.type as? IrSimpleType)?.arguments?.getOrNull(0)?.typeOrNull ?: return null
                val iteratorType = context.irBuiltIns.iteratorClass.typeWith(baseType)
                val iteratorCall = buildCallWithReceiver(newIteratorTarget, newIteratorTarget.type, ITERATOR, parent) ?: return null
                val iteratorDeclaration = scope.createTemporaryVariable(
                    iteratorCall,
                    isMutable = true,
                    nameHint = "replacementIterator",
                    inventUniqueName = true,
                    irType = iteratorType,
                    startOffset = newIteratorTarget.startOffset,
                    endOffset = newIteratorTarget.endOffset,
                )
                val nextCall = buildCallWithReceiver(irGet(iteratorDeclaration), iteratorType, NEXT, parent) ?: return null
                val hasNextCall = buildCallWithReceiver(irGet(iteratorDeclaration), iteratorType, HAS_NEXT, parent) ?: return null
                val nextDeclaration = scope.createTemporaryVariable(
                    nextCall,
                    isMutable = true,
                    nameHint = "outerLoopVariable",
                    inventUniqueName = true,
                    irType = baseType,
                    startOffset = newIteratorTarget.startOffset,
                    endOffset = newIteratorTarget.endOffset,
                )
                return Triple(
                    iteratorDeclaration,
                    nextDeclaration,
                    hasNextCall
                )
            }
        }

        private fun IrBuilderWithScope.buildCallWithReceiver(
            receiver: IrExpression,
            receiverType: IrType,
            functionName: String,
            parent: IrDeclarationParent,
        ): IrCall? {
            val receiverCopy = receiver.deepCopyWithSymbols(parent)
            val function = receiverType.getClass()?.functions?.singleOrNull { function ->
                function.name.asString() == functionName && function.parameters.size == 1
            } ?: return null
            return irCall(function.symbol).apply {
                arguments[0] = receiverCopy
            }
        }
    }
}

private class SequenceFusionTransformer(val context: JvmBackendContext) : IrElementTransformerVoidWithContext() {
    private data class LoopData(
        val loop: IrLoop?,
        val loopVariable: IrVariable,
        val loopBody: IrBlock,
    )

    private fun gatherLoopData(block: IrBlock, parent: IrDeclarationParent): LoopData? {
        if (block.origin != IrStatementOrigin.FOR_LOOP) return null

        // extract loop iterator variable and loop body from IrBlock
        if (block.statements.size != 2) return null
        val blockCopy = block.deepCopyWithSymbols(parent)
        val iteratorDeclaration = blockCopy.statements[0] as? IrVariable ?: return null
        val loop = blockCopy.statements[1] as? IrWhileLoop ?: return null

        val possiblySequenceInitializer = iteratorDeclaration.initializer as? IrCall ?: return null
        val iterable = possiblySequenceInitializer.arguments.firstOrNull() ?: return null
        if (!isElementSequence(context, iterable)) return null
        if (loop.body !is IrBlock) return null
        val body = loop.body as IrBlock
        val loopVariable = lookupForLoopVariable(body) ?: return null
        body.statements.remove(loopVariable)
        return LoopData(loop, loopVariable, body)
    }

    private data class FunctionData(val function: IrRichFunctionReference)

    private fun gatherFunctionData(call: IrCall, parent: IrDeclarationParent): FunctionData? {
        val function = call.arguments.getOrNull(1) as? IrRichFunctionReference ?: return null
        val copiedFunction = function.deepCopyWithSymbols(parent)
        return FunctionData(copiedFunction)
    }

    private fun SequenceSource.createStrategy(
        builder: IrBuilderWithScope,
    ): LoweringStrategy = when (this) {
        is SequenceSource.AsSequence -> LoweringStrategy.UnknownVariableStrategy(this.iterable)
        is SequenceSource.GenerateSequence -> LoweringStrategy.GenerateSequenceStrategy(this)
        is SequenceSource.SequenceOf -> LoweringStrategy.SequenceOfStrategy(this)
        is SequenceSource.Variable -> LoweringStrategy.UnknownVariableStrategy(builder.irGet(this.variable.owner))
    }

    // This is where the actual transformation takes place
    override fun visitBlock(expression: IrBlock): IrExpression {
        val result = super.visitBlock(expression)
        if (result !is IrBlock) return result

        val builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset)
        val parent = currentScope?.scope?.scopeOwnerSymbol as? IrDeclarationParent ?: currentDeclarationParent ?: return result
        val loopData = gatherLoopData(result, parent) ?: return result
        val receiver =
            ((expression.statements.getOrNull(0) as? IrVariable)?.initializer as? IrCall)?.arguments?.getOrNull(0) ?: return result
        val sequenceData = receiver.sequenceDataOfExpression ?: return result
        val strategy = sequenceData.sequenceSource.createStrategy(builder)
        return strategy.lowerLoop(builder to parent, loopData.loopBody, sequenceData, loopData.loop, loopData.loopVariable) ?: result
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val functionName = expression.symbol.owner.name.asString()
        val result = super.visitCall(expression)
        val visitedExpression = super.visitCall(expression) as? IrCall ?: return result
        if (functionName == FOR_EACH) {
            val builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset)
            val parent =
                currentScope?.scope?.scopeOwnerSymbol as? IrDeclarationParent ?: currentDeclarationParent ?: return visitedExpression
            val functionData = gatherFunctionData(visitedExpression, parent) ?: return visitedExpression
            val sequenceData = expression.arguments[0]?.sequenceDataOfExpression ?: return visitedExpression
            val strategy = sequenceData.sequenceSource.createStrategy(builder)
            return strategy.lowerFunction(builder to parent, functionData.function, sequenceData) ?: visitedExpression
        }
        return visitedExpression
    }
}
