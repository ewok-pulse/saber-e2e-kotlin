// TARGET_BACKEND: JVM
// WITH_STDLIB

@file:OptIn(kotlin.ExperimentalStdlibApi::class)

fun <@JvmSpecialize T> makeNullable(x: T): T? = x

fun <@JvmSpecialize T> makeNonNullable(x: T?): T = when {
    x == null -> error("argument is in fact nullable")
    else -> return x
}

fun <@JvmSpecialize T> innerId(x: T) = x
fun <@JvmSpecialize T> id(x: T): T {
    return innerId<T?>(x)!!
}

@JvmInline
value class I(val x: Int)

@JvmInline
value class IN(val x: Int?)

@JvmInline
value class S(val x: String)

@JvmInline
value class SN(val x: String?)

fun box(): String {
    if (makeNullable<Int>(42) != 42) return "fail: makeNullable<Int>"
    if (makeNullable<Int?>(42) != 42) return "fail: makeNullable<Int?>"
    if (makeNullable<String>("hello") != "hello") return "fail: makeNullable<String>"
    if (makeNullable<String?>("hello") != "hello") return "fail: makeNullable<String?>"
    if (makeNullable<I>(I(42)) != I(42)) return "fail: makeNullable<I>"
    if (makeNullable<I?>(I(42)) != I(42)) return "fail: makeNullable<I?>"
    if (makeNullable<IN>(IN(42)) != IN(42)) return "fail: makeNullable<IN>"
    if (makeNullable<IN?>(IN(42)) != IN(42)) return "fail: makeNullable<IN?>"
    if (makeNullable<S>(S("hello")) != S("hello")) return "fail: makeNullable<S>"
    if (makeNullable<S?>(S("hello")) != S("hello")) return "fail: makeNullable<S?>"
    if (makeNullable<SN>(SN("hello")) != SN("hello")) return "fail: makeNullable<SN>"
    if (makeNullable<SN?>(SN("hello")) != SN("hello")) return "fail: makeNullable<SN?>"

    if (makeNonNullable<Int>(42) != 42) return "fail: makeNonNullable<Int>"
    if (makeNonNullable<Int?>(42) != 42) return "fail: makeNonNullable<Int?>"
    if (makeNonNullable<String>("hello") != "hello") return "fail: makeNonNullable<String>"
    if (makeNonNullable<String?>("hello") != "hello") return "fail: makeNonNullable<String?>"
    if (makeNonNullable<I>(I(42)) != I(42)) return "fail: makeNonNullable<I>"
    if (makeNonNullable<I?>(I(42)) != I(42)) return "fail: makeNonNullable<I?>"
    if (makeNonNullable<IN>(IN(42)) != IN(42)) return "fail: makeNonNullable<IN>"
    if (makeNonNullable<IN?>(IN(42)) != IN(42)) return "fail: makeNonNullable<IN?>"
    if (makeNonNullable<S>(S("hello")) != S("hello")) return "fail: makeNonNullable<S>"
    if (makeNonNullable<S?>(S("hello")) != S("hello")) return "fail: makeNonNullable<S?>"
    if (makeNonNullable<SN>(SN("hello")) != SN("hello")) return "fail: makeNonNullable<SN>"
    if (makeNonNullable<SN?>(SN("hello")) != SN("hello")) return "fail: makeNonNullable<SN?>"

    if (id<Int>(42) != 42) return "fail: id<Int>"
    if (id<Int?>(42) != 42) return "fail: id<Int?>"
    if (id<String>("hello") != "hello") return "fail: id<String>"
    if (id<String?>("hello") != "hello") return "fail: id<String?>"
    if (id<I>(I(42)) != I(42)) return "fail: id<I>"
    if (id<I?>(I(42)) != I(42)) return "fail: id<I?>"
    if (id<IN>(IN(42)) != IN(42)) return "fail: id<IN>"
    if (id<IN?>(IN(42)) != IN(42)) return "fail: id<IN?>"
    if (id<S>(S("hello")) != S("hello")) return "fail: id<S>"
    if (id<S?>(S("hello")) != S("hello")) return "fail: id<S?>"
    if (id<SN>(SN("hello")) != SN("hello")) return "fail: id<SN>"
    if (id<SN?>(SN("hello")) != SN("hello")) return "fail: id<SN?>"

    return "OK"
}
