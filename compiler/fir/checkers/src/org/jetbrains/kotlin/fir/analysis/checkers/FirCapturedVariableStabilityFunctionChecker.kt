/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.cfa.AbstractFirPropertyInitializationChecker
import org.jetbrains.kotlin.fir.analysis.cfa.nearestNonInPlaceGraph
import org.jetbrains.kotlin.fir.analysis.cfa.util.*
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeDynamicType

/**
 * Checks captured variables inside non-in-place lambdas and determines their stability
 * using [FindCapturedWrites, FindVisibleWrites].
 */
object FirCapturedVariableStabilityFunctionChecker : AbstractFirPropertyInitializationChecker(MppCheckerKind.Common) {
    context(reporter: DiagnosticReporter, context: CheckerContext)
    override fun analyze(data: VariableInitializationInfoData) {
        val trackedProperties = data.properties
            .map { it as FirPropertySymbol }
            .filterTo(linkedSetOf()) { symbol ->
                symbol.isLocal &&
                        !symbol.isVal &&
                        symbol.resolvedReturnType !is ConeDynamicType
            }
        if (trackedProperties.isEmpty()) return

        val capturedWrites = data.graph.traverseToFixedPoint(FindCapturedWrites(trackedProperties))
        val visibleWrites =
            data.graph.traverseToFixedPoint(FindVisibleWrites(capturedWrites, trackedProperties, excludeLocalInPlaceWrites = true))

        val escapingProperties = mutableSetOf<Pair<FirPropertySymbol, FirQualifiedAccessExpression>>()
        data.graph.traverse(
            CapturedVariableVisitor(
                trackedProperties,
                visibleWrites,
                escapingProperties,
            )
        )

        for ((symbol, expression) in escapingProperties) {
            reporter.reportOn(
                expression.source,
                FirErrors.CV_DIAGNOSTIC,
                symbol.name.toString(),
                context
            )
        }
    }
}

private class CapturedVariableVisitor(
    private val trackedProperties: Set<FirPropertySymbol>,
    private val visibleWrites: Map<CFGNode<*>, PathAwareControlFlowInfo<PropertyAccessType, VariableWriteData>>,
    private val escapingProperties: MutableSet<Pair<FirPropertySymbol, FirQualifiedAccessExpression>>,
) : ControlFlowGraphVisitorVoid() {
    override fun visitNode(node: CFGNode<*>) {}

    override fun visitQualifiedAccessNode(node: QualifiedAccessNode) {
        reportIfNeeded(node, node.fir)
    }

    override fun visitFunctionCallEnterNode(node: FunctionCallEnterNode) {
        // TODO(KT-76534): remove when implicit invoke is handled in CFG correctly.
        val call = node.fir as? FirImplicitInvokeCall ?: return
        val receiver = (call.dispatchReceiver ?: call.explicitReceiver) as? FirQualifiedAccessExpression ?: return
        val receiverExitNode =
            (node.firstPreviousNode as? FunctionCallArgumentsExitNode)?.explicitReceiverExitNode ?: return

        reportIfNeeded(receiverExitNode, receiver)
    }

    override fun visitVariableAssignmentNode(node: VariableAssignmentNode) {
        reportIfNeeded(node, node.fir.lValue as? FirQualifiedAccessExpression ?: return)
    }

    private fun reportIfNeeded(
        accessNode: CFGNode<*>,
        expression: FirQualifiedAccessExpression,
    ) {
        val currentGraph = accessNode.owner.nearestNonInPlaceGraph()

        if (currentGraph.declaration !is FirAnonymousFunction) return
        val symbol = expression.calleeReference.toResolvedVariableSymbol() as? FirPropertySymbol ?: return
        if (symbol !in trackedProperties) return
        val hasCapturedWrites = visibleWrites[accessNode]
            ?.values
            ?.any { controlFlowInfo ->
                controlFlowInfo.values.any { writeData ->
                    writeData[symbol]?.isNotEmpty() == true
                }
            } == true

        if (hasCapturedWrites) {
            escapingProperties.add(symbol to expression)
        }
    }
}