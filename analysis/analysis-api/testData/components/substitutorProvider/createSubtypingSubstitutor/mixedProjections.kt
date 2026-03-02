interface <caret_subclass>A<T, S> : B<T, S>
interface B<X, Y>

fun test(x: <caret_supertype>B<Int, out String>) {}
