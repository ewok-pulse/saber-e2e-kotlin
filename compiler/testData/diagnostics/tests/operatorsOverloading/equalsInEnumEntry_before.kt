// RUN_PIPELINE_TILL: BACKEND
// LANGUAGE: -ForbidOperatorEqualsInEnumEntriesAndAnonymousObjects

enum class E {
    X {
        operator fun equals(other: E): Boolean = true
    },
    Y {
        operator fun equals(a: Int, b: Int): Unit = Unit
    },
    Z {
        context(e: E)
        operator fun equals(): Any? = null
    }
}

/* GENERATED_FIR_TAGS: enumDeclaration, enumEntry */
