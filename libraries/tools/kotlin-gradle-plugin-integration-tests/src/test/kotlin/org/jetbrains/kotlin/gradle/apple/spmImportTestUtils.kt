/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.apple

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.GenerateSyntheticLinkageImportProject
import org.jetbrains.kotlin.gradle.util.runProcess
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

@Suppress("INVISIBLE_REFERENCE")
const val SYNTHETIC_IMPORT_TARGET_MAGIC_NAME = GenerateSyntheticLinkageImportProject.Companion.SYNTHETIC_IMPORT_TARGET_MAGIC_NAME

private data class AppleSdkSlice(
    val sdk: String,
    val arch: String = "arm64",
    val targetTriple: String,
)

private val iosFrameworkSlices = listOf(
    AppleSdkSlice(
        sdk = "iphoneos",
        targetTriple = "arm64-apple-ios15.0",
    ),
    AppleSdkSlice(
        sdk = "iphonesimulator",
        targetTriple = "arm64-apple-ios15.0-simulator",
    ),
)

enum class XcframeworkSourceType {
    SWIFT_WITH_OBJC,
    OBJECTIVE_C,
}

enum class XcframeworkLinkage {
    STATIC,
    DYNAMIC,
}

data class XcframeworkSpec(
    val sourceType: XcframeworkSourceType,
    val linkage: XcframeworkLinkage = XcframeworkLinkage.DYNAMIC,
    val hasHeadersAndModules: Boolean = true,
)

// region Local Swift Package Creation Utilities

fun createLocalSwiftPackage(
    localPackageDir: Path,
    packageName: String = "LocalSwiftPackage",
    useObjcSources: Boolean = false,
) {
    localPackageDir.createDirectories()
    val sourcesDir = localPackageDir.resolve("Sources/$packageName")
    sourcesDir.createDirectories()
    writePackageManifest(localPackageDir, packageName, ".target(name: \"$packageName\"),")
    writeLocalPackageSources(sourcesDir, packageName, useObjcSources)
}

fun createLocalSwiftPackageWithBinaryTarget(
    localPackageDir: Path,
    packageName: String,
    xcframeworkSpec: XcframeworkSpec = XcframeworkSpec(XcframeworkSourceType.SWIFT_WITH_OBJC),
) {
    localPackageDir.createDirectories()

    val xcframeworkPath = when (xcframeworkSpec.sourceType) {
        XcframeworkSourceType.OBJECTIVE_C -> createStubObjcXCFramework(localPackageDir, packageName, xcframeworkSpec.linkage)
        XcframeworkSourceType.SWIFT_WITH_OBJC -> createStubSwiftXCFramework(localPackageDir, packageName, xcframeworkSpec.linkage)
    }
    if (!xcframeworkSpec.hasHeadersAndModules) {
        removeFrameworkHeadersAndModules(xcframeworkPath)
    }
    writePackageManifest(
        localPackageDir = localPackageDir,
        packageName = packageName,
        targetDefinition = """
            .binaryTarget(
                name: "$packageName",
                path: "${xcframeworkPath.fileName}"
            ),
        """.trimIndent(),
    )
}

private fun removeFrameworkHeadersAndModules(xcframeworkPath: Path) {
    xcframeworkPath.listDirectoryEntries()
        .filter { it.isDirectory() }
        .forEach { sliceDir ->
            sliceDir.listDirectoryEntries()
                .filter { it.isDirectory() }
                .filter { it.fileName.toString().endsWith(".framework") }
                .forEach { frameworkDir ->
                    frameworkDir.resolve("Headers").toFile().deleteRecursively()
                    frameworkDir.resolve("Modules").toFile().deleteRecursively()
                }
        }
}

private fun writePackageManifest(
    localPackageDir: Path,
    packageName: String,
    targetDefinition: String,
) {
    localPackageDir.resolve("Package.swift").writeText(
        """
            // swift-tools-version: ${"5.9"}
            import PackageDescription
        
            let package = Package(
                name: "$packageName",
                platforms: [.iOS(.v${"15"})],
                products: [
                    .library(name: "$packageName", targets: ["$packageName"]),
                ],
                targets: [
                    ${targetDefinition.prependIndent("            ")}
                ]
            )
        """.trimIndent()
    )
}

private fun writeLocalPackageSources(
    sourcesDir: Path,
    packageName: String,
    useObjcSources: Boolean,
) {
    if (useObjcSources) {
        sourcesDir.resolve("$packageName.h").writeText(objcHeaderContent())
        sourcesDir.resolve("$packageName.m").writeText(objcImplementationContent(packageName))
        sourcesDir.resolve("module.modulemap").writeText(moduleMapContent(packageName, "$packageName.h"))
    } else {
        sourcesDir.resolve("$packageName.swift").writeText(swiftSourceContent())
    }
}

