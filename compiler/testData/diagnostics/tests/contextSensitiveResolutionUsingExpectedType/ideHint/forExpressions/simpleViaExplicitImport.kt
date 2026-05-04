// RUN_PIPELINE_TILL: BACKEND
// IDE_MODE

// FILE: test.kt
package test

enum class A {
    X, Y
}

// FILE: main.kt
import test.A
import test.A.X
import test.A.Y

fun expectsA(x: A) {}

fun main() {
    expectsA(X)
    val a: A = Y
}

/* GENERATED_FIR_TAGS: enumDeclaration, enumEntry, functionDeclaration, localProperty, propertyDeclaration */
