// WITH_STDLIB

fun box(): String {
    var k = 1
    val seq = generateSequence(k, { if (it < 4) it + 1 else null })
    k = 2
    val list = listOf(1, 2, 3, 4)
    var index = 0
    for (item in seq) {
        if (item != list[index++]) return "failed: sequence yielded: $item, while the expected was: ${list[index - 1]} at index: ${index - 1}"
    }
    return "OK"
}
