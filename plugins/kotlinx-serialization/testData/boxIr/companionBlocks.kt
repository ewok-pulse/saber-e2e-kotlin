// LANGUAGE: +CompanionBlocksAndExtensions
// TARGET_BACKEND: JVM_IR
// JS: KT-85459, Native: KT-84829

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.builtins.*

@Serializable
data class Vector(val x: Double, val y: Double) {
    companion {
        val UnitX = Vector(1.0, 0.0)

        val json = Json { encodeDefaults = true }
    }
}

@Serializable
data class Box<T>(val t: T) {
    companion {
        val nullBox: Box<Any?> = Box(null)
    }
}

fun <T> boxTest(t: T, kSerializer: KSerializer<T>, expected: String, descCount: Int): String {
    val encoded = Vector.json.encodeToString(kSerializer, t)
    if (encoded != expected) return "FAIL encoded: $encoded expected: $expected"
    val decoded = Vector.json.decodeFromString(kSerializer, encoded)
    if (decoded != t) return "FAIL decoded: $decoded expected: $t"
    val cnt = kSerializer.descriptor.elementsCount
    if (cnt != descCount) return "FAIL element count: $cnt expected: $descCount"
    return "OK"
}

fun box(): String {
    val vector = boxTest(Vector.UnitX, Vector.serializer(), """{"x":1.0,"y":0.0}""", 2)
    if (vector != "OK") return vector
    val box = boxTest(Box("abc"), Box.serializer(String.serializer()), """{"t":"abc"}""", 1)
    return box
}
