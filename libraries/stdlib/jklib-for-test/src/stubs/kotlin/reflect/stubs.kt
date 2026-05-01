package kotlin.reflect

@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalAssociatedObjects

public interface KClassifier
public interface KCallable<out R>

public expect interface KClass<T : Any> : KClassifier {
    public val simpleName: String?
    public val qualifiedName: String?
    public fun isInstance(value: Any?): Boolean
}

public expect interface KFunction<out R> : KCallable<R>, Function<R>

public expect interface KFunction0<out R> : KFunction<R>, () -> R
public expect interface KFunction1<in P1, out R> : KFunction<R>, (P1) -> R
public expect interface KFunction2<in P1, in P2, out R> : KFunction<R>, (P1, P2) -> R
public expect interface KFunction3<in P1, in P2, in P3, out R> : KFunction<R>, (P1, P2, P3) -> R

public expect interface KSuspendFunction0<out R> : KFunction<R>, suspend () -> R
public expect interface KSuspendFunction1<in P1, out R> : KFunction<R>, suspend (P1) -> R
public expect interface KSuspendFunction2<in P1, in P2, out R> : KFunction<R>, suspend (P1, P2) -> R
public expect interface KSuspendFunction3<in P1, in P2, in P3, out R> : KFunction<R>, suspend (P1, P2, P3) -> R


public expect interface KProperty<out V> : KCallable<V>
public expect interface KMutableProperty<V> : KProperty<V>

public expect interface KProperty0<out V> : KProperty<V>, () -> V {
    public fun get(): V
}
public expect interface KMutableProperty0<V> : KProperty0<V>, KMutableProperty<V> {
    public fun set(value: V)
}

public expect interface KProperty1<T, out V> : KProperty<V>, (T) -> V {
    public fun get(receiver: T): V
}
public expect interface KMutableProperty1<T, V> : KProperty1<T, V>, KMutableProperty<V> {
    public fun set(receiver: T, value: V)
}

public expect interface KProperty2<D, E, out V> : KProperty<V>, (D, E) -> V {
    public fun get(receiver1: D, receiver2: E): V
}
public expect interface KMutableProperty2<D, E, V> : KProperty2<D, E, V>, KMutableProperty<V> {
    public fun set(receiver1: D, receiver2: E, value: V)
}

public expect class KTypeProjection

public expect interface KType {
    public val classifier: KClassifier?
    public val arguments: List<KTypeProjection>
    public val isMarkedNullable: Boolean
}

@ExperimentalAssociatedObjects
public expect inline fun <reified T : Annotation> KClass<*>.findAssociatedObject(): Any?
