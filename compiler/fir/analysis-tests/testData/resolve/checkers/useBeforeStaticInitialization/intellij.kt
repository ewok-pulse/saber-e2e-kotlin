// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL

@<!UNRESOLVED_REFERENCE!>ApiStatus<!>.Internal
public sealed interface InternalEnvironmentName {
    public val name: @<!UNRESOLVED_REFERENCE!>NonNls<!> String

    public data object Local : InternalEnvironmentName {
        override val name: String = LOCAL_INTERNAL_ENVIRONMENT_NAME
    }

    public data class Custom(override val name: String) : InternalEnvironmentName

    public companion object {
        private const val LOCAL_INTERNAL_ENVIRONMENT_NAME = "Local"

        @<!UNRESOLVED_REFERENCE!>JvmStatic<!>
        public fun of(name: String): InternalEnvironmentName = if (name == LOCAL_INTERNAL_ENVIRONMENT_NAME) Local else Custom(name)
    }
}

<!POSSIBLE_INITIALIZATION_DEADLOCK!>internal abstract class ParseItem(val source: String?) {
    abstract fun match(file: CharSequence, options: <!UNRESOLVED_REFERENCE!>MinimatchOptions<!>): Boolean

    companion object {
        @<!UNRESOLVED_REFERENCE!>JvmField<!>
        val Empty: ParseItem = LiteralItem("")
    }
}<!>

internal class ParseResult(val item: ParseItem, val isB: Boolean)

internal class GlobStar : ParseItem(null) {
    override fun match(file: CharSequence, options: <!UNRESOLVED_REFERENCE!>MinimatchOptions<!>): Nothing = throw UnsupportedOperationException()

    override fun toString(): String = "GlobStar"
}

<!POSSIBLE_INITIALIZATION_DEADLOCK!>internal class LiteralItem(source: String) : ParseItem(source) {
    override fun match(file: CharSequence, options: <!UNRESOLVED_REFERENCE!>MinimatchOptions<!>): Boolean = if (options.<!UNRESOLVED_REFERENCE!>nocase<!>) <!UNRESOLVED_REFERENCE!>StringUtil<!>.equalsIgnoreCase(file, source) else <!UNRESOLVED_REFERENCE!>StringUtil<!>.equals(file, source)

    // TODO Auto-generated method stub
    override fun toString(): String = "Literal(\"$source\")"
}<!>
