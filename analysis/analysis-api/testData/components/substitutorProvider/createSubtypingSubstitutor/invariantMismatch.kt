interface <caret_subclass>A<T : Dog> : B<T>
interface B<T>

open class Animal
class Dog : Animal()

fun test(x: <caret_supertype>B<Animal>) {}
