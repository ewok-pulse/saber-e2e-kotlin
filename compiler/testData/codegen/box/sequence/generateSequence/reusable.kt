// WITH_STDLIB

fun box(): String {
    val sequence = generateSequence(1) { x: Int -> if (x < 5) x + 1 else null }
    for (item in sequence) {}
    for (item in sequence) {}
    val seq2 = generateSequence({ 1 }) { x: Int -> if (x < 3) x + 1 else null }
    for (item in seq2) {}
    for (item in seq2) {}
    try {
        val seq3 = generateSequence { 1 }
        seq3.iterator()
        seq3.iterator()
    } catch (e: IllegalStateException) {
        if (e.message != "This sequence can be consumed only once.") return "Exception thrown has wrong message: ${e.message}"
        try {
            generateSequence { 1 }.let { seq4 ->
                seq4.iterator()
                seq4.iterator()
            }
        } catch (e: IllegalStateException) {
            if (e.message != "This sequence can be consumed only once.") return "Exception thrown has wrong message: ${e.message}"
            return "OK"
        }
    }
    return "failed: generateSequence(() -> T?) should not allow reusing the sequence"
}
