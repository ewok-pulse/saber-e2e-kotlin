/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.compiler.nativeimage

import com.intellij.testFramework.TestDataFile
import org.jetbrains.kotlin.codegen.forTestCompile.TestCompilePaths
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.directives.AdditionalFilesDirectives
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.ConfigurationDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.directives.LanguageSettingsDirectives
import org.jetbrains.kotlin.test.directives.model.ComposedDirectivesContainer
import org.jetbrains.kotlin.test.directives.model.Directive
import org.jetbrains.kotlin.test.directives.model.RegisteredDirectives
import org.jetbrains.kotlin.test.directives.model.ValueDirective
import org.jetbrains.kotlin.test.services.JUnit5Assertions
import org.jetbrains.kotlin.test.services.impl.RegisteredDirectivesParser
import org.jetbrains.kotlin.test.util.KtTestUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap

abstract class AbstractNativeImageBlackBoxCodegenTest {
    @TempDir
    lateinit var workingDir: File

    private val nativeImageDist: File by lazy {
        val raw = System.getProperty(TestCompilePaths.KOTLIN_NATIVE_IMAGE_DIST_PATH)
            ?: error("System property '${TestCompilePaths.KOTLIN_NATIVE_IMAGE_DIST_PATH}' is not set")
        File(raw).also { require(it.isDirectory) { "native-image dist not found: $it" } }
    }

    private val nativeImageExecutable: File by lazy {
        val launcher = when {
            System.getProperty("os.name").startsWith("windows", ignoreCase = true) -> "kotlinc-native-image.bat"
            else -> "kotlinc-native-image.sh"
        }
        nativeImageDist.resolve("bin").resolve(launcher)
    }

    private val javaHome: String = System.getProperty("java.home")

    private val compilationClasspath: List<File> by lazy {
        listOf(
            requireFile(TestCompilePaths.KOTLIN_FULL_STDLIB_PATH),
            requireFile(TestCompilePaths.KOTLIN_TEST_JAR_PATH),
        )
    }

    private val reflectClasspath: List<File> by lazy {
        listOf(requireFile(TestCompilePaths.KOTLIN_REFLECT_JAR_PATH))
    }

    private val mockJdkRtJar: File by lazy {
        requireFile(TestCompilePaths.KOTLIN_MOCKJDK_RUNTIME_PATH)
    }

    private fun requireFile(propName: String): File {
        val raw = System.getProperty(propName) ?: error("System property '$propName' is not set")
        return raw.split(File.pathSeparator).map(::File).firstOrNull { it.exists() }
            ?: error("No existing file in '$propName' = '$raw'")
    }

    open fun runTest(@TestDataFile filePath: String) {
        val testFile = File(KtTestUtil.getHomeDirectory(), filePath)
        val source = testFile.readText()
        val directives = parseDirectives(source)

        val skipReason = shouldSkip(source, directives)
        assumeTrue(skipReason == null) { "skipped: $skipReason" }

        val withReflect = JvmEnvironmentConfigurationDirectives.WITH_REFLECT in directives
        val withFullJdk = JvmEnvironmentConfigurationDirectives.FULL_JDK in directives

        val boxFile = File(workingDir, "box.kt").apply { writeText(prepareSource(source)) }
        val outDir = File(workingDir, "ni-out").apply { mkdirs() }

        val classpath = compilationClasspath +
                (if (withReflect) reflectClasspath else emptyList()) +
                (if (!withFullJdk) listOf(mockJdkRtJar) else emptyList())

        val (compileOut, exit) = runNativeImageCompiler(
            *buildCompileArgs(boxFile, outDir, directives, withFullJdk).toTypedArray(),
            classpath = classpath,
        )
        assertEquals(0, exit, "native-image compilation failed:\n$compileOut")

        val result = invokeBox(outDir, boxClassName(source), withReflect)
        assertEquals("OK", result, "box() != 'OK'")
    }

