// RUN_PIPELINE_TILL: BACKEND
// LANGUAGE: -ForbidOperatorEqualsInEnumEntriesAndAnonymousObjects

fun test() {
    val a = object {
        operator fun String.equals(): Boolean = true
    }
    val b = object {
        suspend operator fun equals() { }
    }
    val c = object {
        override operator fun equals(other: Any?): Boolean = true
    }
}

/* GENERATED_FIR_TAGS: anonymousObjectExpression, funWithExtensionReceiver, functionDeclaration, localProperty, operator,
propertyDeclaration, suspend */
