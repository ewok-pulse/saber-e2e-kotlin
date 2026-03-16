// WITH_STDLIB

fun box(): String {
    val list = listOf(1, 2, 3)
    val seq = list.asSequence().map { it * 2 }
    val list2 = list.map { it * 2 }
    var index = 0
    for (item in seq) {
        if (item != list2[index++]) return "failed: sequence yielded: $item, while the expected was: ${list2[index - 1]} at index: ${index - 1}"
    }
    return "OK"
}
