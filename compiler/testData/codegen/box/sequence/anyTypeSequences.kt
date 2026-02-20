fun box(): String {
    val seq = sequenceOf(12, 23, 34, "cake").map(
        { x ->
            when (x) {
                is Int -> x + 2
                is String -> x + "2"
                else -> x
            }
        })
    val list = listOf(14, 25, 36, "cake2")
    var index = 0
    for (item in seq) {
        if (list[index++] != item) return "FAIL"
    }
    return "OK"
}