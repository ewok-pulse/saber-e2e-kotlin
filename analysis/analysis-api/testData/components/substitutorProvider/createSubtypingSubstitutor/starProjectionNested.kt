// WITH_STDLIB
interface <caret_subclass>A<T> : B<T>
interface B<T>

fun test(x: <caret_supertype>B<List<*>>) {}
