// WITH_STDLIB

fun box(): String {
    val seq = sequenceOf(1, 2, 3)
    for (i in seq) {
        if (i == 2) break
        if (i == 3) return "failed: break skipped"
    }
    return "OK"
}