    private fun runNativeImageCompiler(
        vararg arguments: String,
        classpath: List<File>,
    ): Pair<Int, String> {
        val cmd = listOf(
            nativeImageExecutable.absolutePath,
            "-cp", classpath.joinToString(File.pathSeparator)
        ) + arguments
        val builder = ProcessBuilder(cmd).directory(workingDir).redirectErrorStream(true)
        builder.environment().putIfAbsent("JAVA_HOME", javaHome)
        val proc = builder.start()
        val out = proc.inputStream.reader().use { it.readText() }
        return proc.waitFor() to out
    }

    private fun buildCompileArgs(
        boxFile: File,
        outDir: File,
        directives: RegisteredDirectives,
        withFullJdk: Boolean,
    ): List<String> = buildList {
        directives[LanguageSettingsDirectives.LANGUAGE].forEach { add("-XXLanguage:$it") }
        addAll(directives.valueDirectiveFlags())
        directives[LanguageSettingsDirectives.OPT_IN].forEach { add("-opt-in=$it") }
        if (!withFullJdk) add("-no-jdk")
        for ((directive, path) in HELPER_FILES) {
            if (directive in directives) {
                add(File(diagnosticsHelpersRoot, path).absolutePath)
            }
        }
        add(boxFile.absolutePath)
        add("-d")
        add(outDir.absolutePath)
        add("-Dkotlinc.test.allow.testonly.language.features=true")
    }

    private fun RegisteredDirectives.valueDirectiveFlags(): List<String> = listOfNotNull(
        renderValueFlag(LanguageSettingsDirectives.RETURN_VALUE_CHECKER_MODE, "-Xreturn-value-checker") { it.state },
        renderValueFlag(JvmEnvironmentConfigurationDirectives.ASSERTIONS_MODE, "-Xassertions") { it.description },
        renderValueFlag(JvmEnvironmentConfigurationDirectives.LAMBDAS, "-Xlambdas") { it.description },
        renderValueFlag(JvmEnvironmentConfigurationDirectives.SAM_CONVERSIONS, "-Xsam-conversions") { it.description },
    )

    private inline fun <T : Any> RegisteredDirectives.renderValueFlag(
        directive: ValueDirective<T>,
        flagPrefix: String,
        render: (T) -> String,
    ): String? = this[directive].singleOrNull()?.let { "$flagPrefix=${render(it)}" }

    private fun invokeBox(classDir: File, boxClass: String, withReflect: Boolean): String {
        URLClassLoader(arrayOf(classDir.toURI().toURL()), sharedRuntimeLoader(withReflect)).use { loader ->
            val method = loader.loadClass(boxClass).getMethod("box")
            val thread = Thread.currentThread()
            val previous = thread.contextClassLoader
            thread.contextClassLoader = loader
            return try {
                method.invoke(null) as String
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            } finally {
                thread.contextClassLoader = previous
            }
        }
    }

    private fun sharedRuntimeLoader(withReflect: Boolean): URLClassLoader =
        sharedRuntimeLoaders.computeIfAbsent(withReflect) { needsReflect ->
            val cp = compilationClasspath + if (needsReflect) reflectClasspath else emptyList()
            URLClassLoader(
                cp.map { it.toURI().toURL() }.toTypedArray(),
                ClassLoader.getSystemClassLoader().parent,
            )
        }

