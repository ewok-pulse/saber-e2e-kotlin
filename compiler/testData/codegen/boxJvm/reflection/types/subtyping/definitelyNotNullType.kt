// TARGET_BACKEND: JVM
// WITH_REFLECT

import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.isSupertypeOf
import kotlin.test.assertFalse
import kotlin.test.assertTrue

interface A<T> {
    fun f(t: T & Any): T & Any
    fun g(): T & Any
    fun <T> h(): T & Any
}

fun checkSubtype(subtype: KType, supertype: KType) {
    assertTrue(subtype.isSubtypeOf(supertype), "Expected $subtype to be a subtype of $supertype")
    assertTrue(supertype.isSupertypeOf(subtype), "Expected $subtype to be a subtype of $supertype")
}

fun checkUnrelatedTypes(type1: KType, type2: KType) {
    assertFalse(type1.isSubtypeOf(type2), "Expected $type1 NOT to be a subtype of $type2")
    assertFalse(type2.isSubtypeOf(type1), "Expected $type2 NOT to be a subtype of $type1")
    assertFalse(type1.isSupertypeOf(type2), "Expected $type2 NOT to be a subtype of $type1")
    assertFalse(type2.isSupertypeOf(type1), "Expected $type1 NOT to be a subtype of $type2")
}

fun box(): String {
    val f = A<*>::f
    val t1 = f.parameters.last().type
    val t2 = f.returnType
    checkSubtype(t1, t2)
    checkSubtype(t2, t1)

    val g = A<*>::f
    val t3 = g.returnType
    checkSubtype(t1, t3)
    checkSubtype(t3, t1)

    val h = A::class.members.single { it.name == "h" }.returnType
    checkUnrelatedTypes(t1, h)

    return "OK"
}
