// WITH_STDLIB

// CHECK_BYTECODE_TEXT
// 0 iterator
fun box(): String {
    val seq = generateSequence(1) { null }.map { if (it == 1) "OK" else "FAIL" }
    for (item in seq) {
        if (item != "OK") return item
    }
    val seq2 = generateSequence({ 1 }, { null }).map { if (it == 1) "OK" else "FAIL" }
    for (item in seq2) {
        if (item != "OK") return item
    }
    generateSequence(1) { if (it < 3) null else it + 1 }.map { if (it == 1) "OK" else "FAIL" }.forEach { if (it != "OK") return it }
    val seq3 = generateSequence { 1 }.map { if (it == 1) "OK" else "FAIL" }
    for (item in seq3) {
        return item
    }
    return "SKIPPED BODY"
}

//private fun Class<*>.computeNormalPropertyOrdering() {
//    generateSequence(this) { it.superclass }.forEach { clazz ->
//        for (unused in clazz.declaredFields) {
//            return
//        }
//    }
//}