// endregion

// region swift package description DTOs

@Serializable
data class SwiftPackageDescription(
    val dependencies: List<SwiftPackageDependency> = emptyList(),
    @SerialName("manifest_display_name") val manifestDisplayName: String,
    val name: String,
    val path: String,
    val platforms: List<SwiftPackagePlatform> = emptyList(),
    val products: List<SwiftPackageProduct> = emptyList(),
    val targets: List<SwiftPackageTarget> = emptyList(),
    @SerialName("tools_version") val toolsVersion: String,
)

@Serializable
data class SwiftPackageDependency(
    val identity: String,
    val requirement: SwiftPackageDependencyRequirement? = null,
    val type: String,
    val url: String? = null,
)

@Serializable
data class SwiftPackageDependencyRequirement(
    val range: List<SwiftPackageVersionRange>? = null,
)

@Serializable
data class SwiftPackageVersionRange(
    @SerialName("lower_bound") val lowerBound: String,
    @SerialName("upper_bound") val upperBound: String,
)

@Serializable
data class SwiftPackagePlatform(
    val name: String,
    val version: String,
)

@Serializable
data class SwiftPackageProduct(
    val name: String,
    val targets: List<String> = emptyList(),
    val type: SwiftPackageProductType,
)

@Serializable
data class SwiftPackageProductType(
    val library: List<SwiftPackageLibraryType>? = null,
    val executable: Boolean? = null,
)

@Serializable
enum class SwiftPackageLibraryType {
    @SerialName("dynamic")
    DYNAMIC,

    @SerialName("static")
    STATIC,

    @SerialName("automatic") // but in Package.swift it's '.none'
    AUTOMATIC,
}

@Serializable
data class SwiftPackageTarget(
    @SerialName("module_type") val moduleType: String,
    val name: String,
    val path: String,
    @SerialName("product_dependencies") val productDependencies: List<String> = emptyList(),
    @SerialName("product_memberships") val productMemberships: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val type: String,
)

// endregion

private val appleToolJson = Json {
    ignoreUnknownKeys = true
}

private inline fun <reified T> runAppleToolCommand(
    workingDir: Path,
    command: List<String>,
    outputFile: File? = null,
): T {
    val result = runProcess(
        cmd = command,
        workingDir = workingDir.toFile(),
    )
    require(result.isSuccessful) {
        "Failed to run command ${command.joinToString(" ")} at $workingDir: ${result.output}"
    }
    val jsonContent = outputFile?.readText() ?: result.output
    return appleToolJson.decodeFromString<T>(jsonContent)
}

fun describeSwiftPackage(packagePath: Path): SwiftPackageDescription {
    return runAppleToolCommand(packagePath, listOf("swift", "package", "describe", "--type", "json"))
}

// region swift package dump DTOs

@Serializable
data class SwiftPackageDump(
    val name: String,
    val targets: List<SwiftPackageDumpTarget> = emptyList(),
)


@Serializable
data class SwiftPackageDumpTarget(
    val name: String,
    val type: String,
    val dependencies: List<SwiftPackageDumpTargetDependency> = emptyList(),
    val settings: List<SwiftPackageDumpTargetSetting> = emptyList(),
)

@Serializable
data class SwiftPackageDumpTargetDependency(
    // product is a heterogeneous array: [productName: String, packageName: String, moduleAliases: Any?, condition: Any?]
    val product: List<kotlinx.serialization.json.JsonElement>? = null,
)

@Serializable
data class SwiftPackageDumpTargetSetting(
    val tool: String,
    val kind: SwiftPackageDumpTargetSettingKind,
)

@Serializable
data class SwiftPackageDumpTargetSettingKind(
    val unsafeFlags: SwiftPackageDumpUnsafeFlags? = null,
)

@Serializable
data class SwiftPackageDumpUnsafeFlags(
    @SerialName("_0") val flags: List<String> = emptyList(),
)

// endregion

fun dumpSwiftPackage(packagePath: Path): SwiftPackageDump {
    return runAppleToolCommand(packagePath, listOf("swift", "package", "dump-package"))
}

// region xcodebuild PIF dump DTOs

@Serializable
data class XcodebuildPIFEntry(
    val signature: String,
    val type: String,
    val contents: XcodebuildPIFContents,
)