    companion object {
        private val BACKEND = TargetBackend.JVM_IR

        private val sharedRuntimeLoaders = ConcurrentHashMap<Boolean, URLClassLoader>()

        private val diagnosticsHelpersRoot: File =
            File(KtTestUtil.getHomeDirectory(), "compiler/testData/diagnostics/helpers")

        private val HELPER_FILES: List<Pair<Directive, String>> = listOf(
            AdditionalFilesDirectives.CHECK_TYPE to "types/checkType.kt",
            AdditionalFilesDirectives.CHECK_TYPE_WITH_EXACT to "types/checkTypeWithExact.kt",
            AdditionalFilesDirectives.INFERENCE_HELPERS to "inference/inferenceUtils.kt",
            AdditionalFilesDirectives.WITH_COROUTINES to "coroutines/CoroutineHelpers.kt",
            AdditionalFilesDirectives.CHECK_STATE_MACHINE to "coroutines/StateMachineChecker.kt",
            AdditionalFilesDirectives.CHECK_TAIL_CALL_OPTIMIZATION to "coroutines/TailCallOptimizationChecker.kt",
        )

        private val DIRECTIVES_CONTAINER = ComposedDirectivesContainer(
            LanguageSettingsDirectives,
            ConfigurationDirectives,
            CodegenTestDirectives,
            JvmEnvironmentConfigurationDirectives,
            AdditionalFilesDirectives,
        )

        private const val INDIVIDUAL_DIAGNOSTIC = """(\w+;)?(\w+:)?(\w+)(\{[\w;]+})?(?:\(((?:".*?")(?:,\s*".*?")*)\))?"""
        private val DIAGNOSTIC_MARKUP = Regex("""(<!$INDIVIDUAL_DIAGNOSTIC(,\s*$INDIVIDUAL_DIAGNOSTIC)*!>)|(<!>)""")
        private val MULTI_FILE_MARKER = Regex("""(?m)^// FILE:""")
        private val HELPERS_IMPORT = Regex("""(?m)^import helpers\.""")

        private fun prepareSource(source: String): String =
            DIAGNOSTIC_MARKUP.replace(source.replace("OPTIONAL_JVM_INLINE_ANNOTATION", "@JvmInline"), "")

        private fun parseDirectives(source: String): RegisteredDirectives {
            val parser = RegisteredDirectivesParser(DIRECTIVES_CONTAINER, JUnit5Assertions)
            for (line in source.lineSequence()) {
                if (line.startsWith("//")) parser.parse(line)
            }
            return parser.build()
        }

        private fun shouldSkip(source: String, directives: RegisteredDirectives): String? = when {
            MULTI_FILE_MARKER.containsMatchIn(source) -> "multi-file (// FILE:) tests are not supported"
            HELPERS_IMPORT.containsMatchIn(source) -> "tests importing helpers.* are not supported"
            "+MultiPlatformProjects" in directives[LanguageSettingsDirectives.LANGUAGE] -> "multiplatform projects are not supported"
            isBackendIgnored(directives) -> "ignored on $BACKEND via directive"
            else -> null
        }

        private fun isBackendIgnored(directives: RegisteredDirectives): Boolean {
            fun ValueDirective<TargetBackend>.matches() =
                directives[this].any { ignoredBackend ->
                    TargetBackend.ANY.isTransitivelyCompatibleWith(ignoredBackend) || BACKEND.isTransitivelyCompatibleWith(ignoredBackend)
                }
            if (CodegenTestDirectives.IGNORE_BACKEND.matches()) return true
            if (CodegenTestDirectives.IGNORE_BACKEND_K2.matches()) return true
            if (directives[ConfigurationDirectives.DONT_TARGET_EXACT_BACKEND].any { it == BACKEND }) return true
            val target = directives[ConfigurationDirectives.TARGET_BACKEND]
            return target.isNotEmpty() &&
                    TargetBackend.ANY !in target &&
                    target.none { BACKEND.isTransitivelyCompatibleWith(it) }
        }

        private const val BOX_FILE_CLASS = "BoxKt"

        private fun boxClassName(source: String): String {
            val pkg = source.lineSequence()
                .firstOrNull { it.trimStart().startsWith("package ") }
                ?.substringAfter("package ")
                ?.trim()
            return if (pkg.isNullOrBlank()) BOX_FILE_CLASS else "$pkg.$BOX_FILE_CLASS"
        }
    }
}
