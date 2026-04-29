# Context

I am working on [KT-10380 — `allEqual` function for `Iterable`](https://youtrack.jetbrains.com/issue/KT-10380), adding an `allEqual`-family of extension functions to the Kotlin standard library for `Iterable`, `Sequence`, object arrays, primitive arrays, and unsigned arrays.

## Implementation already in place

**Extension-method generators** in `libraries/tools/kotlin-stdlib-gen/src/templates/Aggregates.kt` — three template definitions:
- `f_allEqual`
- `f_allEqualBy`
- `f_allEqualWith`

**Generated extension methods** (output of the templates above) for `Iterable`, `Sequence`, and arrays:
- `libraries/stdlib/common/src/generated/_Collections.kt`
- `libraries/stdlib/common/src/generated/_Sequences.kt`
- `libraries/stdlib/common/src/generated/_Arrays.kt`
- `libraries/stdlib/common/src/generated/_UArrays.kt`

**Samples**:
- Generator: `libraries/tools/kotlin-stdlib-gen/src/generators/test/AllEqualSampleGenerator.kt`
- Generated samples: `libraries/stdlib/samples/test/samples/generated/allequal`

**Unit tests**:
- Generator: `libraries/tools/kotlin-stdlib-gen/src/generators/test/AllEqualTestGenerator.kt`
- Generated tests: `libraries/stdlib/test/generated/allequal`

## How to regenerate and run tests

Regenerate generated extension files (output of the `Aggregates.kt` templates):

```bash
./gradlew :tools:kotlin-stdlib-gen:run
```

Regenerate tests and samples:

```bash
./gradlew :tools:kotlin-stdlib-gen:generateStdlibTests
```

**Note**: this command also regenerates files under `libraries/stdlib/test/generated/minmax/` via `MinMaxTestGenerator` (the only change there is a copyright-year bump unrelated to KT-10380). Revert any `MinMax*` files immediately after regeneration.

Run all `allEqual` tests (every class under `libraries/stdlib/test/generated/allequal/`):

```bash
./gradlew :kotlin-stdlib:jvmTest --tests "test.generated.allequal.*"
```

Run all `allEqual` samples (every class under `libraries/stdlib/samples/test/samples/generated/allequal/`):

```bash
./gradlew :kotlin-stdlib:samples:test --tests "samples.generated.allequal.*"
```

## Pull request

https://github.com/JetBrains/kotlin/pull/5941

## Related issues (read for broader context)

There is a complementary task for adding `allDistinct()` and `allDistinctBy {}`:

- [KT-30270 — Provide `allDistinct()` and `allDistinctBy{}` for iterables](https://youtrack.jetbrains.com/issue/KT-30270)
- [KT-85976 — Add `allDistinct` and `allDistinctBy` extensions to the standard library](https://youtrack.jetbrains.com/issue/KT-85976) — a duplicate of KT-30270 with a more detailed description.

These tasks must be considered jointly with the current work: naming and semantics decisions made for `allEqual*` will be inherited by `allDistinct*`, so reviewing them is essential before finalizing the API.
