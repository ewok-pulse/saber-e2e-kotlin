// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL

<!POSSIBLE_INITIALIZATION_DEADLOCK!>sealed class Base {
    companion object {
        <!UNINITIALIZED_PROPERTY!>val fooAccess = <!UNINITIALIZED_ACCESS!>Derived.foo()<!><!>
    }
}<!>

<!POSSIBLE_INITIALIZATION_DEADLOCK!>class Derived(var value: String) : Base() {
    companion object {
        fun foo(): String = "foo"
    }
}<!>
