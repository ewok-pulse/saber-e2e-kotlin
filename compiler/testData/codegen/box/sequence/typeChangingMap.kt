fun box(): String {
    val seq = sequenceOf(5, 7, 9).map { it.toString() }.map { it.toInt() + 5 }
    val list = listOf(10, 12, 14)
    var index = 0
    for (item in seq) {
        if (list[index++] != item) return "FAIL"
    }
    return "OK"
}