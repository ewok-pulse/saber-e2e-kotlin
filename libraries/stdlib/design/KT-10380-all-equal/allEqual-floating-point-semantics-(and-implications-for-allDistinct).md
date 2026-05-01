# allEqual floating-point semantics (and implications for allDistinct)

## Status

The design meeting (2026-04-29) confirmed the current implementation: `allEqual()` for `DoubleArray` / `FloatArray` uses `compareTo == 0`, putting the primitive overloads on Kotlin's `equals` / collection-equality model — `NaN == NaN`, `-0.0 != 0.0`. The corollary decision is to remove `allEqualWith()` from the final API surface (see [Practical recommendation](#practical-recommendation)). This document captures the rationale for both and folds in the open questions raised by review on [PR #5941](https://github.com/JetBrains/kotlin/pull/5941):

- the closest internal Kotlin precedent — see [Internal precedent: KTLC-192](#internal-precedent-ktlc-192);
- what users actually write today when hand-rolling the check — see [Consistency with what users actually hand-roll today](#3-consistency-with-what-users-actually-hand-roll-today);
- whether epsilon / tolerance comparison should be considered — see [Why epsilon-tolerance comparison doesn't fit `allEqual`](#why-epsilon-tolerance-comparison-doesnt-fit-allequal);
- joint design with `allDistinct*` — see [What this means for `allDistinct` / `allDistinctBy`](#what-this-means-for-alldistinct--alldistinctby);
- broader survey of other ecosystems including Python — see [What other ecosystems do](#what-other-ecosystems-do).

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

In the current generator [`libraries/tools/kotlin-stdlib-gen/src/templates/Aggregates.kt`](../../../tools/kotlin-stdlib-gen/src/templates/Aggregates.kt):

- `allEqual()` for `DoubleArray` / `FloatArray` compares elements via `compareTo == 0`.
- `allEqual()` for `Iterable<T>`, `Sequence<T>`, `Array<T>`, the rest of the primitive arrays, and unsigned arrays uses ordinary `!=`.
- `allEqualBy()` compares selector results as values of type `K`, i.e. via ordinary `!=`. Because `K` is a generic type parameter, the comparison goes through structural equality even when the selector returns `Double` / `Float` — so the floating-point behavior is the same as for the boxed-element overloads of `allEqual()`, regardless of the receiver type. In particular, on a `DoubleArray` / `FloatArray`, `allEqualBy { it }` follows the generic-selector equality path rather than `allEqual()`'s primitive `compareTo == 0` implementation, but it gives the same answer.
- `allEqualWith()` currently doesn't define any floating-point semantics of its own: that's entirely determined by the user's predicate. It is not part of the final API rationale here, because the design meeting (2026-04-29) decided to remove it before finalizing the `allEqual` family.

All overloads return `true` for empty (vacuously) and single-element receivers.

The use of `compareTo == 0` for `DoubleArray` / `FloatArray` (rather than `==`) is intentional: between two statically-known primitive `Double` / `Float` operands, `==` would dispatch to IEEE 754. `compareTo == 0` is how the implementation reaches equals-equivalence-class semantics without the per-element box that `equals(Any?)` would incur — on JVM the compiler intrinsifies `Double.compareTo(Double)` to `java.lang.Double.compare(double, double)`, a non-boxing static call.

Important: two equality models and one related ordering tool must not be conflated here:

1. IEEE 754 equality for expressions of the form `a == b`, when `a` and `b` are statically known to be `Double` / `Float` or their nullable counterparts.
2. [`equals`-semantics](https://kotlinlang.org/docs/equality.html), which Kotlin uses in generic APIs and equality-based collection operations.
3. `compareTo`-semantics — a total order, not an equality model, that for `Double` / `Float` agrees with `equals` on equivalence classes (`compareTo == 0` ⇔ `equals` returns `true`). This is what the current `DoubleArray` / `FloatArray` overloads use to reach model (2) without boxing through `equals(Any?)`.

The next section ("Where the ambiguity in Kotlin actually lies") covers (1) and (2) — the two equality models — in detail; (3) is treated as the implementation tool that bridges from primitive operands to (2).

For `allEqual()` and `allEqualBy()`, the current implementation ultimately ends up consistent everywhere with Kotlin's existing `equals` / collection-equality model:

- `NaN` is considered equal to `NaN`;
- `-0.0` is considered unequal to `0.0`.

This is not the case for the currently implemented `allEqualWith()`: there the semantics is always explicit and user-defined.

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

#### Internal precedent: KTLC-192

The closest internal Kotlin precedent is [KTLC-192](https://youtrack.jetbrains.com/issue/KTLC-192) — *"Comparing floating-point values in array/list operations 'contains', 'indexOf', 'lastIndexOf': IEEE 754 or total order"*. It addressed exactly the same `DoubleArray` / `FloatArray` ambiguity that this document does, on the closest sibling APIs (membership and lookup rather than aggregate equality), and was **approved by the Language Committee** (resolved 2024-11).

The approved direction: deprecate the original IEEE behavior of `DoubleArray.contains` / `indexOf` / `lastIndexOf` and make the list-facing behavior of `DoubleArray.asList()` / `FloatArray.asList()` compatible with the `List` contract. The motivation in the issue was the same one that shows up here:

- IEEE was breaking the natural invariant `array[i] in array == true` (it failed for `NaN`).
- `DoubleArray.asList()` produced a `List<Double>` whose `contains` / `indexOf` disagreed with ordinary `List<Double>` behavior — a `List`-contract violation.

In the current stdlib sources, the hidden deprecated primitive-array search extensions still preserve the old IEEE implementation for migration / compatibility, while the `asList()` implementations use list-compatible equality. The relevant design signal is still clear: LC rejected raw IEEE `==` as the natural collection/search semantics for these floating-point array APIs.

Same array types, same ambiguity, same body deciding. The current `allEqual()` is the natural continuation of that direction; choosing IEEE semantics for `DoubleArray.allEqual()` would introduce, on a new aggregate, the same kind of primitive-vs-boxed inconsistency KTLC-192 was designed to remove from the list-facing API surface.

### 3. Consistency with what users actually hand-roll today

There isn't *one* natural hand-rolled implementation. To find out what users actually write, we surveyed two corpora: a curated set of large Kotlin repositories (`androidx`, `intellij-community`, `Exposed`, `dokka`, `detekt`, `kmath`, `multik`, `lets-plot`, `Anki-Android`, `mihon`, `openrndr/orx`, etc.) and the GitHub-wide index via `gh api search/code`. Two patterns appear at scale, and they **disagree** on FP semantics — which is precisely why the design choice matters.

**Pattern A — `.all { it == first }` / `.all { it == constant }`** (IEEE 754 when elements are statically `Double` / `Float`):

```kotlin
// androidx/camera/camera-camera2-pipe/.../FrameImpl.kt
statuses.all { it == statuses.first() }

// androidx/wear/.../IconButtonTest.kt — "All spaces around the button should be equal"
spaces.all { it == spaces[0] }

// androidx/xr/arcore/.../FaceStateTest.kt — actual FloatArray, XR face-tracking
underTest.parameters.all { it == 0.0f }
underTest.regionConfidences.all { it == 0.0f }

// Kotlin/multik (NumPy-like) — actual Double matrix
mk.zeros<Double>(3, 4).all { it == 0.0 }
```

GitHub-wide: the query `"FloatArray" "all { it == "` (Kotlin) returns 200+ hits across numerical / DL / CV projects.

**Pattern B — `.toSet().size == 1` / `.distinct().size <= 1`** (equals / total-order semantics):

```kotlin
// intellij-community/.../BaseCompletionGolfFileReportGenerator.kt
// computeMetric returns Double — this is the equals-semantics check on real Double data
val metricValuesAll: List<Double> = curSessions.map { computeMetric(it) }
if (curSessions.size > 1 && metricValuesAll.distinct().size == 1) {
    rowClasses = "$rowClasses duplicate"
}

// androidx/compose/runtime/.../CompositionTests.kt — "expected all parentReferences to be the same"
check(parentReferences.toSet().size == 1) { "expected all parentReferences to be the same" }

// Exposed/.../SetOperations.kt — "Each query must have the same number of columns"
require(rawStatements.map { it.set.realFields.size }.distinct().size == 1) { "Each query must have the same number of columns" }

// lets-plot/.../DensityStatUtil.kt — "All data series in stat data must have equal size"
require(statData.values.map { it.size }.toSet().size == 1) { "All data series in stat data must have equal size" }

// intellij-community/.../MatchSequence.kt — "For all texts there should be the same amount of ranges"
require(ranges.map { it.value.size }.distinct().size <= 1) { "For all texts there should be the same amount of ranges" }

// intellij-community/.../DefaultIntentionActionWithChoice.kt
// "All default intention actions with choice are expected to have same family"
require(result.map { it.familyName }.toSet().size == 1) { "All default intention actions with choice are expected to have same family" }

// intellij-community/.../MLSorter.kt
items.map { cachedScore[it]?.prefixLength }.toSet().size == 1
```

Even custom test DSLs land on Pattern B. Dokka's `infix fun Collection<Any>?.allEquals(other: Any?)` (in `dokka-subprojects/plugin-base/src/test/kotlin/utils/TestUtils.kt`) is implemented with `assertEquals` per element — i.e. `equals`-semantics — and is used in dozens of Dokka tests like `visibility.values allEquals KotlinVisibility.Public`.

GitHub-wide: `.toSet().size == 1` returns ~640 hits in Kotlin code; `.distinct().size == 1` returns ~400. Pattern B is at least as widespread as Pattern A in absolute terms — and, more importantly, the *intentional* "are these things all the same?" idiom in production Kotlin (with explanatory error messages calling out exactly that) is overwhelmingly Pattern B.

**Aside, not a counter-example.** The same `.all { it == … }` shape is also used as a NaN test in FP-heavy code, e.g. `openrndr/orx/.../GradientDescent.kt` (sourced via GitHub-wide search):

```kotlin
require(g0.all { it == it && it != Double.POSITIVE_INFINITY && it != Double.NEGATIVE_INFINITY })
require(pstep.all { it == it }) { "pstep contains NaNs" }
require(g1.all { it == it })
```

Here `it == it` is *deliberately* IEEE — it returns `false` only for `NaN`. This is a different question ("does the array contain a `NaN`?") from "are all elements equal?", and demonstrates that some FP-heavy code consciously relies on IEEE behavior. It is not an argument that `allEqual()` should be IEEE — and notice that other FP-heavy projects (kmath) reach for the explicit `Double.isNaN()` API for the same job:

```kotlin
// kmath/kmath-stat/.../InternalUtils.kt
require(!(prob < 0 || prob.isInfinite() || prob.isNaN())) { "Invalid probability: $prob" }

// kmath/kmath-complex/.../Quaternion.kt
require(!w.isNaN()) { "w-component of quaternion is not-a-number" }
```

i.e. NaN-handling is a separate, explicit concern, not entangled with "are all elements equal".

**The implication for the FP decision.** Both Pattern A and Pattern B are mainstream, but they are not equivalent in spirit:

- Pattern A is a direct "all the same value as the first one" idiom. On statically-known `Double` / `Float` elements it silently switches to IEEE 754, reproducing the trap KTLC-192 addressed for list-facing floating-point array search APIs. The most striking real example on actual FP data — `metricValuesAll.distinct().size == 1` on a `List<Double>` of ML-evaluation metrics in IntelliJ — explicitly does **not** use Pattern A.
- Pattern B is well represented in production code where "all the same" is the *primary* question (set / column / size / family / metric uniformity). It carries equals-equivalence-class semantics, and its FP behavior matches `Set<Double>`, `distinct()`, `contentEquals` — and KTLC-192.

`allEqual()` aligns with Pattern B (and with KTLC-192). For non-empty receivers, callers who already use Pattern B see no behavior change when they migrate to the stdlib API; callers who use Pattern A on FP arrays get a deliberate move to Kotlin's collection-equality model instead of the silent IEEE switch. (One intentional difference for empty receivers: `allEqual()` returns `true` vacuously, whereas `.distinct().size == 1` / `.toSet().size == 1` return `false` — the `.distinct().size <= 1` variant matches.)

### 4. `allEqual` is a check on the contents of a collection, not an arithmetic operation

Conceptually `allEqual` is closer to:

- `contentEquals`
- collection equality
- membership in `Set`
- `distinct`

than to a bare `a == b` expression between two primitive floating-point values.

It answers not the question "are two `Double`s equal under IEEE 754?", but the question "does Kotlin treat all elements of this collection as the same, under the rules it normally applies in generic APIs and equality-based collection operations?".

For that question the current Kotlin semantics already exists, and leaning on it is the natural move.

### 5. Platform-uniform behavior

Kotlin pins the behavior of `Double.equals` / `Float.equals` (and `compareTo`) to the same model on every target — JVM, JS, Native, Wasm — independently of how the platform's native `==` operator handles `NaN` and signed zero. So the equality model adopted here behaves identically across all Kotlin targets: there is no "JVM-only" caveat, and porting the receiver between collections and primitive arrays does not change the result.

## Why epsilon-tolerance comparison doesn't fit `allEqual`

A natural follow-up question, raised in DM: direct equality between `Double` values is famously fragile after arithmetic, so should `allEqual` instead consider two values "equal" when they are within some `ε`?

The answer is no, for two independent reasons.

**1. `allEqual` is structurally an equivalence-relation question; epsilon-closeness is not an equivalence relation.**

`allEqual` answers *"are all elements equal to each other?"*. "Equal" here is an equivalence relation — reflexive, symmetric, **transitive**. Epsilon-closeness violates transitivity:

> `|a − b| < ε` and `|b − c| < ε` does **not** imply `|a − c| < ε`.

Concrete: with `ε = 0.5` and `[0.0, 0.4, 0.8]`, consecutive pairs are within ε but the endpoints are not. So an epsilon-based "equality" cannot partition a collection into equivalence classes. Once the collection has more than two elements, several different questions become plausible — "is every element close to the first one?", "are adjacent elements close in iteration order?", "are all pairs close?" — and they can disagree. The all-pairs variant is well-defined, but it is a range / spread check, not equality semantics.

**2. There is no canonical ε for stdlib.**

Tolerance is irreducibly domain-specific: absolute, relative, ULP-based, NaN-handling, sign-aware — every numerical-software project picks something different. Stdlib does not ship epsilon-aware equality anywhere — not for `Set<Double>`, not for `distinct()`, not for `contentEquals` — so making `allEqual` the one place where it does would be both inconsistent and impossible to choose well.

**What to write if "all values close" is what you actually want.** Express it as a range / spread check, not as a flavor of `allEqual`:

```kotlin
// well-defined for finite values, order-independent
(values.max() - values.min()) < eps
```

That is a different operation, with a clear meaning, that does not pretend to be an equivalence relation.

## Why pointing only at `sorted` / `isSorted` isn't enough

The parallel with `sorted` / `isSorted` is, on its own, weak.

Yes, they also use `compareTo`, and for `Double` / `Float` there:

- `NaN` ranks above everything else;
- `-0.0 < 0.0`.

But that's a secondary argument.

The main argument for the current `allEqual` semantics isn't that it "looks like sorting" — it's that it matches the rules Kotlin has already adopted for comparing floating-point values in generic APIs and equality-based collection operations.

## What this means for `allDistinct` / `allDistinctBy`

`allEqual*` and `allDistinct*` are being designed **jointly** — the FP-semantics decision here is not made in isolation, so the symmetry between the two API families is by construction rather than by accident. `allDistinct*` is tracked under [KT-85976](https://youtrack.jetbrains.com/issue/KT-85976) (a more detailed duplicate of [KT-30270](https://youtrack.jetbrains.com/issue/KT-30270)).

For `allDistinct()` / `allDistinctBy {}`, the same semantics is the better choice.

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

Implementation-wise, this means primitive floating-point overloads of `allDistinct()` must not use a naive `==`-based scan. They need to reuse Kotlin equality / hashing semantics (for example through a `HashSet` of boxed keys) or an equivalent representation/`compareTo == 0` strategy, so that `NaN` duplicates collapse and signed zeros remain distinct.

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

### Python

Python's standard `itertools` doesn't ship `all_equal`, but it's the canonical recipe and it lives in [`more_itertools.all_equal`](https://more-itertools.readthedocs.io/en/stable/api.html#more_itertools.all_equal):

```python
more_itertools.all_equal(iterable, key=None)
```

- compares elements via Python `==` — which for `float` is IEEE-aligned (`nan != nan`, `+0.0 == -0.0`);
- the optional `key=` parameter is a transformation, analogous to `allEqualBy`'s selector (it is **not** a 2-arg predicate / comparator);
- no NaN / signed-zero special-casing.

So Python lines up with Rust on FP semantics — both are in the IEEE camp.

### NumPy

NumPy doesn't hide the choice behind default magic; it makes it explicit:

- [`np.array_equal(a, b, equal_nan=False)`](https://numpy.org/doc/stable/reference/generated/numpy.array_equal.html)

This is a good illustration that the problem is real, but a parameterized API of this kind for `allEqual` in Kotlin stdlib would most likely be overkill.

## Takeaway from external precedents

There's no overall cross-language consensus. Roughly:

- **JVM-equals camp** (Java, Kotlin/JVM): `NaN == NaN`, `-0.0 != 0.0`. Used by `Double.equals`, `Arrays.equals(double[], double[])`, `Stream.distinct`.
- **IEEE camp** (Rust `itertools`, Python `more_itertools`): `==` on floats follows IEEE, `NaN != NaN`. Coherent inside ecosystems whose collections / hashing are designed around that.
- **SameValueZero camp** (JavaScript `Set` via SameValueZero, .NET / LINQ `Double.Equals`): `NaN == NaN`, `+0 == -0`.
- **Explicit-flag camp** (NumPy `array_equal(equal_nan=…)`): expose the choice as a parameter.

But the relevant pattern for Kotlin/JVM is unambiguous: many equality-based collection APIs in popular languages depart from raw IEEE equality at least in the `NaN` case, and the JVM world specifically has long lived in the `Double.equals` / `Double.compareTo` model. That's why the current choice looks natural for Kotlin stdlib.

## Practical recommendation

### For `allEqual()` and `allEqualBy()`

Keep the current semantics.

That is, treat the default behavior for floating-point values as aligned with Kotlin's `equals` / collection-equality model:

- `NaN == NaN`
- `-0.0 != 0.0`

### For `allEqualWith()`

Remove it from the final API.

The current implementation makes `allEqualWith()` an explicit user-defined predicate hook, so it does not have default floating-point semantics of its own. However, it should not be used as the escape hatch in the final design: the originally-cited motivator was referential equality, and it is a poor fit for epsilon-style floating-point comparison because such predicates are usually not equivalence relations. The full use-case investigation that informed the removal decision is in [`Removing allEqualWith from the allEqual family`](Removing-allEqualWith-from-the-allEqual-family.md).

Consequently, samples and final API documentation should not rely on `allEqualWith()` as the way to obtain IEEE behavior.

### For `allDistinct()` / `allDistinctBy()`

Pick the same default semantics as for `allEqual()` / `allEqualBy()`.

## What to make explicit in samples

This is already well covered in tests for `allEqual()` and `allEqualBy()`.

In samples, it's worth explicitly showing at least the following cases:

```kotlin
arrayOf(Double.NaN, Double.NaN).allEqual()         // true
arrayOf(0.0, -0.0).allEqual()                      // false

doubleArrayOf(Double.NaN, Double.NaN).allEqual()   // true
doubleArrayOf(0.0, -0.0).allEqual()                // false
doubleArrayOf(Double.NaN).allEqual()               // true
doubleArrayOf().allEqual()                         // true (vacuously)

listOf(Double.NaN, Double.NaN).allEqualBy { it }   // true
listOf(0.0, -0.0).allEqualBy { it }                // false
doubleArrayOf(Double.NaN, Double.NaN).allEqualBy { it }  // true
doubleArrayOf(0.0, -0.0).allEqualBy { it }               // false
```

This is especially useful because it surfaces the default stdlib semantics, applied uniformly across `allEqual()` (boxed and primitive) and `allEqualBy()` (including FP-typed selectors).

No `allEqualWith()` sample should be added if that overload is removed from the final API.
