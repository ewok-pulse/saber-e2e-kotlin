// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
abstract class X(val y: Bar)

<!POSSIBLE_INITIALIZATION_DEADLOCK!>object Bar<!> {
    <!UNINITIALIZED_PROPERTY!>val prop = <!UNINITIALIZED_ACCESS!>Foo.const<!><!>
}

<!POSSIBLE_INITIALIZATION_DEADLOCK!>class Foo {
    companion <!POSSIBLE_INITIALIZATION_DEADLOCK!>object<!> : X(Bar) {
        val const = "AAA"
    }
}<!>
