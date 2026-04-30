// RUN_PIPELINE_TILL: BACKEND
// LANGUAGE: +AllowExpectValueClassesWithNoPrimaryConstructor
// WITH_STDLIB
// MODULE: m1-common
// FILE: common.kt

expect value class CommonUSize : Comparable<CommonUSize> {
    override operator fun compareTo(other: CommonUSize): Int
}

expect value class CommonSomething {
    constructor(value: Int)
}

// MODULE: m2-jvm()()(m1-common)
// FILE: jvm.kt

actual typealias CommonUSize = UInt

@JvmInline
actual value class CommonSomething(val value: Int)

/* GENERATED_FIR_TAGS: actual, classDeclaration, expect, functionDeclaration, operator, override, primaryConstructor,
propertyDeclaration, secondaryConstructor, typeAliasDeclaration, value */
