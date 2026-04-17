// TARGET_BACKEND: JVM
// WITH_REFLECT
// LANGUAGE: +CompanionBlocksAndExtensions

import kotlin.reflect.KProperty
import kotlin.reflect.jvm.isAccessible
import kotlin.test.assertEquals

class Delegate(var storage: String) {
    operator fun getValue(instance: Any?, property: KProperty<*>) = storage
    operator fun setValue(instance: Any?, property: KProperty<*>, value: String) { storage = value }
}

class A {
    companion {
        var p: String by Delegate("p")
    }
}

fun box(): String {
    assertEquals("p", A::p.call())
    assertEquals(Unit, A::p.setter.call("a"))
    assertEquals("a", A::p.call())

    // TODO: uncomment when KT-85767 is fixed.
    // val pd = (A::p).apply { isAccessible = true }.getDelegate() as Delegate
    // assertEquals("a", pd.storage)

    return "OK"
}
