fun box(): String {
    val seq = sequenceOf(1, 2, 3).map { it * 3 }.map { it * 2 }
    val list = listOf(6, 12, 18)
    var index = 0
    for (item in seq) {
        if (list[index++] != item) return "FAIL"
    }
    return "OK"
}