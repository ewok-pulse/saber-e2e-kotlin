interface <caret_subclass>A<T : Number> : B<T>
interface B<T>

fun test(x: <caret_supertype>B<out Number>) {}
