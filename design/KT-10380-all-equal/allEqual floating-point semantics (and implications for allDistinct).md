# allEqual floating-point semantics (and implications for allDistinct)

## The problem

Take two arrays with the same logical content:

```kotlin
val a1: Array<Double> = arrayOf(Double.NaN, Double.NaN)
val a2: DoubleArray   = doubleArrayOf(Double.NaN, Double.NaN)
```

What should `a1.allEqual()` and `a2.allEqual()` return? And should they return the same thing?

The answer is not obvious, because `Double` in Kotlin has two distinct equality models:

- **IEEE 754** — used by `==` between statically-known `Double` / `Float` operands. Here `Double.NaN == Double.NaN` is `false` and `0.0 == -0.0` is `true`.
- **`equals` / collection-equality** — used by generic APIs and equality-based collection operations. Here `Double.NaN.equals(Double.NaN)` is `true` and `0.0.equals(-0.0)` is `false`.

`Array<Double>` has no real choice: as a generic container, its element comparisons go through `equals`, so `a1.allEqual()` is forced into the second model. `DoubleArray`, on the other hand, *could* in principle pick either — and that is exactly where the design freedom lives:

```kotlin
arrayOf(Double.NaN, Double.NaN).allEqual()        // ?
doubleArrayOf(Double.NaN, Double.NaN).allEqual()  // ?
```

The same tension shows up for signed zero — and here it goes in the *opposite* direction, with IEEE saying "equal" and `equals` saying "not equal":

```kotlin
arrayOf(0.0, -0.0).allEqual()        // ?
doubleArrayOf(0.0, -0.0).allEqual()  // ?
```

So the design question this document answers is:

> Which equality model should `allEqual` follow, and should the answer be uniform across `Array<Double>`, `DoubleArray`, `Iterable<Double>`, and `Sequence<Double>`?

## What the current implementation actually does

In the current generator [`libraries/tools/kotlin-stdlib-gen/src/templates/Aggregates.kt`](libraries/tools/kotlin-stdlib-gen/src/templates/Aggregates.kt):

- `allEqual()` for `DoubleArray` / `FloatArray` compares elements via `compareTo == 0`.
- `allEqual()` for `Iterable<T>`, `Sequence<T>`, `Array<T>`, and the rest of the primitive arrays uses ordinary `!=`.
- `allEqualBy()` compares selector results as values of type `K`, i.e. via ordinary `!=`.
- `allEqualWith()` doesn't define any floating-point semantics of its own: that's entirely determined by the user's predicate.

The use of `compareTo == 0` for `DoubleArray` / `FloatArray` (rather than `==`) is intentional: between two statically-known primitive `Double` / `Float` operands, `==` would dispatch to IEEE 754. `compareTo == 0` is how the implementation reaches equals-equivalence-class semantics without boxing through `equals(Any?)`.

Important: three different things must not be conflated here:

