// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL

<!POSSIBLE_INITIALIZATION_DEADLOCK!>enum class Enum(val y: String) {
    <!POTENTIALLY_UNINITIALIZED_PROPERTY!>ENTRY(<!POTENTIALLY_UNINITIALIZED_ACCESSES!>EnumTest.x<!>) {
        override fun toString(): String = y
    };<!>
}<!>

<!POSSIBLE_INITIALIZATION_DEADLOCK!>interface EnumTest {
    companion object {
        val x = "OK"
        <!POTENTIALLY_UNINITIALIZED_PROPERTY!>val z = <!POTENTIALLY_UNINITIALIZED_ACCESSES!>Enum.ENTRY.y<!><!>
    }
}<!>

class Class {
    val y = ClassTest.y
}

interface ClassTest {
    companion object {
        val x = "OK"
        <!POTENTIALLY_UNINITIALIZED_PROPERTY!>val z = <!POTENTIALLY_UNINITIALIZED_ACCESSES!>Class().y<!><!>
        val y = "yay"
    }
}

interface Interface {
    fun foo(arg: String = InterfaceTest.x): String
}

class InterfaceImpl : Interface {
    override fun foo(arg: String): String = arg
}

interface InterfaceTest {
    companion object {
        val x = "OK"
        <!POTENTIALLY_UNINITIALIZED_PROPERTY!>val z = <!POTENTIALLY_UNINITIALIZED_ACCESSES!>InterfaceImpl().foo()<!><!>
    }
}