@Serializable
data class XcodebuildPIFContents(
    val guid: String,
    val name: String? = null,
    val path: String? = null,
    val projectDirectory: String? = null,
    val projectIsPackage: String? = null,
    val projectName: String? = null,
    val targets: List<String> = emptyList(),
    val projects: List<String> = emptyList(),
    val dependencies: List<XcodebuildPIFDependency> = emptyList(),
    val buildConfigurations: List<XcodebuildPIFBuildConfiguration> = emptyList(),
    val buildPhases: List<XcodebuildPIFBuildPhase> = emptyList(),
    val productReference: XcodebuildPIFProductReference? = null,
    val productTypeIdentifier: String? = null,
    val type: String? = null,
    val frameworksBuildPhase: XcodebuildPIFFrameworksBuildPhase? = null,
)

@Serializable
data class XcodebuildPIFDependency(
    val guid: String,
    val name: String? = null,
    val platformFilters: List<String> = emptyList(),
)

@Serializable
data class XcodebuildPIFBuildConfiguration(
    val guid: String,
    val name: String,
    val buildSettings: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
)

@Serializable
data class XcodebuildPIFBuildPhase(
    val guid: String,
    val type: String,
    val buildFiles: List<XcodebuildPIFBuildFile> = emptyList(),
)

@Serializable
data class XcodebuildPIFBuildFile(
    val guid: String,
    val fileReference: String? = null,
    val targetReference: String? = null,
    val platformFilters: List<String> = emptyList(),
)

@Serializable
data class XcodebuildPIFProductReference(
    val guid: String,
    val name: String,
    val type: String,
)

@Serializable
data class XcodebuildPIFFrameworksBuildPhase(
    val guid: String,
    val type: String,
    val buildFiles: List<XcodebuildPIFBuildFile> = emptyList(),
)

// endregion

fun dumpXcodebuildPIF(appPath: Path): List<XcodebuildPIFEntry> {
    val outputFile = File.createTempFile("xcodebuild-pif", ".json")
    return runAppleToolCommand(appPath, listOf("xcodebuild", "-dumpPIF", outputFile.absolutePath), outputFile)
}

// region Code snippet generators for test sources

/**
 * Generates Objective-C header content for a helper class.
 */
private fun objcHeaderContent(): String = """
    #import <Foundation/Foundation.h>

    @interface LocalHelper : NSObject
    + (NSString *)greeting;
    @end
""".trimIndent()

/**
 * Generates Objective-C implementation content for a helper class.
 */
private fun objcImplementationContent(headerFileName: String): String = """
    #import "$headerFileName.h"

    @implementation LocalHelper
    + (NSString *)greeting {
        return @"Hello from LocalHelper";
    }
    @end
""".trimIndent()

/**
 * Generates Swift source content for a helper class with @objc annotations.
 */
private fun swiftSourceContent(): String = """
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
private fun moduleMapContent(moduleName: String, headerName: String): String = """
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

/**
 * Creates a minimal XCFramework using Objective-C for testing purposes.
 * The XCFramework contains stub frameworks for iOS device (arm64) and iOS simulator (arm64).
 *
 * @param outputDir The directory where the XCFramework will be created
 * @param frameworkName The name of the framework (without .xcframework extension)
 */
fun createStubObjcXCFramework(
    outputDir: Path,
    frameworkName: String,
    linkage: XcframeworkLinkage = XcframeworkLinkage.DYNAMIC,
): Path {
    val tempDir = createTempFrameworkBuildDir(outputDir, "${frameworkName}_temp")

    // Create Objective-C header file
    val headerFile = tempDir.resolve("$frameworkName.h")
    headerFile.writeText(objcHeaderContent())

    // Create Objective-C implementation file
    val implFile = tempDir.resolve("$frameworkName.m")
    implFile.writeText(objcImplementationContent(frameworkName))

    val frameworks = iosFrameworkSlices.map { slice ->
        buildObjcFramework(
            tempDir = tempDir,
            headerFile = headerFile,
            implFile = implFile,
            frameworkName = frameworkName,
            slice = slice,
            linkage = linkage,
        )
    }
    return createXCFramework(outputDir, frameworkName, tempDir, frameworks)
}

