// RUN_PIPELINE_TILL: BACKEND
// WITH_STDLIB

fun barRegular(f: () -> Unit) {}

private fun testNoReassignment() {
    var another = "hello"
    barRegular { println(another) }
}

fun testEffectivelyImmutable(ind : Int) {
    var b = 2
    if (ind < 2) {
        b = 4
    }
    barRegular{
        println(b)
    }
}

fun baz(s: String) {}

class MutableObject(var mutableField: String = "initial")

fun testEffectivelyImmutableObject(): Unit {
    var mutObj = MutableObject()
    barRegular {
        baz(mutObj.mutableField)
        println(mutObj.toString())
    }
}

private fun testEffectivelyImmutableAfterTryCatch() {
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

fun testEffectivelyImmutableAfterWhileLoop(startIndex : Int) {
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

fun barRegularString(elem : String, f: () -> Unit) {}

private fun testEffectivelyImmutableArguments() {
    var clientvar = "hi"
    var another = "hello"

    barRegularString(clientvar) {
        barRegularString(another) {
            println(another)
        }
    }
}

fun processChunked(chunkHandler: (String) -> Unit) {}

fun testEffectivelyImmutableStringInCustomFunction() {
    var accumulator = ""
    processChunked { chunk ->
        val combinedData = accumulator + chunk
        if (accumulator == "") {
            print(1)
        }
    }
}

/* GENERATED_FIR_TAGS: assignment, functionDeclaration, functionalType, lambdaLiteral, localProperty,
propertyDeclaration, stringLiteral */
