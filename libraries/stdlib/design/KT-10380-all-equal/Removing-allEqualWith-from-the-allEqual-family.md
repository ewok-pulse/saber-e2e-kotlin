# Removing `allEqualWith` from the `allEqual` family

## Status

On the design meeting (2026-04-29) we decided to remove `allEqualWith()` from the final API of the `allEqual` family. The shipped surface is `allEqual()` and `allEqualBy(selector)` only.

This document records the rationale: the original naming concern, the broader question of whether the overload is worth keeping at all, and the use-case research that resolved the latter. It is the companion to [`allEqual` floating-point semantics (and implications for `allDistinct`)](allEqual-floating-point-semantics-%28and-implications-for-allDistinct%29.md), which also commits to removal on the equivalence-relation ground (epsilon-closeness is not an equivalence relation, so `allEqualWith` would be a misuse vector). This doc shares that ground as one strand of its rationale and adds the use-case investigation as the other.

## How this question arose

### The original framing — naming

The custom-predicate variant was implemented as `allEqualWith` with the signature `fun <T> Iterable<T>.allEqualWith(predicate: (T, T) -> Boolean): Boolean` (and analogously for `Sequence`, `Array<T>`, primitive arrays, and unsigned arrays). Filipp Zhinkin's review on [PR #5941](https://github.com/JetBrains/kotlin/pull/5941):

> with does not work well here as the "to be equal with"'s meaning is not far from "to be equal to".

"All equal with X" reads almost like "all equal **to** X" rather than "all equal **under predicate** X". In ordering APIs, the stdlib `*With` suffix conventionally denotes `Comparator`-based variants (`sortedWith`, `maxWith`, `minWith`, `maxOfWith`, `minOfWith`, `isSortedWith`); there "sorted with X" has no competing reading "sorted to X". `allEqualWith` would have looked like that comparator-family naming without taking a `Comparator<in T>`, weakening the established convention in this API area.

