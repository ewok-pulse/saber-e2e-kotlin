interface <caret_subclass>A<T> : B<T>
interface B<T> : C<T>
interface C<T>

fun test(x: <caret_supertype>C<out Number>) {}
