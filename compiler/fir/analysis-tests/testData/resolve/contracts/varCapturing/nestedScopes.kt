// RUN_PIPELINE_TILL: BACKEND
// WITH_STDLIB

fun barRegular(f: (Int) -> Unit) {}
fun barRegularEmpty(f: () -> Unit) {}

fun testCaptureInsideLocalFunction() {
    var r = 2

    fun localHelper() {
        barRegularEmpty {
            println(<!CV_DIAGNOSTIC!>r<!>)
        }
    }

    localHelper()
    r = 4
    localHelper()
}

fun testNestedAnonymousFunction() {
    var outer = "a"
    fun localHelper() {
        var l = 3
        barRegularEmpty {
            println(<!CV_DIAGNOSTIC!>l<!>)
            println(<!CV_DIAGNOSTIC!>outer<!>)
        }
        l = 2
    }
    outer = "b"
}

fun testNoWarningNestedConstructor() {
    var l = 2
    class Local {
        constructor(i: Int) {
            val result = i + l
            println("Captured l: $l")
        }
    }

    Local(10) // Captured l: 2
    l = 5
    Local(10) // Captured l: 5
}

fun testEscapingLambdaInsideLocalConstructor() {
    var l = 2
    var r = 2

    class Local {
        constructor(i: Int) {
            var result = i + l
            barRegularEmpty {
                println(<!CV_DIAGNOSTIC!>l<!>)
                println(<!CV_DIAGNOSTIC!>result<!>)
                println(<!CV_DIAGNOSTIC!>r<!>)
            }
            result = 3
            l = 3
        }
    }

    Local(10)

    r = 2
    l = 5
    Local(10)
}

fun testNestedInPlaceLambdaInsideEscaping() {
    var l = 2
    barRegular {
        <!CV_DIAGNOSTIC!>l<!>.let {
            println(<!CV_DIAGNOSTIC!>l<!>)
        }
    }
    l = 4
}

private fun testNestedEscapingLambdas() = barRegular {
    var another = "hello"

    barRegular {
        println(<!CV_DIAGNOSTIC!>another<!>)
    }

    another = "hi"
}

/* GENERATED_FIR_TAGS: assignment, functionDeclaration, functionalType, integerLiteral, lambdaLiteral, localProperty,
propertyDeclaration, tryExpression, whileLoop */
