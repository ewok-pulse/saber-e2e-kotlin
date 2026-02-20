fun box(): String {
    val seq = sequenceOf(1, 2, 3)
    val list = listOf(5, 6, 7)
    var index = 0
    for (item in seq.map { it + 4 }) {
        if (list[index++] != item) return "fail"
    }
    return "OK"
}