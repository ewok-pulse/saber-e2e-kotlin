// RUN_PIPELINE_TILL: BACKEND
// WITH_STDLIB

fun barRegular(f: () -> Unit) {}
fun barIndex(f: (Int) -> Unit) {}

private fun testNotCaptured() {
    barRegular {
        var another = "hello"
        println(another)
    }
}

private fun testRepeated() {
    var repeat = true
    var attempts = 0
    while (repeat) {
        barIndex { index ->
            try {
                println(attempts)
                repeat = false
            } catch (e: Throwable) {
                println(e)
            }
        }
    }
}

/* GENERATED_FIR_TAGS: assignment, functionDeclaration, functionalType, integerLiteral, lambdaLiteral, localProperty,
propertyDeclaration, stringLiteral, tryExpression, whileLoop */
