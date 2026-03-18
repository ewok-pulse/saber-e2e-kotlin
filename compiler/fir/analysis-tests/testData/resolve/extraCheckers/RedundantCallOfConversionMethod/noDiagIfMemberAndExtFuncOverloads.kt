// RUN_PIPELINE_TILL: BACKEND
// ISSUE: KT-80060

class C {
    fun test(x: Long) = "call from member"
}

fun C.test(x: Int) = "call from extension"

fun test() {
    val c = C()
    c.test(1.toInt()) // The `toInt` call forces resolving to the extension, the REDUNDANT_CALL_OF_CONVERSION_METHOD shouldn't be reported here
    c.test((2 + 3).toInt()) // Resolving to the extension, no REDUNDANT_CALL_OF_CONVERSION_METHOD
    c.test('d'.<!DEPRECATION!>toInt<!>()) // Dispatch receiver is not `Int` -> always non-redundant, resolving to the member
    c.test(4) // The integer literal type is treated as Long here and it forces the call to be resolved to the member
    c.test(5L) // Long literal, no ambiguity, resolving to the member
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, integerLiteral, localProperty,
propertyDeclaration, stringLiteral */
