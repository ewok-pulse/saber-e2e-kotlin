/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.powerassert

/**
 * Provides information about a function call, including its source and arguments.
 */
@ExperimentalPowerAssert
public class CallExplanation(
    override val offset: Int,
    override val source: String,

    /**
     * The arguments provided to the function call in parameter order.
     * Implicit, default, or arguments annotated with [PowerAssert.Ignore] will be `null`.
     */
    public val arguments: List<Argument?>,
) : Explanation {
    override val expressions: List<Expression>
        get() = arguments.sortedBy { it?.startOffset }.flatMap { it?.expressions.orEmpty() }

    /**
     * Provides information about an argument to a function call.
     */
    public class Argument(
        /**
         * The text character, within [Explanation.source], where the argument source code begins (inclusive).
         */
        public val startOffset: Int,

        /**
         * The text character, within [Explanation.source], where the argument source code ends (exclusive).
         */
        public val endOffset: Int,

        /**
         * The [Kind] of argument, be it [Kind.DISPATCH], [Kind.CONTEXT], [Kind.EXTENSION], or [Kind.VALUE].
         */
        public val kind: Kind,

        /**
         * All [Expression]s which were evaluated as part of this argument.
         * Expressions are provided in evaluation order.
         */
        public val expressions: List<Expression>,
    ) {
        public enum class Kind {
            DISPATCH,
            CONTEXT,
            EXTENSION,
            VALUE,
        }

        override fun toString(): String {
            return "Argument(startOffset=$startOffset, endOffset=$endOffset, kind=$kind, expressions=$expressions)"
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Argument

            if (startOffset != other.startOffset) return false
            if (endOffset != other.endOffset) return false
            if (kind != other.kind) return false
            if (expressions != other.expressions) return false
            return true
        }

        override fun hashCode(): Int {
            var result = startOffset
            result = 31 * result + endOffset
            result = 31 * result + kind.hashCode()
            result = 31 * result + expressions.hashCode()
            return result
        }
    }

    override fun toString(): String {
        return "CallExplanation(offset=$offset, source='$source', arguments=$arguments)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as CallExplanation

        if (offset != other.offset) return false
        if (source != other.source) return false
        if (arguments != other.arguments) return false
        return true
    }

    override fun hashCode(): Int {
        var result = offset
        result = 31 * result + source.hashCode()
        result = 31 * result + arguments.hashCode()
        return result
    }
}
