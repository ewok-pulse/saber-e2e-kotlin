# Removal steps

← [`Removing allEqualWith from the allEqual family`](Removing-allEqualWith-from-the-allEqual-family.md) (rationale)

Operational steps for removing `allEqualWith()` from the `allEqual` family.

The implementation lives entirely in the kotlin-stdlib-gen templates and in generated artifacts. Source-of-truth changes:

- [ ] `libraries/tools/kotlin-stdlib-gen/src/templates/Aggregates.kt` — delete the `f_allEqualWith` block (lines 337–385 at time of writing).
- [ ] `libraries/tools/kotlin-stdlib-gen/src/generators/test/AllEqualSampleGenerator.kt` — remove `writeAllEqualWithSample` and its call from `generate`; remove the `predicateSample` and `supportsReferentialEquality` locals; drop the `|| predicateSample.needsAbsImport` clause from the `writeHeader(...)` call site in `generate(...)` (line 28). The `writeHeader(className: String, needsAbsImport: Boolean)` signature itself is unchanged.
- [ ] `libraries/tools/kotlin-stdlib-gen/src/generators/test/AllEqualTestGenerator.kt` — remove `writeAllEqualWithTest` and its call; remove the `firstVsEach` and `supportsReferentialEquality` locals.
- [ ] `libraries/tools/kotlin-stdlib-gen/src/generators/test/AllEqualUtils.kt` — remove `allEqualWithPredicateFor`, `data class PredicateSample`, `firstVsEachCaseFor`, `data class FirstVsEachCase`. `AllEqualTypeConfig` and its `sampleNeedsAbsImport` field both stay: `sampleNeedsAbsImport` is still set by `signedIntConfig` / `floatingPointConfig` and consumed by `allEqualBy` samples that include `abs(it)` selector assertions.

Then regenerate (no manual edits to generated files):

- [ ] `./gradlew :tools:kotlin-stdlib-gen:run` — regenerates `_Collections.kt`, `_Sequences.kt`, `_Arrays.kt`, `_UArrays.kt` under `libraries/stdlib/common/src/generated/`. Removes the `allEqualWith` extensions from each.
- [ ] `./gradlew :tools:kotlin-stdlib-gen:generateStdlibTests` — regenerates the 15 `AllEqual*Samples.kt` files under `libraries/stdlib/samples/test/samples/generated/allequal/` and the 15 `AllEqual*Test.kt` files under `libraries/stdlib/test/generated/allequal/`. Drops `allEqualWith` samples and tests from each. **Revert any incidental `MinMax*` changes immediately after this regen step** (see the note in [`Context.md`](Context.md)).

Other small doc updates:

- [ ] [`Context.md`](Context.md) — drop the `f_allEqualWith` bullet from the "template definitions" list.
- [x] [`allEqual` floating-point semantics doc](allEqual-floating-point-semantics-%28and-implications-for-allDistinct%29.md) — already commits to removal in its **Practical recommendation** and forward-links to [the rationale doc](Removing-allEqualWith-from-the-allEqual-family.md) for the use-case investigation; no further changes needed.

API stability: `allEqual*` is `@ExperimentalStdlibApi`, but the public API reference dumps already contain the generated declarations. Do not edit them by hand as an intermediate step. After the implementation, generated sources, samples, and tests have been updated and verified, regenerate all public API dumps as the final step, including Native:

```bash
./gradlew :tools:binary-compatibility-validator:test -Doverwrite.output=true -Pkotlin.native.enabled=true
```

This updates the JVM dump under `libraries/tools/binary-compatibility-validator/reference-public-api/` and the merged JS / Wasm / Native KLIB dump under `libraries/tools/binary-compatibility-validator/klib-public-api/`. Review the resulting diff and make sure it only removes the `allEqualWith` entries while keeping `allEqual` and `allEqualBy`.

Verify after regen:

```bash
# Before the final API-dump refresh, stdlib sources should contain only design-doc mentions:
rg -n "allEqualWith" libraries/stdlib

# All allEqual tests pass:
./gradlew :kotlin-stdlib:jvmTest --tests "test.generated.allequal.*"

# All allEqual samples pass:
./gradlew :kotlin-stdlib:samples:test --tests "samples.generated.allequal.*"

# Final step: regenerate all public API dumps, including Native:
./gradlew :tools:binary-compatibility-validator:test -Doverwrite.output=true -Pkotlin.native.enabled=true
```
