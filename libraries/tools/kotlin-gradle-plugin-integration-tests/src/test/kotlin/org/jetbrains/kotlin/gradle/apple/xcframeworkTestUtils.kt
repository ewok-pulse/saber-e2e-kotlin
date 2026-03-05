/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.apple

import org.jetbrains.kotlin.gradle.util.runProcess
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

const val DEFAULT_IOS_SDK_VERSION = "15.0"
private data class AppleSdkSlice(
    val sdk: String,
    val arch: String = "arm64",
    val baseName: String,
    val targetTriple: String
)

private val iosFrameworkSlices = listOf(
    AppleSdkSlice(
        sdk = "iphoneos",
        baseName = "arm64-apple-ios",
        targetTriple = "arm64-apple-ios$DEFAULT_IOS_SDK_VERSION",
    ),
    AppleSdkSlice(
        sdk = "iphonesimulator",
        baseName = "arm64-apple-ios-simulator",
        targetTriple = "arm64-apple-ios$DEFAULT_IOS_SDK_VERSION-simulator",
    ),
)

enum class XcframeworkLinkage {
    STATIC,
    DYNAMIC,
}

/**
 * Library will pack .a (static) binary to the xcframework, a framework will pack .framework binary to the xcframework
 */
enum class XcframeworkBuildType {
    LIBRARY,
    FRAMEWORK,
}

// region Code snippet generators for test sources

internal fun swiftSourceContent(): String = """
    import Foundation

    @objc public class LocalHelper: NSObject {
        @objc public static func greeting() -> String {
            return "Hello from LocalHelper"
        }
    }
""".trimIndent()

/**
 * Generates module.modulemap content for a framework module (non-framework style).
 */
internal fun moduleMapContent(moduleName: String, headerName: String): String = """
    module $moduleName {
        header "$headerName"
        export *
    }
""".trimIndent()

/**
 * Generates module.modulemap content for a framework module.
 */
private fun frameworkModuleMapContent(moduleName: String): String = """
    framework module $moduleName {
        umbrella header "$moduleName.h"
        export *
        module * { export * }
    }
""".trimIndent()

/**
 * Generates Info.plist content for a framework bundle.
 */
private fun frameworkInfoPlistContent(frameworkName: String): String = """
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
    <dict>
        <key>CFBundleDevelopmentRegion</key>
        <string>en</string>
        <key>CFBundleExecutable</key>
        <string>$frameworkName</string>
        <key>CFBundleIdentifier</key>
        <string>com.test.$frameworkName</string>
        <key>CFBundleInfoDictionaryVersion</key>
        <string>6.0</string>
        <key>CFBundleName</key>
        <string>$frameworkName</string>
        <key>CFBundlePackageType</key>
        <string>FMWK</string>
        <key>CFBundleShortVersionString</key>
        <string>1.0</string>
        <key>CFBundleVersion</key>
        <string>1</string>
        <key>MinimumOSVersion</key>
        <string>${"15.0"}</string>
    </dict>
    </plist>
""".trimIndent()

// endregion

// region XCFramework creation utilities

fun createStubSwiftXCFramework(
    outputDir: Path,
    frameworkName: String,
    linkage: XcframeworkLinkage,
    buildType: XcframeworkBuildType,
): Path {
    val tempDir = createTempFrameworkBuildDir(outputDir, "${frameworkName}_swift_temp")

    // Create Swift source file with @objc annotations for Objective-C/Kotlin interop
    val swiftSource = tempDir.resolve("$frameworkName.swift")
    swiftSource.writeText(swiftSourceContent())

    return when (buildType) {
        XcframeworkBuildType.FRAMEWORK -> {
            val frameworks = iosFrameworkSlices.map { slice ->
                buildSwiftFramework(
                    tempDir = tempDir,
                    swiftSource = swiftSource,
                    frameworkName = frameworkName,
                    slice = slice,
                    linkage = linkage,
                )
            }
            createXCFramework(outputDir, frameworkName, tempDir, frameworks = frameworks)
        }
        XcframeworkBuildType.LIBRARY -> {
            if (linkage == XcframeworkLinkage.DYNAMIC) {
                error("Dynamic library is not supported for Swift static library in test infrastructure")
            }
            val libraries = iosFrameworkSlices.map { slice ->
                buildSwiftStaticLibrary(
                    tempDir = tempDir,
                    swiftSource = swiftSource,
                    frameworkName = frameworkName,
                    slice = slice,
                )
            }
            createXCFramework(outputDir, frameworkName, tempDir, libraries = libraries)
        }
    }
}

