// WITH_STDLIB

import kotlin.test.*

fun box(s: String): String {
    class Local {
        open inner class Inner() {
            open fun result() = s
        }
    }

    return Local().Inner().result()
}

fun box(): String {
    return box("OK")
}
