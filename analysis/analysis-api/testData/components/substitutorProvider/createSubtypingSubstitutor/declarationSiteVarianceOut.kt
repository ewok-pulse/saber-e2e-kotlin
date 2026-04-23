interface <caret_subclass>A<T> : B<T>
interface B<out T>

fun test(x: <caret_supertype>B<String>) {}
