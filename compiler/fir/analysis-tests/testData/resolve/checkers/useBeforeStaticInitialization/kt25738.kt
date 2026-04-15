// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
<!POSSIBLE_INITIALIZATION_DEADLOCK!>sealed class S {
    <!POSSIBLE_INITIALIZATION_DEADLOCK!>object O<!> : S()

    companion object {
        <!UNINITIALIZED_PROPERTY!>val x = foo(<!UNINITIALIZED_ACCESS!>O<!>)<!>
    }
}<!>

fun foo(o: S) = 42
