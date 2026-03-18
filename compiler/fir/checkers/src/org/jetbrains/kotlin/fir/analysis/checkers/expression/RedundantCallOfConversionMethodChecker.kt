/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.expression

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.fullyExpandedClassId
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirIntegerLiteralOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.resolve.toClassLikeSymbol
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.abbreviatedTypeOrSelf
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.ConstantValueKind
import org.jetbrains.kotlin.util.OperatorNameConventions

object RedundantCallOfConversionMethodChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private val unsafeNumberClassId = ClassId.fromString("kotlinx.cinterop/UnsafeNumber")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (expression.extensionReceiver != null) return
        val functionName = expression.calleeReference.name
        val qualifiedTypeId = targetClassMap[functionName] ?: return

        if (expression.dispatchReceiver?.isRedundant(qualifiedTypeId, context.session) == true) {
            reporter.reportOn(expression.source, FirErrors.REDUNDANT_CALL_OF_CONVERSION_METHOD)
        }
    }

    context(context: CheckerContext)
    private fun FirExpression.isRedundant(qualifiedClassId: ClassId, session: FirSession): Boolean {
        val potentialIntegerLiteralExpression = when (this) {
            is FirLiteralExpression -> this
            // The first argument defines the type of the entire integer op
            is FirIntegerLiteralOperatorCall -> this.dispatchReceiver as? FirLiteralExpression
            else -> null
        }

        if (potentialIntegerLiteralExpression?.kind?.let { it == ConstantValueKind.Int || it == ConstantValueKind.UnsignedInt } == true) {
            /**
             * The [Int.toInt] here work as a disambiguator for integer literals and integer literal operator calls.
             * I.e., it removes the literal ambiguity by narrowing the union type [Long], [Int], [Short], [Byte] down to unambiguous [Int]
             * If drop the conversion, resolver can widen the [Int] to [Long] that can affect resolution behavior.
             * The same is also relevant for [UInt.toUInt] and not for other types
             * because other types are not expressible via literals, unlike the [Int] and [UInt].
             */
            return false
        }

        val thisTypeId = if (this is FirLiteralExpression) {
            resolvedType.classId
        } else {
            when {
                resolvedType is ConeFlexibleType -> null
                resolvedType.isMarkedNullable -> null
                resolvedType.abbreviatedTypeOrSelf.toClassLikeSymbol()?.hasAnnotation(unsafeNumberClassId, session) == true -> null
                else -> resolvedType.fullyExpandedClassId(session)
            }
        }
        return thisTypeId == qualifiedClassId
    }

    private val targetClassMap: HashMap<Name, ClassId> = hashMapOf(
        OperatorNameConventions.TO_STRING to StandardClassIds.String,
        OperatorNameConventions.TO_DOUBLE to StandardClassIds.Double,
        OperatorNameConventions.TO_FLOAT to StandardClassIds.Float,
        OperatorNameConventions.TO_LONG to StandardClassIds.Long,
        OperatorNameConventions.TO_INT to StandardClassIds.Int,
        OperatorNameConventions.TO_CHAR to StandardClassIds.Char,
        OperatorNameConventions.TO_SHORT to StandardClassIds.Short,
        OperatorNameConventions.TO_BYTE to StandardClassIds.Byte,
        OperatorNameConventions.TO_ULONG to StandardClassIds.ULong,
        OperatorNameConventions.TO_UINT to StandardClassIds.UInt,
        OperatorNameConventions.TO_USHORT to StandardClassIds.UShort,
        OperatorNameConventions.TO_UBYTE to StandardClassIds.UByte
    )
}