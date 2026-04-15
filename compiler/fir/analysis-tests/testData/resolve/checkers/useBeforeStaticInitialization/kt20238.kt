// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL

<!POSSIBLE_INITIALIZATION_DEADLOCK!>enum class Enum(val y: String) {
    <!UNINITIALIZED_PROPERTY!>ENTRY(<!UNINITIALIZED_ACCESS!>EnumTest.x<!>) {
        override fun toString(): String = y
    };<!>
}<!>

<!POSSIBLE_INITIALIZATION_DEADLOCK!>interface EnumTest {
    companion object {
        val x = "OK"
        <!UNINITIALIZED_PROPERTY!>val z = <!UNINITIALIZED_ACCESS!>Enum.ENTRY.y<!><!>
    }
}<!>

class Class {
    val y = <!UNINITIALIZED_ACCESS!>ClassTest.y<!>
}

<!POSSIBLE_INITIALIZATION_DEADLOCK!>interface ClassTest {
    companion object {
        val x = "OK"
        <!UNINITIALIZED_PROPERTY!>val z = Class().y<!>
        val y = "yay"
    }
}<!>
