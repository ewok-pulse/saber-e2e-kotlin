// RUN_PIPELINE_TILL: BACKEND
// IDE_MODE

// FILE: test.kt
package test

sealed class A {
    class X : A()
    class Y : A()
}

// FILE: main.kt
import test.A
import test.A.X
import test.A.Y

fun foo(a: A) {
    if (a is X) {
        "".hashCode()
    }

    if (a !is Y) {
        "".hashCode()
    }

    val x = a as X
    val y = a <!CAST_NEVER_SUCCEEDS!>as?<!> Y
}

/* GENERATED_FIR_TAGS: asExpression, classDeclaration, functionDeclaration, ifExpression, isExpression, localProperty,
nestedClass, nullableType, propertyDeclaration, sealed, smartcast, stringLiteral */