1. IEEE 754 equality for expressions of the form `a == b`, when `a` and `b` are statically known to be `Double` / `Float` or their nullable counterparts.
2. [`equals`-semantics](https://kotlinlang.org/docs/equality.html), which Kotlin uses in generic APIs and equality-based collection operations.
3. `compareTo`-semantics, which establishes a total order and, for `Double` / `Float`, agrees with `equals` on equivalence classes.

For `allEqual()` and `allEqualBy()`, the current implementation ultimately ends up consistent everywhere with Kotlin's existing `equals` / collection-equality model:

- `NaN` is considered equal to `NaN`;
- `-0.0` is considered unequal to `0.0`.

This is not the case for `allEqualWith()`: there the semantics is always explicit and user-defined.

## Where the ambiguity in Kotlin actually lies

`Double` and `Float` in Kotlin already carry two different equality models, and that's not a bug — it's part of the language.

### 1. IEEE 754 semantics

When both operands are statically known to be `Double` / `Float` or their nullable counterparts, `==` [follows IEEE 754](https://kotlinlang.org/docs/numbers.html#floating-point-numbers-comparison):

- `Double.NaN == Double.NaN` -> `false`
- `0.0 == -0.0` -> `true`

### 2. Generic and collection equality semantics

When values participate in comparison not as statically-known floating-point operands but in generic APIs or equality-based collection operations, Kotlin uses `equals` / `compareTo`.

The [official Kotlin documentation](https://kotlinlang.org/docs/numbers.html#floating-point-numbers-comparison) specifies precisely this behavior:

- `NaN` is equal to itself;
- `NaN` is greater than any other value;
- `-0.0` is not equal to `0.0`;
- `-0.0` is less than `0.0`.

This is already the de-facto Kotlin standard for how such APIs compare floating-point values.

## Why this equality model is the reasonable choice for `allEqual`

### 1. Consistency across overloads

If we picked IEEE semantics for primitive floating-point arrays, semantically identical APIs would diverge:

- `DoubleArray.allEqual()`
- `Array<Double>.allEqual()`
- `Iterable<Double>.allEqual()`
- `Sequence<Double>.allEqual()`

For the same set of values the user would get different answers depending only on whether the representation is boxed or primitive. That's a poor signal for stdlib.

The current choice removes this asymmetry.

### 2. Consistency with stdlib's existing equality-based APIs

Kotlin has long used non-IEEE semantics in equality-based collection and array APIs.

The most important precedents:

- [`contentEquals()`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/content-equals.html) for `DoubleArray` / `FloatArray`
- [equality of lists and other collections](https://kotlinlang.org/docs/equality.html#structural-equality)
- [`distinct()`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/distinct.html)
- [`toSet()`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/to-set.html) / [`toHashSet()`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/to-hash-set.html)
- `HashSet<Double>` / `Map<Double, V>` (relies on [`Double.equals`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Double.html#equals(java.lang.Object)) under the hood on the JVM)
- [`groupBy()`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/group-by.html)

In all of these the user is already operating under the model:

- `NaN == NaN`
- `-0.0 != 0.0`

So `allEqual()` doesn't introduce a new exotic semantics — it simply continues the existing one.

### 3. Consistency with the natural hand-rolled implementation

Before this stdlib API existed, one of the most natural hand-rolled user implementations was:

```kotlin
iterable.distinct().size <= 1
```

It already operates in the same model as the current `allEqual()`.

If the new stdlib API answered differently from this hand-rolled implementation, the user would hit a migration trap: replacing the manual implementation with the stdlib one would change behavior precisely on the nastiest cases — `NaN` and signed zero.

### 4. `allEqual` is a check on the contents of a collection, not an arithmetic operation

Conceptually `allEqual` is closer to:

- `contentEquals`
- collection equality
- membership in `Set`
- `distinct`

than to a bare `a == b` expression between two primitive floating-point values.

It answers not the question "are two `Double`s equal under IEEE 754?", but the question "does Kotlin treat all elements of this collection as the same, under the rules it normally applies in generic APIs and equality-based collection operations?".

For that question the current Kotlin semantics already exists, and leaning on it is the natural move.

## Why pointing only at `sorted` / `isSorted` isn't enough

The parallel with `sorted` / `isSorted` is, on its own, weak.

Yes, they also use `compareTo`, and for `Double` / `Float` there:

- `NaN` ranks above everything else;
- `-0.0 < 0.0`.

But that's a secondary argument.

The main argument for the current `allEqual` semantics isn't that it "looks like sorting" — it's that it matches the rules Kotlin has already adopted for comparing floating-point values in generic APIs and equality-based collection operations.

## What this means for `allDistinct` / `allDistinctBy`

For the upcoming `allDistinct()` / `allDistinctBy {}`, the same semantics is the better choice.

The reasons are the same:

- consistency with `distinct()`;
- consistency with `toSet()` / `HashSet`;
- consistency between primitive and boxed overloads;
- no migration trap.

In practice this means:

```kotlin
arrayOf(Double.NaN, Double.NaN).allDistinct()        // false
doubleArrayOf(Double.NaN, Double.NaN).allDistinct()  // false

arrayOf(0.0, -0.0).allDistinct()        // true
doubleArrayOf(0.0, -0.0).allDistinct()  // true
```

This may be less intuitive for those who think only in terms of IEEE `==`, but in exchange it lines up entirely with how Kotlin already behaves in generic APIs and equality-based collection operations.

## What other ecosystems do

There's no single global default here. There are several different schools.

### Java

This is the closest and most important precedent.

- [`Double.equals`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Double.html#equals(java.lang.Object)) treats `NaN` as equal to `NaN` and distinguishes `+0.0` from `-0.0`.
- [`Arrays.equals(double[], double[])`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Arrays.html#equals(double[],double[])) is documented exactly the same way.
- [`Stream.distinct()`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/stream/Stream.html#distinct()) uses `Object.equals`.

For Kotlin/JVM this is a very strong argument in favor of the current choice.

### .NET / LINQ

- [`Distinct`](https://learn.microsoft.com/en-us/dotnet/api/system.linq.enumerable.distinct), [`SequenceEqual`](https://learn.microsoft.com/en-us/dotnet/api/system.linq.enumerable.sequenceequal?view=net-8.0), [`GroupBy`](https://learn.microsoft.com/en-us/dotnet/api/system.linq.enumerable.groupby?view=net-7.0) use the default equality comparer.
- For [`Double.Equals`](https://learn.microsoft.com/en-us/dotnet/fundamentals/runtime-libraries/system-double-equals) this means `NaN` is equal to `NaN`.

But there's an important difference from Java and Kotlin here:

- in .NET, [`Double.Equals(0.0, -0.0)` returns `true`](https://learn.microsoft.com/en-us/dotnet/fundamentals/runtime-libraries/system-double-equals).

So .NET corroborates the idea that "equality-based collection APIs often don't follow raw IEEE equality", but it doesn't corroborate Kotlin/Java's specific choice for signed zero.

### JavaScript

[`Set`](https://tc39.es/ecma262/2026/#sec-set-objects) has number-key behavior equivalent to [`SameValueZero`](https://tc39.es/ecma262/2026/#sec-samevaluezero):

- `NaN` is considered equal to `NaN`;
- `+0` and `-0` are considered equal.

Again, an argument that collection equality often differs from raw numeric `==`, but not an argument for distinguishing signed zero specifically.

### Rust

Rust takes a different philosophy:

- `f32` / `f64` do not implement [`Eq`](https://doc.rust-lang.org/std/cmp/trait.Eq.html);
- [`HashSet<f64>`](https://doc.rust-lang.org/std/collections/struct.HashSet.html) from the standard library is therefore impossible;
- [`itertools::all_equal()`](https://docs.rs/itertools/latest/itertools/trait.Itertools.html#method.all_equal) works through `PartialEq`, i.e. with IEEE semantics.

This is a coherent design, but it's only possible because Rust's type system strictly forbids treating floats as ordinary `Eq` types.

Kotlin has long since left that model behind.

### NumPy

NumPy doesn't hide the choice behind default magic; it makes it explicit:

- [`np.array_equal(a, b, equal_nan=False)`](https://numpy.org/doc/stable/reference/generated/numpy.array_equal.html)

This is a good illustration that the problem is real, but a parameterized API of this kind for `allEqual` in Kotlin stdlib would most likely be overkill.

## Takeaway from external precedents

There's no overall cross-language consensus.

But there is an important pattern:

- many equality-based collection APIs in popular languages depart from raw IEEE equality at least in the `NaN` case;
- the JVM world, which Kotlin is particularly close to, has long lived in the `Double.equals` / `Double.compareTo` model.

That's exactly why the current choice looks natural for Kotlin stdlib.

## Practical recommendation

### For `allEqual()` and `allEqualBy()`

Keep the current semantics.

That is, treat the default behavior for floating-point values as aligned with Kotlin's `equals` / collection-equality model:

- `NaN == NaN`
- `-0.0 != 0.0`

### For `allEqualWith()`

Nothing needs to change, and there's no need to ascribe the same semantics to it.

This is an intentional escape hatch.

If a user specifically wants IEEE behavior, they can already write:

```kotlin
values.allEqualWith { a, b -> a == b }
```

And that will be an IEEE comparison, because inside the lambda `a` and `b` are statically known to be `Double` / `Float` or their nullable counterparts.

This works for:

- `Iterable<Double>`
- `Sequence<Double>`
- `Array<Double>`
- `DoubleArray`

and likewise for `Float`.

### For `allDistinct()` / `allDistinctBy()`

Pick the same default semantics as for `allEqual()` / `allEqualBy()`.

## What to make explicit in samples and tests

This is already well covered in tests.

In samples, it's worth explicitly showing at least the following cases:

```kotlin
doubleArrayOf(Double.NaN, Double.NaN).allEqual()   // true
doubleArrayOf(0.0, -0.0).allEqual()                // false
doubleArrayOf(Double.NaN).allEqual()               // true

doubleArrayOf(Double.NaN, Double.NaN).allEqualWith { a, b -> a == b }  // false
doubleArrayOf(0.0, -0.0).allEqualWith { a, b -> a == b }               // true
```

This is especially useful because it surfaces both models side by side:

- the default stdlib semantics;
- explicit IEEE semantics via the predicate.
