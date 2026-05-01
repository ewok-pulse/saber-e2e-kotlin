# Kotlin Compiler Embeddable Native Image distribution

A GraalVM-compiled native image of `kotlin-compiler-embeddable` that runs as a
standalone executable. Behaves like the regular `kotlinc` but with some limitations &mdash;
mainly, no support for plugins. Produces a native image distribution which is similar
in its structure to the standard `kotlinc` distribution.

## Distribution layout

The distribution is produced in [prepare/compiler-native-image/build/dist/kotlinc-native-image](build/dist/kotlinc-native-image) directory and
has the following structure:

* `bin/kotlinc-native-image[.exe]` &mdash; native binary;
* `bin/kotlinc-native-image.[sh|bat]` &mdash; launcher wrappers;
* `lib/` &mdash; runtime classpath jars;
* `license/` &mdash; license information.

## Building

Produce a native image binary:
```
./gradlew :kotlin-compiler-native-image:kotlincNativeImage
```

The task requires a GraalVM JDK 25 toolchain, which is automatically resolved via Gradle.

Produce a native image distribution:
```
./gradlew :kotlin-compiler-native-image:kotlincNativeImageDist
```


## Running

Similar to JVM `kotlinc`, the native image binary requires some configuration. To simplify
the user experience, the distribution also provides `kotlinc-native-image.[sh|bat]` wrapper scripts
that do all the necessary configuration of the binary. Namely, they configure:

* `-Djava.home` &mdash; resolved to the `JAVA_HOME` environment variable;
* `-Dkotlin.home` &mdash; resolved to the location of the distribution root.

These wrapper scripts may be used as a drop-in replacement for the JVM `kotlinc`:

```bash
dist/kotlinc-native-image/bin/kotlinc-native-image.sh path/to/Foo.kt -d out/
```

## Tests

Run the test suite with:
```
./gradlew :kotlin-compiler-native-image:nativeImageBoxTest
```

The test suite mirrors the compiler's JVM box-codegen tests: it is
generated from the same test data under [compiler/testData/codegen/box](../../compiler/testData/codegen/box)
and behaves similarly to the JVM tests. The goal is to ensure that the behavior of the native image
compiler is the same as its JVM counterpart.


For each test data file the [AbstractNativeImageBlackBoxCodegenTest](testFixtures/kotlin/org/jetbrains/kotlin/compiler/nativeimage/AbstractNativeImageBlackBoxCodegenTest.kt) runner:

1. parses the standard directives from the source header;
2. writes the source to a temporary `box.kt`;
3. invokes the built `bin/kotlinc-native-image.[sh|bat]` launcher as a separate
   process to compile it;
4. loads the produced classes via a `URLClassLoader` and asserts that `box()`
   returns `"OK"`.

The current setup is rather ad-hoc:

* tests are excluded from the default `compilerTest` aggregate and run only via
  the dedicated `nativeImageBoxTest` task; the main goal is to not intervene with
  pre-existing workflows while still ensuring that the native image works correctly;
* test setup is quite sensitive to the changes in the "main" box tests, as it currently
  performs its own directives parsing and handling;
* `JVM_IR` is treated as the target backend, so a test is skipped if it contains 
  `// IGNORE_BACKEND: JVM_IR` directive (or its equivalent);
* multifile tests are not supported;
* tests that use helper functions (`helpers.*`) are skipped.


## Reachability metadata

GraalVM reachability metadata is stored in [prepare/compiler-native-image/resources/META-INF/native-image](resources/META-INF/native-image).
Initial metadata was collected via the [GraalVM Tracing Agent](https://www.graalvm.org/latest/reference-manual/native-image/metadata/AutomaticMetadataCollection/)
and is currently updated manually when necessary.
