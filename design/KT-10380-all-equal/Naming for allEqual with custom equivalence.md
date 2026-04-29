# Naming for `allEqual` with custom equivalence

## The Problem

The custom-predicate variant of `allEqual` is currently named `allEqualWith`. Filipp's review on [PR #5941](https://github.com/JetBrains/kotlin/pull/5941):

> with does not work well here as the "to be equal with"'s meaning is not far from "to be equal to".

"All equal with X" reads almost like "all equal **to** X" rather than "all equal **under predicate** X". `*With` works for `Comparator`-based APIs (`sortedWith`, `maxWith`, `minWith`, `maxOfWith`, `minOfWith`, `isSortedWith`) — there "sorted with X" has no competing reading "sorted to X". `allEqualWith` would also be the only `*With` in stdlib not taking a `Comparator<in T>`, breaking the established convention. The linguistic and conventional concerns reinforce each other.

The API has not shipped, so renaming is free — no deprecation cycle.

## Recommendation

Make the custom-predicate variant an overload of `allEqual` instead of `allEqualWith`:

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

Analogously for `Sequence`, `Array<T>`, primitive arrays, and unsigned arrays.

Call sites read naturally:

```kotlin
values.allEqual()                    // structural ==
values.allEqual { a, b -> a === b }  // referential
arr.allEqual { a, b -> a == b }      // IEEE 754 opt-in for Double/Float
strs.allEqual { a, b -> a.equals(b, ignoreCase = true) }
```

The `IEEE 754` case for `Double`/`Float` is the subject of a separate [floating-point semantics doc](allEqual%20floating-point%20semantics%20%28and%20implications%20for%20allDistinct%29.md).

## Why overload `allEqual`

1. **Stdlib precedent.** `count`, `single`, `singleOrNull`, `first`, `last`, `any`, `none` all have a `foo()` / `foo(predicate)` pair. The relation here is consistent: `allEqual()` ≡ `allEqual { a, b -> a == b }`, just as `count()` ≡ `count { true }`.
2. **Family preserved.** All three forms live under the `allEqual*` prefix and surface together in IDE autocomplete.
3. **Mistake-proof overload.** Disambiguation is by parameter count (0 vs 2). A 1-arg lambda — `list.allEqual { it == "x" }` — fails to compile rather than silently miscalling.
4. **Contract in the signature.** The parameter name `areEquivalent` makes the equivalence-relation requirement visible without reading the KDoc.

## Trade-off

The 2-arg lambda is novel for stdlib — existing `foo()` / `foo(predicate)` pairs use a 1-arg `(T) -> Boolean`. This belongs to the capability itself, not the naming: any way to expose custom equivalence in stdlib requires a 2-arg lambda.

## Alternatives

| Variant                                            | Why not                                                                                                                |
|----------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| Keep `allEqualWith`                                | Both linguistic ("with" vs "to") and conventional (`*With` = `Comparator`) issues remain                               |
| Drop the predicate variant entirely                | Loses IEEE 754 / referential / case-insensitive escape hatches; forces users to `windowed(2).all { ... }`              |
| `allEqualUsing` / `allEqualWhen` / `allEqualWhere` | One-off suffix for a single function; "when"/"where" carry misleading guard / SQL-filter readings                      |
| `allEquivalent`                                    | Clean and precise, but breaks the `allEqual*` family and autocomplete on the `allEqual` prefix                         |
| Overload `allEqualBy` with a 2-arg lambda          | `*By` in stdlib is for selectors `(T) -> K`; conflates selector and predicate; stdlib doesn't overload by lambda arity |
