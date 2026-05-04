// RUN_PIPELINE_TILL: BACKEND
// IDE_MODE

// FILE: test.kt
package test

sealed class A {
    object X : A()
    object Y : A()
}

// FILE: main.kt
import test.A
import test.A.*

fun expectsA(x: A) {}

fun main() {
    expectsA(X)
    val a: A = Y
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, localProperty, nestedClass, objectDeclaration,
propertyDeclaration, sealed */