private fun buildSwiftFramework(
    tempDir: Path,
    swiftSource: Path,
    frameworkName: String,
    slice: AppleSdkSlice,
    linkage: XcframeworkLinkage,
): Path {
    val frameworkDir = tempDir.resolve("${slice.sdk}-${slice.arch}/$frameworkName.framework")
    val headersDir = frameworkDir.resolve("Headers")
    val modulesDir = frameworkDir.resolve("Modules")
    createFrameworkDirectories(frameworkDir, headersDir, modulesDir)
    val swiftModuleDir = modulesDir.resolve("$frameworkName.swiftmodule").also(Path::createDirectories)

    // Compile Swift source to dynamic library with generated ObjC header
    val objcHeaderPath = headersDir.resolve("$frameworkName-Swift.h")
    val command = buildList {
        addAll(
            listOf(
                "xcrun", "--sdk", slice.sdk, "swiftc",
                "-emit-library",
            )
        )
        if (linkage == XcframeworkLinkage.STATIC) {
            add("-static")
        }
        addAll(
            listOf(
                "-enable-library-evolution",
                "-emit-module",
                "-emit-module-path", swiftModuleDir.resolve("${slice.baseName}.swiftmodule").absolutePathString(),
                "-emit-module-interface-path", swiftModuleDir.resolve("${slice.baseName}.swiftinterface").absolutePathString(),
                "-emit-objc-header",
                "-emit-objc-header-path", objcHeaderPath.absolutePathString(),
                "-module-name", frameworkName,
                "-target", slice.targetTriple,
            )
        )
        if (linkage == XcframeworkLinkage.DYNAMIC) {
            addAll(
                listOf(
                    "-Xlinker", "-install_name", "-Xlinker", "@rpath/$frameworkName.framework/$frameworkName",
                )
            )
        }
        addAll(
            listOf(
                "-o", frameworkDir.resolve(frameworkName).absolutePathString(),
                swiftSource.absolutePathString(),
            )
        )
    }
    runProcessOrFail(
        command = command,
        workingDir = tempDir,
        errorMessage = "Failed to compile Swift $linkage framework for ${slice.sdk}-${slice.arch}",
    )

    // Create umbrella header that includes the generated Swift header
    headersDir.resolve("$frameworkName.h").writeText(
        """
        #import <Foundation/Foundation.h>
        #import <$frameworkName/$frameworkName-Swift.h>
        """.trimIndent()
    )

    modulesDir.resolve("module.modulemap").writeText(frameworkModuleMapContent(frameworkName))
    frameworkDir.resolve("Info.plist").writeText(frameworkInfoPlistContent(frameworkName))

    return frameworkDir
}

private fun buildSwiftStaticLibrary(
    tempDir: Path,
    swiftSource: Path,
    frameworkName: String,
    slice: AppleSdkSlice,
): LibraryArtifact {
    val sliceDir = tempDir.resolve("${slice.sdk}-${slice.arch}")
    sliceDir.createDirectories()
    val headersDir = sliceDir.resolve("Headers")
    headersDir.createDirectories()
    val swiftModuleDir = headersDir.resolve("$frameworkName.swiftmodule").also(Path::createDirectories)

    val libraryPath = sliceDir.resolve("lib$frameworkName.a")

    val objcHeaderPath = headersDir.resolve("$frameworkName-Swift.h")
    val command = listOf(
        "xcrun", "--sdk", slice.sdk, "swiftc",
        "-emit-library",
        "-static",
        "-enable-library-evolution",
        "-emit-module",
        "-emit-module-path", swiftModuleDir.resolve("${slice.baseName}.swiftmodule").absolutePathString(),
        "-emit-module-interface-path", swiftModuleDir.resolve("${slice.baseName}.swiftinterface").absolutePathString(),
        "-emit-objc-header",
        "-emit-objc-header-path", objcHeaderPath.absolutePathString(),
        "-module-name", frameworkName,
        "-target", slice.targetTriple,
        "-o", libraryPath.absolutePathString(),
        swiftSource.absolutePathString(),
    )
    runProcessOrFail(
        command = command,
        workingDir = tempDir,
        errorMessage = "Failed to compile Swift static library for ${slice.sdk}-${slice.arch}",
    )

    // Create umbrella header that includes the generated Swift header
    headersDir.resolve("$frameworkName.h").writeText(
        """
        #import <Foundation/Foundation.h>
        #import "$frameworkName-Swift.h"
        """.trimIndent()
    )

    headersDir.resolve("module.modulemap").writeText(moduleMapContent(frameworkName, "$frameworkName.h"))

    return LibraryArtifact(binaryPath = libraryPath, headersDir = headersDir)
}

private fun createFrameworkDirectories(frameworkDir: Path, headersDir: Path, modulesDir: Path) {
    frameworkDir.createDirectories()
    headersDir.createDirectories()
    modulesDir.createDirectories()
}

private fun createTempFrameworkBuildDir(
    outputDir: Path,
    tempDirName: String,
): Path {
    val parentDir = requireNotNull(outputDir.parent) {
        "Output directory $outputDir should have a parent to host temporary framework build files"
    }
    return parentDir.resolve(tempDirName).also(Path::createDirectories)
}

/**
 * Represents a compiled library artifact with its binary path and headers directory.
 */
private data class LibraryArtifact(
    val binaryPath: Path,
    val headersDir: Path,
)

private fun createXCFramework(
    outputDir: Path,
    frameworkName: String,
    tempDir: Path,
    frameworks: List<Path> = emptyList(),
    libraries: List<LibraryArtifact> = emptyList(),
): Path {
    val xcframeworkPath = outputDir.resolve("$frameworkName.xcframework")
    val command = buildList {
        addAll(listOf("xcodebuild", "-create-xcframework"))
        frameworks.forEach { framework ->
            add("-framework")
            add(framework.absolutePathString())
        }
        libraries.forEach { library ->
            add("-library")
            add(library.binaryPath.absolutePathString())
            add("-headers")
            add(library.headersDir.absolutePathString())
        }
        add("-output")
        add(xcframeworkPath.absolutePathString())
    }
    runProcessOrFail(
        command = command,
        workingDir = tempDir,
        errorMessage = "Failed to create XCFramework from frameworks",
    )
    return xcframeworkPath.also {
        tempDir.toFile().deleteRecursively()
    }
}

private fun runProcessOrFail(
    command: List<String>,
    workingDir: Path,
    errorMessage: String,
) {
    val result = runProcess(
        cmd = command,
        workingDir = workingDir.toFile(),
    )
    require(result.isSuccessful) {
        "$errorMessage: ${result.output}"
    }
}

// endregion
