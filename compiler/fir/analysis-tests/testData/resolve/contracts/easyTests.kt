// RUN_PIPELINE_TILL: BACKEND
// WITH_STDLIB

fun barRegular(f: () -> Unit) {}

private fun testStable() = barRegular {
    var another = "hello"

    barRegular {
        println(another)
    }

    var r = "bla"

    barRegular {
        <!CV_DIAGNOSTIC!>r<!> = "3"
    }

    barRegular {
        <!CV_DIAGNOSTIC!>r<!> = "4"
    }
}

fun easyTest(ind : Int) {
    var b = 2
    if (ind < 2) {
        b = 4
    }
    barRegular{
        println(b)
    }
}

private fun assertFormat(raw: String, vararg args: Any) {
    var e: Exception? = null
    val actual = try {
    } catch (ex: Exception) {
        e = ex
        ex.message
    }

    barRegular{
        if (e != null) throw e
    }
}

fun consume(startIndex : Int) {
    var digitsInRow = 0
    while (startIndex < 10) {
        ++digitsInRow
    }
    if (digitsInRow < 0)
        barRegular {
            "Only found $digitsInRow digits in a row"
        }
    for (i in 0..10) {
        val length = digitsInRow
    }
}

fun testDirectReassignment() {
    var unstable = ""
    barRegular {
        println(<!CV_DIAGNOSTIC!>unstable<!>)
    }
    unstable = "hello"
}

fun testReassignmentInCurrentLambda() {
    var stable = ""
    barRegular {
        stable = "hello"
        println(stable)
        stable = "2"
    }
    println(stable)
}

private fun testReassignmentInLocalLamda(a: Int, v: Any) = run {
    var oldVersionedValue: String? = null
    var added : Boolean
    val aevtPrime = barRegular {
        when {
            oldVersionedValue == null -> run { added = true }
            oldVersionedValue == "hi" -> run { added = false }
            else -> run { added = true }
        }
    }
}


/* GENERATED_FIR_TAGS: assignment, functionDeclaration, functionalType, lambdaLiteral, localProperty,
propertyDeclaration, stringLiteral */
