// RUN_PIPELINE_TILL: BACKEND
// ISSUE: KT-85056

class C {
    fun test(x: Long): String {
        return "call from member"
    }
}

fun C.test(x: Int): String  {
    return "call from extension"
}

fun main() {
    C().test(1 as Int) // The `as Int` forces resolving to the extension, the USELESS_CAST shouldn't be reported here
    C().test((2 + 3) as Int) // Resolving to the extension, no USELESS_CAST here
    C().test(4) // `4` is treated as `Long`, resolving to the member
}

/* GENERATED_FIR_TAGS: asExpression, classDeclaration, funWithExtensionReceiver, functionDeclaration, integerLiteral,
stringLiteral */
