// LANGUAGE: +ValueClasses
// CHECK_BYTECODE_LISTING

value class UnitWrapper(val unit: Unit)
value class NothingWrapper(val nothing: Nothing)

fun NothingWrapper.wrap() {
    NothingWrapper(this.nothing)
}

fun box(): String {
    val unitWrapper = UnitWrapper(Unit)
    require(unitWrapper == UnitWrapper(Unit))
    require(unitWrapper.unit == Unit)
    
    return "OK"
}
