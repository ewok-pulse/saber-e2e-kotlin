/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.android.externalAndroidTarget

import com.android.build.api.dsl.androidLibrary
import org.gradle.api.JavaVersion
import org.gradle.api.logging.LogLevel
import org.gradle.kotlin.dsl.kotlin
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.testbase.*
import java.io.DataInputStream
import kotlin.test.assertEquals

private const val JVM_17_CLASS_MAJOR_VERSION = 61

@AndroidTestVersions(minVersion = TestVersions.AGP.AGP_813)
@AndroidGradlePluginTests
class JvmToolchainExternalAndroidTargetIT : KGPBaseTest() {

    @JdkVersions(versions = [JavaVersion.VERSION_21])
    @GradleAndroidTest
    fun `test - jvmToolchain is applied to androidLibrary and androidHostTest compilation`(
        gradleVersion: GradleVersion,
        androidVersion: String,
        jdkVersion: JdkVersions.ProvidedJdk,
    ) {
        project(
            "empty",
            gradleVersion = gradleVersion,
            buildOptions = defaultBuildOptions.copy(androidVersion = androidVersion),
            buildJdk = jdkVersion.location,
        ) {
            plugins {
                kotlin("multiplatform")
                id("com.android.kotlin.multiplatform.library")
            }
            buildScriptInjection {
                kotlinMultiplatform.apply {
                    androidLibrary {
                        compileSdk = 34
                        namespace = "org.jetbrains.sample.multitarget"
                        withHostTest {}
                    }
                    jvmToolchain(17)

                    sourceSets.getByName("androidMain").compileSource(
                        """
                        package sample

                        import android.content.Context

                        class AndroidMain(val context: Context)
                        """.trimIndent()
                    )
                    sourceSets.getByName("androidHostTest").compileSource(
                        """
                        package sample

                        class HostTestProbe
                        """.trimIndent()
                    )
                }
            }

            build(":compileAndroidMain", ":compileAndroidHostTest") {
                assertTasksExecuted(":compileAndroidMain", ":compileAndroidHostTest")
                assertCompilerArgument(":compileAndroidMain", "-jvm-target 17", logLevel = LogLevel.INFO)
                assertCompilerArgument(":compileAndroidHostTest", "-jvm-target 17", logLevel = LogLevel.INFO)
                assertEquals(
                    JVM_17_CLASS_MAJOR_VERSION,
                    readClassFileMajorVersion("build/classes/kotlin/android/main/sample/AndroidMain.class")
                )
            }
        }
    }

    @JdkVersions(versions = [JavaVersion.VERSION_21])
    @GradleAndroidTest
    fun `test - jvmToolchain is applied to androidLibrary withJava Kotlin and Java compilations`(
        gradleVersion: GradleVersion,
        androidVersion: String,
        jdkVersion: JdkVersions.ProvidedJdk,
    ) {
        project(
            "empty",
            gradleVersion = gradleVersion,
            buildOptions = defaultBuildOptions.copy(androidVersion = androidVersion),
            buildJdk = jdkVersion.location,
        ) {
            plugins {
                kotlin("multiplatform")
                id("com.android.kotlin.multiplatform.library")
            }
            buildScriptInjection {
                kotlinMultiplatform.apply {
                    androidLibrary {
                        compileSdk = 34
                        namespace = "org.jetbrains.sample.withjava"
                        withJava()
                    }
                    jvmToolchain(17)
                    printKotlinTaskJavaVersion("compileAndroidMain")
                    printJavaCompileTaskJavaVersion("compileAndroidMainJavaWithJavac")

                    sourceSets.getByName("androidMain").compileSource(
                        """
                        package sample

                        class KotlinUsesJava {
                            fun ping(): String = JavaPeer().value()
                        }
                        """.trimIndent()
                    )
                }
            }

            javaSourcesDir("androidMain").resolve("sample/JavaPeer.java").apply {
                parent.toFile().mkdirs()
                toFile().writeText(
                    """
                    package sample;

                    public class JavaPeer {
                        public String value() {
                            return "java";
                        }
                    }
                    """.trimIndent()
                )
            }

            build(":compileAndroidMain", ":compileAndroidMainJavaWithJavac") {
                assertTasksExecuted(":compileAndroidMain", ":compileAndroidMainJavaWithJavac")
                assertOutputContains("[KOTLIN TASK] compileAndroidMain javaVersion: 17")
                assertOutputContains("[JAVAC TASK] compileAndroidMainJavaWithJavac javaVersion: 17")
            }
        }
    }

    @JdkVersions(versions = [JavaVersion.VERSION_21])
    @GradleAndroidTest
    fun `test - jvmToolchain and jvmTarget are applied independently for androidLibrary compilation`(
        gradleVersion: GradleVersion,
        androidVersion: String,
        jdkVersion: JdkVersions.ProvidedJdk,
    ) {
        project(
            "empty",
            gradleVersion = gradleVersion,
            buildOptions = defaultBuildOptions.copy(androidVersion = androidVersion),
            buildJdk = jdkVersion.location,
        ) {
            plugins {
                kotlin("multiplatform")
                id("com.android.kotlin.multiplatform.library")
            }
            buildScriptInjection {
                kotlinMultiplatform.apply {
                    androidLibrary {
                        compileSdk = 34
                        namespace = "com.example.lib"
                        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
                    }
                    jvmToolchain(17)

                    sourceSets.getByName("androidMain").compileSource(
                        """
                        package com.example.lib

                        import android.content.Context

                        class AndroidMain(val context: Context) {
                            fun increment(): Int = 1
                        }
                        """.trimIndent()
                    )
                }
            }

            build(":compileAndroidMain") {
                assertTasksExecuted(":compileAndroidMain")
                assertCompilerArgument(":compileAndroidMain", "-jvm-target 11", logLevel = LogLevel.INFO)
            }
        }
    }

    private fun TestProject.readClassFileMajorVersion(classFilePath: String): Int {
        val classFile = projectPath.resolve(classFilePath).toFile()
        check(classFile.exists()) { "Class file does not exist: $classFilePath" }

        return DataInputStream(classFile.inputStream().buffered()).use { input ->
            val magic = input.readInt()
            check(magic == 0xCAFEBABE.toInt()) { "Invalid class file header for $classFilePath" }
            input.readUnsignedShort()
            input.readUnsignedShort()
        }
    }
}

private fun GradleProjectBuildScriptInjectionContext.printKotlinTaskJavaVersion(vararg taskNames: String) {
    project.tasks
        .withType(org.jetbrains.kotlin.gradle.tasks.UsesKotlinJavaToolchain::class.java)
        .matching { it.name in taskNames }
        .configureEach { task ->
            task.doFirst {
                println("[KOTLIN TASK] ${task.name} javaVersion: ${task.kotlinJavaToolchain.javaVersion.get()}")
            }
        }
}

private fun GradleProjectBuildScriptInjectionContext.printJavaCompileTaskJavaVersion(taskName: String) {
    project.tasks
        .withType(org.gradle.api.tasks.compile.JavaCompile::class.java)
        .matching { it.name == taskName }
        .configureEach { task ->
            task.doFirst {
                println("[JAVAC TASK] ${task.name} javaVersion: ${task.javaCompiler.get().metadata.languageVersion.asInt()}")
            }
        }
}
