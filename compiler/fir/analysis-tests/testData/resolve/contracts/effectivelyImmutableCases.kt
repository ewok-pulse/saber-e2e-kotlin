// RUN_PIPELINE_TILL: BACKEND
// WITH_STDLIB
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

fun barRegular(f: () -> Unit) {}

fun baz(s: String) {}

class MutableObject(var mutableField: String = "initial")

private fun testStable() = barRegular {
    var another = "hello"

    barRegular {
        println(another)
    }
}

private fun testUnstable() = barRegular {
    var another = "hello"

    barRegular {
        println(<!CV_DIAGNOSTIC!>another<!>)
    }

    another = "hi"
}

private fun testNotCaptured() {
    barRegular {
        var another = "hello"
        println(another)
    }
}

private fun testUnstableNotCaptured() {
    barRegular {
        var isEmpty = true
        barRegular {
            isEmpty = false
        }
        if (<!CV_DIAGNOSTIC!>isEmpty<!>) {
            println("Empty")
        }
    }
}

private fun testSimpleCapturedCase(){
    var first = true
    barRegular {
        barRegular {
            if (first) {
                first = false
            }
        }
    }
}

fun testReturnAnonymousFunction(): (String) -> Unit {
    var isScheduled = false
    return { t ->
        if (!<!CV_DIAGNOSTIC!>isScheduled<!>) {
            <!CV_DIAGNOSTIC!>isScheduled<!> = true
            barRegular {
                baz(t)
                <!CV_DIAGNOSTIC!>isScheduled<!> = false
            }
        }
    }
}

fun testEffectivelyImmutableObject(): Unit {
    var mutObj = MutableObject()
    barRegular {
        baz(mutObj.mutableField)
        println(mutObj.toString())
    }
}

fun testMutableObject(): Unit {
    var immutObj = MutableObject()
    barRegular {
        baz(immutObj.mutableField)
        println(immutObj.toString())
    }

    var mutObj = MutableObject()

    barRegular {
        mutObj = MutableObject("process")
        println(mutObj.mutableField)
    }

    barRegular {
        println(<!CV_DIAGNOSTIC!>mutObj<!>.toString())
    }

    var x = "bla"

    barRegular {
        x = "3"
    }

    barRegular {
        println(<!CV_DIAGNOSTIC!>x<!>)
    }

    var r = "bla"

    barRegular {
        <!CV_DIAGNOSTIC!>r<!> = "3"
    }

    println(r)

    barRegular {
        <!CV_DIAGNOSTIC!>r<!> = "4"
    }
}

/* GENERATED_FIR_TAGS: assignment, functionDeclaration, functionalType, lambdaLiteral, localProperty,
propertyDeclaration, stringLiteral */
