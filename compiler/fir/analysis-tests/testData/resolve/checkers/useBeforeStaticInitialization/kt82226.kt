// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL

<!POSSIBLE_INITIALIZATION_DEADLOCK!>interface IrDeclarationOrigin {
    val name: String
    val isSynthetic: Boolean
        get() = false

    companion object {
        val TEST = IrDeclarationOriginImpl("TEST", isSynthetic = true)
    }
}<!>

<!POSSIBLE_INITIALIZATION_DEADLOCK!>class IrDeclarationOriginImpl(
    override val name: String,
    override val isSynthetic: Boolean = false
) : IrDeclarationOrigin {
    override fun toString(): String = name
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IrDeclarationOriginImpl) return false

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int = name.hashCode()

    /**
     * Lazy is required here to avoid initialization loop between this class and [IrDeclarationOrigin].
     * Otherwise, if one thread tries to use this (or [Synthetic]) delegate or directly instantiate [IrDeclarationOriginImpl],
     * while thread would try to access [IrDeclarationOrigin.Companion], it could lead to dead-lock, as one thread would
     * wait while [IrDeclarationOriginImpl] is initialized to proceed with [IrDeclarationOrigin.Companion] initialization,
     * and other would wait while [IrDeclarationOrigin] is initialized as it's super-interface of [IrDeclarationOrigin]
     * which has default methods (and so must be initialized before class).
     */
//    object Regular {
//        operator fun getValue(thisRef: Any?, property: KProperty<*>) = IrDeclarationOriginImpl(property.name)
//    }
//
//    object Synthetic {
//        operator fun getValue(thisRef: Any?, property: KProperty<*>) = IrDeclarationOriginImpl(property.name, isSynthetic = true)
//    }
}<!>
