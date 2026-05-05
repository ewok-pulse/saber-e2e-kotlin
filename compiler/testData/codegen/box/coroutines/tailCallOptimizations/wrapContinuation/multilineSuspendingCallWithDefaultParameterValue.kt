// LANGUAGE: +WrapContinuationForTailCallFunctions
// ISSUE: KT-83363

// WITH_STDLIB
// IGNORE_BACKEND: JS_IR, JS_IR_ES6, WASM_JS, WASM_WASI

import kotlin.coroutines.*

class BuildOptions

suspend fun compile(makeZip: Boolean = false, options: BuildOptions) {}

suspend fun doTest() {
    compile(
        options = BuildOptions()
    )
}

fun box(): String {
    suspend {
        doTest()
    }.startCoroutine(Continuation(EmptyCoroutineContext) {
        it.getOrThrow()
    })
    return "OK"
}