private fun buildObjcFramework(
    tempDir: Path,
    headerFile: Path,
    implFile: Path,
    frameworkName: String,
    slice: AppleSdkSlice,
    linkage: XcframeworkLinkage,
): Path {
    val frameworkDir = tempDir.resolve("${slice.sdk}-${slice.arch}/$frameworkName.framework")
    val headersDir = frameworkDir.resolve("Headers")
    val modulesDir = frameworkDir.resolve("Modules")
    createFrameworkDirectories(frameworkDir, headersDir, modulesDir)

    // Copy header to framework Headers directory
    headerFile.toFile().copyTo(headersDir.resolve("$frameworkName.h").toFile())

    // Create umbrella header
    headersDir.resolve("$frameworkName-umbrella.h").writeText(
        "#import <$frameworkName/$frameworkName.h>"
    )

    // Create module.modulemap
    modulesDir.resolve("module.modulemap").writeText(frameworkModuleMapContent(frameworkName))

    when (linkage) {
        XcframeworkLinkage.DYNAMIC -> {
            runProcessOrFail(
                command = listOf(
                    "xcrun", "--sdk", slice.sdk, "clang",
                    "-fobjc-arc",
                    "-dynamiclib",
                    "-target", slice.targetTriple,
                    "-framework", "Foundation",
                    "-install_name", "@rpath/$frameworkName.framework/$frameworkName",
                    "-I", headersDir.absolutePathString(),
                    "-o", frameworkDir.resolve(frameworkName).absolutePathString(),
                    implFile.absolutePathString(),
                ),
                workingDir = tempDir,
                errorMessage = "Failed to compile Objective-C dynamic framework for ${slice.sdk}-${slice.arch}",
            )
        }

        XcframeworkLinkage.STATIC -> {
            val objectFile = tempDir.resolve("${slice.sdk}-${slice.arch}/$frameworkName.o")
            runProcessOrFail(
                command = listOf(
                    "xcrun", "--sdk", slice.sdk, "clang",
                    "-fobjc-arc",
                    "-c",
                    "-target", slice.targetTriple,
                    "-I", headersDir.absolutePathString(),
                    "-o", objectFile.absolutePathString(),
                    implFile.absolutePathString(),
                ),
                workingDir = tempDir,
                errorMessage = "Failed to compile Objective-C static framework object for ${slice.sdk}-${slice.arch}",
            )
            runProcessOrFail(
                command = listOf(
                    "xcrun", "--sdk", slice.sdk, "libtool",
                    "-static",
                    "-o", frameworkDir.resolve(frameworkName).absolutePathString(),
                    objectFile.absolutePathString(),
                ),
                workingDir = tempDir,
                errorMessage = "Failed to archive Objective-C static framework for ${slice.sdk}-${slice.arch}",
            )
        }
    }
    frameworkDir.resolve("Info.plist").writeText(frameworkInfoPlistContent(frameworkName))

    return frameworkDir
}

/**
 * Creates a minimal XCFramework using Swift with @objc annotations for testing purposes.
 * The XCFramework contains stub frameworks for iOS device (arm64) and iOS simulator (arm64).
 * The Swift code uses @objc annotations to make it consumable by Kotlin/Native via cinterop.
 *
 * @param outputDir The directory where the XCFramework will be created
 * @param frameworkName The name of the framework (without .xcframework extension)
 */
fun createStubSwiftXCFramework(
    outputDir: Path,
    frameworkName: String,
    linkage: XcframeworkLinkage = XcframeworkLinkage.DYNAMIC,
): Path {
    val tempDir = createTempFrameworkBuildDir(outputDir, "${frameworkName}_swift_temp")

    // Create Swift source file with @objc annotations for Objective-C/Kotlin interop
    val swiftSource = tempDir.resolve("$frameworkName.swift")
    swiftSource.writeText(swiftSourceContent())

    val frameworks = iosFrameworkSlices.map { slice ->
        buildSwiftFramework(
            tempDir = tempDir,
            swiftSource = swiftSource,
            frameworkName = frameworkName,
            slice = slice,
            linkage = linkage,
        )
    }
    return createXCFramework(outputDir, frameworkName, tempDir, frameworks)
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
                "-emit-module",
                "-emit-module-path", modulesDir.resolve("$frameworkName.swiftmodule").absolutePathString(),
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

private fun createXCFramework(
    outputDir: Path,
    frameworkName: String,
    tempDir: Path,
    frameworks: List<Path>,
): Path {
    val xcframeworkPath = outputDir.resolve("$frameworkName.xcframework")
    val command = buildList {
        addAll(listOf("xcodebuild", "-create-xcframework"))
        frameworks.forEach { framework ->
            add("-framework")
            add(framework.absolutePathString())
        }
        add("-output")
        add(xcframeworkPath.absolutePathString())
    }
    runProcessOrFail(
        command = command,
        workingDir = tempDir,
        errorMessage = "Failed to create XCFramework",
    )
    return xcframeworkPath.also {
        // Cleanup temp directory
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
