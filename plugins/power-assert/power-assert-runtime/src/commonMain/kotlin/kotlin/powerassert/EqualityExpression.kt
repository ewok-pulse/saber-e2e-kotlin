/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.powerassert

/**
 * An [Expression] for an equality operator invocation.
 * This is limited exclusively to the positive equality operaator (`==`)
 * and not the negative equality operator (`!=`),
 * the positive identity operator (`===`),
 * or the negaitve identity operator (`!==`).
 */
@ExperimentalPowerAssert
public class EqualityExpression(
    override val startOffset: Int,
    override val endOffset: Int,
    override val displayOffset: Int,
    override val value: Any?,

    /**
     * The left-hand side of the equality operator.
     */
    public val lhs: Any?,

    /**
     * The right-hand side of the equality operator.
     */
    public val rhs: Any?,
) : Expression {
    override fun copy(deltaOffset: Int): EqualityExpression {
        return EqualityExpression(
            startOffset = startOffset + deltaOffset,
            endOffset = endOffset + deltaOffset,
            displayOffset = displayOffset + deltaOffset,
            value = value,
            lhs = lhs,
            rhs = rhs,
        )
    }

    override fun toString(): String {
        return "EqualityExpression(startOffset=$startOffset, endOffset=$endOffset, displayOffset=$displayOffset, value=$value, lhs=$lhs, rhs=$rhs)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as EqualityExpression

        if (startOffset != other.startOffset) return false
        if (endOffset != other.endOffset) return false
        if (displayOffset != other.displayOffset) return false
        if (value != other.value) return false
        if (lhs != other.lhs) return false
        if (rhs != other.rhs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = startOffset
        result = 31 * result + endOffset
        result = 31 * result + displayOffset
        result = 31 * result + (value?.hashCode() ?: 0)
        result = 31 * result + (lhs?.hashCode() ?: 0)
        result = 31 * result + (rhs?.hashCode() ?: 0)
        return result
    }
}
