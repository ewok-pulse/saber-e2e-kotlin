// RUN_PIPELINE_TILL: BACKEND
// WITH_STDLIB

fun barRegular(f: () -> Unit) {}

fun testDirectReassignment() {
    var unstable = ""
    barRegular {
        println(<!CV_DIAGNOSTIC!>unstable<!>)
    }
    unstable = "hello"
}

class MutablePerson(var name: String = "NoName")
fun baz(s: String) {}

fun testConditionalObjectReassignment(x : String) {
    var person = MutablePerson("Alice")

    barRegular {
        baz(<!CV_DIAGNOSTIC!>person<!>.name)
    }
    if (person.name != x) {
        person = MutablePerson()
    }
}

class MutableObject(var mutableField: String = "initial")

fun testNullableVariableReassignment() {
    var localObjVal : MutableObject? = MutableObject()
    barRegular {
        println(<!CV_DIAGNOSTIC!>localObjVal<!>?.mutableField)
    }
    localObjVal = null
}

private fun testReassignmentAfterNestedCapture(){
    var first = true
    barRegular {
        barRegular {
            if (<!CV_DIAGNOSTIC!>first<!>) {
                first = false
            }
        }
    }
    first = true
}

class DeepObject { var theProblematicVar: String = "Hello" }

class MiddleObject { val next: DeepObject? = DeepObject() }

class RootObject { val next: MiddleObject? = MiddleObject() }

fun testNullableObjectReassignment() {
    var root : RootObject? = RootObject()
    barRegular {
        baz(<!CV_DIAGNOSTIC!>root<!>?.next!!.next!!.theProblematicVar)
    }
    root = null
}

fun testObjectReferenceReassignment() {
    var root2 = RootObject()
    val root3 = RootObject()
    barRegular {
        baz(<!CV_DIAGNOSTIC!>root2<!>.next!!.next!!.theProblematicVar)
    }
    root2 = root3
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, equalityExpression, functionDeclaration, ifExpression,
lambdaLiteral, localProperty, primaryConstructor, propertyDeclaration, stringLiteral */