The naming question was whether to rename the overload (e.g. by overloading `allEqual()` itself; see [Historical context](#historical-context-rejected-rename-to-overload-alternative)) or to drop it entirely.

### The pivot — drop instead of rename

At the design meeting (2026-04-29) the team chose drop. The reasoning had two strands:

1. **Misuse vector.** The most "obvious" custom predicate is epsilon-tolerance for floating-point — and epsilon-closeness is not an equivalence relation (transitivity fails: `|a − b| < ε` and `|b − c| < ε` does not imply `|a − c| < ε`). See [Why epsilon-tolerance comparison doesn't fit `allEqual`](allEqual-floating-point-semantics-%28and-implications-for-allDistinct%29.md#why-epsilon-tolerance-comparison-doesnt-fit-allequal). Shipping `allEqualWith` invites users to write predicates that violate the contract `allEqualWith` documents (an equivalence relation), and there is no way for the API to detect that.
2. **Cited use case looked thin.** The motivating example given when `allEqualWith` was introduced was referential equality (`{ a, b -> a === b }`), but it was unclear whether real Kotlin code actually wants this often enough to justify a stdlib API.

Filipp's parting note in the PR:

> We discussed that it's better to drop, but let's investigate if there are actually no wide-spread use-cases for it.

The next section captures that investigation.

## Use-case investigation

### Local-repos survey

Surveyed a curated set of major Kotlin codebases for hand-rolled "all elements equal under custom predicate" idioms with non-trivial predicates (anything other than plain `==` / `equals(other)`):

```kotlin
// Pairwise (equivalent under transitivity)
xs.zipWithNext().all { (a, b) -> PRED(a, b) }
xs.windowed(2).all { (a, b) -> PRED(a, b) }

// First-vs-rest (matches `allEqualWith`'s actual semantics)
xs.drop(1).all { PRED(xs.first(), it) }
xs.all { PRED(xs.first(), it) }

// Manual loop variants of the same
```

Repos searched (39 total): `androidx`, `intellij-community`, `dokka`, `Exposed`, `detekt`, `kmath`, `multik`, `lets-plot`, `dataframe`, `arrow`, `ktor`, `kotlinx.coroutines`, `kotlinx.serialization`, `kotest`, `mockk`, `sqldelight`, `ktlint`, `ksp`, `compose-multiplatform`, `apollo-kotlin`, `workflow-kotlin`, `okhttp`, `okio`, `kotlinx-io`, `Anki-Android`, `mihon`, `Signal-Android`, `bitwarden-android`, `element-x-android`, `firefox-android`, `turbine`, `koin`, `gradle`, `wire`, `moshi`, `http4k`, `leakcanary`, `Android`, `familie-ba-sak`.

Search command per repo:

```bash
rg --type-add 'kt:*.{kt,kts}' -t kt -nH -g '!**/build/**' -g '!**/out/**' \
    -e '\.zipWithNext\(\)\s*\.\s*all' \
    -e '\.windowed\(\s*2\s*\)\s*\.\s*all' \
    -e '\.drop\(\s*1\s*\)\s*\.\s*all' \
    -e 'all\s*\{\s*it\s*===\s*'
```

After triaging hits and discarding false positives (sortedness checks, list-vs-list comparisons, per-pair-with-paired-data), the qualifying hits across the entire corpus were:

**Domain-specific structural equality — 2 genuine hits.**

```kotlin
// intellij-community/plugins/editorconfig/backend/src/language/util/EditorConfigTemplateUtil.kt:73
descriptors.asSequence().zipWithNext().all { (prev, current) ->
    haveStructuralEquality(prev, current)
}

// dataframe/plugins/kotlin-dataframe/testData/testUtils.kt:52
schemas.zipWithNext().all { (a, b) -> a.compare(b).isEqual() }
```

Both are pairwise checks where the predicate is a domain-specific structural-equivalence function. Under transitivity (which both predicates are presumably designed to satisfy) they would also have been expressible as "first element equivalent to all others".

**Referential equality (`===`) — 2 genuine hits, both in `intellij-community`'s Kotlin plugin.**

```kotlin
// intellij-community/plugins/kotlin/idea/src/org/jetbrains/kotlin/idea/inspections/KotlinRedundantDiagnosticSuppressInspection.kt:67-68 (production)
val firstElement = places.first() as KtAnnotated
val diagnostics = if (places.all { it === firstElement }) { /* ... */ } else { /* ... */ }

// intellij-community/plugins/kotlin/gradle/gradle-java/k1/test/org/jetbrains/kotlin/idea/codeInsight/gradle/GradleFacetImportTest.kt:863, 865 (test)
val refSdk = sdks.firstOrNull() ?: return
sdks.all { it === refSdk }
```

Both are first-vs-rest (`xs.all { it === xs.first() }`), the exact shape `allEqualWith` would match (the `as KtAnnotated` cast in the first hit is irrelevant for `===`). Both are confined to a single repo's plugin code, and both are already idiomatic without an stdlib wrapper.

A broader `all { ... === ... }` sweep (relaxing the `it ===` part of the regex above to allow destructured or named lambda parameters) surfaced three additional near-matches that turned out *not* to be `allEqualWith`-shaped:

- `dokka/dokka-subprojects/core/src/main/kotlin/org/jetbrains/dokka/pages/PageNodes.kt:200` (plus a verbatim copy at `dokka/dokka-subprojects/plugin-base/.../AllTypesPageNode.kt:46`) — `(this zip other).all { (a, b) -> a === b }` inside a `shallowEq` infix function. This compares **two distinct lists** pairwise; it would map to a hypothetical `contentEqualsWith`, not `allEqualWith`. Not a match.
- `arrow/arrow-libs/fx/arrow-fx-stm/src/commonMain/kotlin/arrow/fx/stm/internal/Impl.kt:104` — `accessMap.all { (tv, entry) -> tv._value === entry.initialVal }`. Per-pair self-coherence (each element compares its own two fields), not a collection-wide invariant — `allEqualWith` semantics don't apply. Not a match.
- `intellij-community/plugins/kotlin/base/fe10/analysis/src/org/jetbrains/kotlin/idea/util/CallType.kt:444` — `variants.all { another -> another === type || ... }`. Compares each element to an *external* reference `type`. Expressible as `xs.all { it === type }`; no `allEqual*` needed.

**IEEE-754, epsilon-tolerance, `String.equals(other, ignoreCase = true)` — 0 genuine hits.** None of these predicate categories appear inside an "all elements equivalent" collection idiom in any of the surveyed repos.

### Bottom line

The use-case bar for shipping `allEqualWith` in stdlib is not met:

- The originally-cited main motivator (referential equality) shows up in `allEqualWith`-shape only in `intellij-community`'s Kotlin plugin (1 production occurrence, 1 test occurrence) across the 39 major Kotlin codebases surveyed.
- The two structural-equality hits are domain-specific equivalence checks, fully expressible today as `xs.zipWithNext().all { ... }`, with no readability benefit from a stdlib wrapper.
- The pattern that *would* be tempting (epsilon-tolerance) is exactly the one the FP-semantics doc warns against because epsilon-closeness is not transitive.

## Decision

Remove `allEqualWith()` from the final API. The `allEqual` family ships as:

```kotlin
@SinceKotlin("2.4")
@ExperimentalStdlibApi
public fun <T> Iterable<T>.allEqual(): Boolean

@SinceKotlin("2.4")
@ExperimentalStdlibApi
public inline fun <T, K> Iterable<T>.allEqualBy(selector: (T) -> K): Boolean
```

Analogously for `Sequence`, `Array<T>`, primitive arrays, and unsigned arrays.

## Why removal beats keeping or renaming (recap)

1. **Use-case bar not met.** See research above. Four genuine hits across all surveyed major Kotlin codebases (two structural-equality, two referential — the referential ones both in `intellij-community`'s Kotlin plugin), all already idiomatic without an stdlib wrapper.
2. **Misuse vector eliminated.** With no `allEqualWith` overload, users reaching for "all equal under epsilon" are forced to express it explicitly as a spread check or a hand-rolled loop, where the loss of transitivity is visible. See [the FP-semantics analysis](allEqual-floating-point-semantics-%28and-implications-for-allDistinct%29.md#why-epsilon-tolerance-comparison-doesnt-fit-allequal).
3. **Linguistic / convention concern resolved at no cost.** No equality-family `*With` overload is introduced that looks like the ordering APIs' comparator-based `*With` variants while taking an arbitrary predicate. The "with X" / "to X" reading ambiguity goes away with the API.

## What users should write instead

For the cases the hand-rolled patterns in the surveyed code already cover:

```kotlin
// Domain-specific equivalence relation (transitive). Direct replacement for the
// patterns found in EditorConfig and DataFrame:
xs.zipWithNext().all { (a, b) -> areEquivalent(a, b) }

// Referential equality (every element is the same instance).
// `allEqual*()` returns `true` for empty and singleton inputs; the forms below preserve that.

// Iterable / List (multi-pass): trivial form
fun <T> Iterable<T>.allReferentiallyEqual(): Boolean =
    firstOrNull()?.let { first -> all { it === first } } ?: true

// One-shot Sequence (single-pass): consume via iterator so the sequence is traversed only once
fun <T> Sequence<T>.allReferentiallyEqual(): Boolean {
    val iterator = iterator()
    if (!iterator.hasNext()) return true
    val first = iterator.next()
    for (item in iterator) if (item !== first) return false
    return true
}

// Array
fun <T> Array<T>.allReferentiallyEqual(): Boolean =
    size < 2 || (1..lastIndex).all { this[it] === this[0] }

// "All values close" within ε — NOT an equivalence relation; use a spread check:
(values.max() - values.min()) < eps
```

The first form covers the genuine cases observed in the wild. The second covers the originally-cited referential motivator. The third is included as a redirect: it is not what `allEqualWith` would have served well even hypothetically, but a user reaching for `allEqualWith` for "approximate equality" is asking the wrong question — see the FP-semantics doc.

## Historical context: rejected rename-to-overload alternative

Before the design meeting pivoted to removal, this document recommended renaming `allEqualWith` to an overload of `allEqual()` itself:

```kotlin
@SinceKotlin("2.4")
@ExperimentalStdlibApi
public fun <T> Iterable<T>.allEqual(): Boolean

@SinceKotlin("2.4")
@ExperimentalStdlibApi
public inline fun <T> Iterable<T>.allEqual(areEquivalent: (T, T) -> Boolean): Boolean

@SinceKotlin("2.4")
@ExperimentalStdlibApi
public inline fun <T, K> Iterable<T>.allEqualBy(selector: (T) -> K): Boolean
```

The rationale at the time:

1. **Stdlib precedent.** `count`, `single`, `singleOrNull`, `first`, `last`, `any`, `none` all have a `foo()` / `foo(predicate)` pair. The relation here is consistent: `allEqual()` ≡ `allEqual { a, b -> a == b }`, just as `count()` ≡ `count { true }`.
2. **Family preserved.** All forms live under the `allEqual*` prefix and surface together in IDE autocomplete.
3. **Mistake-proof overload.** Disambiguation would be by parameter count (0 vs 2). A 1-arg lambda — `list.allEqual { it == "x" }` — would fail to compile rather than silently miscalling.
4. **Contract in the signature.** The parameter name `areEquivalent` would make the equivalence-relation requirement visible without reading the KDoc.

Trade-off: the 2-arg lambda would have been novel for stdlib; existing `foo()` / `foo(predicate)` pairs use a 1-arg `(T) -> Boolean`.

Other rejected variants considered at the time:

| Variant                                            | Why not                                                                                                                |
|----------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| Keep `allEqualWith`                                | Both linguistic ("with" vs "to") and conventional (`*With` = `Comparator`) issues remain                               |
| `allEqualUsing` / `allEqualWhen` / `allEqualWhere` | One-off suffix for a single function; "when"/"where" carry misleading guard / SQL-filter readings                      |
| `allEquivalent`                                    | Clean and precise, but breaks the `allEqual*` family and autocomplete on the `allEqual` prefix                         |
| Overload `allEqualBy` with a 2-arg lambda          | `*By` in stdlib is for selectors `(T) -> K`; conflates selector and predicate; stdlib doesn't overload by lambda arity |

**Why this alternative was rejected.** The rename-to-overload would have solved the *naming* concern (`*With` convention preserved, no misleading "to X" reading), but it would not have addressed the deeper concerns surfaced at the design meeting:

- The misuse vector around non-equivalence-relation predicates (epsilon, etc.) would persist. The signature still admits any `(T, T) -> Boolean`; nothing enforces transitivity.
- The use-case bar still wouldn't be met. A renamed overload still adds API surface that the field evidence (above) does not justify.

The naming concern, in other words, was a symptom — the fundamental question was whether the overload was carrying its weight at all. The research says it is not.
