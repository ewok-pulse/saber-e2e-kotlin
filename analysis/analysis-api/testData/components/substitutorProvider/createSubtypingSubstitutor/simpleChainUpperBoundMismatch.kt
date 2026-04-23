interface <caret_subclass>A<T> : B<T> // [UPPER_BOUND_VIOLATED] Type argument is not within its bounds: must be subtype of 'Int'.
interface B<T : Int> : C<Int, T>
interface C<X, Y>

fun test(x: <caret_supertype>C<Int, String>) {}
