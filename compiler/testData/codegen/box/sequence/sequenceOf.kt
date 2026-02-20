fun box(): String {
    val seq = sequenceOf(1, 2, 3)
    val list = listOf(1, 2, 3)
    var index = 0
    for (item in seq) {
        if (item != list[index++]) return "fail"
    }
    return "OK"
}