interface <caret_subclass>A<T : CharSequence> : B<T>
interface B<T>

fun test(x: <caret_supertype>B<out Number>) {}
