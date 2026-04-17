// RUN_PIPELINE_TILL: BACKEND
// WITH_STDLIB

fun barRegular(f: () -> Unit) {}
fun baz(s: String) {}

private fun testReassignmentAcrossMultipleLambdas() {
    var r = 1

    barRegular { r = 3 }
    barRegular { r = 4 }
}

fun testReturnAnonymousFunction(): (String) -> Unit {
    var isScheduled = false
    return { t ->
        if (!<!CV_DIAGNOSTIC!>isScheduled<!>) {
            isScheduled = true
            barRegular {
                baz(t)
                isScheduled = false
            }
        }
    }
}

class MutableObject(var mutableField: String = "initial")
fun testObjectReassignmentAcrossLambdas() {
    var mutObj = MutableObject()

    barRegular {
        mutObj = MutableObject("process")
        println(mutObj.mutableField)
    }

    barRegular {
        println(<!CV_DIAGNOSTIC!>mutObj<!>.toString())
    }
}

fun testStringReassignment() {
    var x = "bla"

    barRegular { x = "3" }
    barRegular { println(<!CV_DIAGNOSTIC!>x<!>) }
}

fun testSmartCastReassignedInAnotherLambda() {
    var flag = true
    var name = "World"
    var obj: Any = "text"
    var nullableStr: String? = null
    barRegular {
        if (<!CV_DIAGNOSTIC!>flag<!> && true) {
        print(1)
    }
        println("Hello ${<!CV_DIAGNOSTIC!>name<!>}")
        if (<!CV_DIAGNOSTIC!>obj<!> is String) {
        print(1)
    }
        val s = <!CV_DIAGNOSTIC!>obj<!> as String
        val res = <!CV_DIAGNOSTIC!>nullableStr<!> ?: "default"
    }

    barRegular {
        flag = true
        name += "a"
        obj = "text"
        nullableStr = null
        println(name)
        baz(flag.toString())
    }
}

/* GENERATED_FIR_TAGS: additiveExpression, andExpression, asExpression, assignment, classDeclaration, elvisExpression,
functionDeclaration, functionalType, ifExpression, integerLiteral, isExpression, lambdaLiteral, localProperty,
nullableType, primaryConstructor, propertyDeclaration, stringLiteral */
