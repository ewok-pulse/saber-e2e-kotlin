/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.apple

import org.gradle.kotlin.dsl.kotlin
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.testbase.*
import org.jetbrains.kotlin.gradle.uklibs.applyMultiplatform
import org.jetbrains.kotlin.gradle.util.isTeamCityRun
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.condition.OS
import kotlin.io.path.*
import kotlin.test.assertEquals

@OsCondition(
    supportedOn = [OS.MAC],
    enabledOnCI = [OS.MAC],
)
@OptIn(EnvironmentalVariablesOverride::class)
@DisplayName("SwiftPM import integration tests for local packages")
@NativeGradlePluginTests
class SwiftPMImportLocalPackagesIT : KGPBaseTest() {

    @GradleTest
    fun `local package cinterop klib signatures are updated when Swift source changes`(version: GradleVersion) {
        project("emptyxcode", version) {
            val localSwiftPackageRelativePath = "../localSwiftPackage"
            val localPackageDir = projectPath.resolve(localSwiftPackageRelativePath)
            val targetName = "LocalSwiftPackage"

            createLocalSwiftPackage(localPackageDir, packageName = targetName)

            // Overwrite the default Swift source with custom content for this test
            localPackageDir.resolve("Sources/$targetName/$targetName.swift").writeText(
                """
                    import Foundation

                    @objc public class OriginalClass: NSObject {
                        @objc public func originalMethod() -> String {
                            return "original"
                        }
                        @objc public func methodToBeRemoved() -> String {
                            return "will be removed"
                        }
                    }
                """.trimIndent()
            )

            plugins {
                kotlin("multiplatform")
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    listOf(
                        iosArm64(),
                        iosSimulatorArm64()
                    ).forEach {
                        it.binaries.framework {
                            baseName = "Shared"
                            isStatic = true
                        }
                    }

                    swiftPMDependencies {
                        localSwiftPackage(
                            directory = project.layout.projectDirectory.dir(localSwiftPackageRelativePath),
                            products = listOf(targetName),
                        )
                    }
                }
            }

            assertEquals(
                """
                    swiftPMImport.emptyxcode/OriginalClass.<init>|objc:init#Constructor[1]
                    swiftPMImport.emptyxcode/OriginalClass.Companion|null[1]
                    swiftPMImport.emptyxcode/OriginalClass.init|objc:init[1]
                    swiftPMImport.emptyxcode/OriginalClass.methodToBeRemoved|objc:methodToBeRemoved[1]
                    swiftPMImport.emptyxcode/OriginalClass.originalMethod|objc:originalMethod[1]
                    swiftPMImport.emptyxcode/OriginalClassMeta.<init>|<init>(){}[1]
                    swiftPMImport.emptyxcode/OriginalClassMeta.allocWithZone|objc:allocWithZone:[1]
                    swiftPMImport.emptyxcode/OriginalClassMeta.alloc|objc:alloc[1]
                    swiftPMImport.emptyxcode/OriginalClassMeta.new|objc:new[1]
                    swiftPMImport.emptyxcode/OriginalClassMeta|null[1]
                    swiftPMImport.emptyxcode/OriginalClass|null[1]
                """.trimIndent(),
                commonizeAndDumpCinteropSignatures().trim(),
                message = "Initial cinterop signatures should match expected output"
            )

            localPackageDir.resolve("Sources/$targetName/$targetName.swift").writeText(
                """
                    import Foundation

                    @objc public class OriginalClass: NSObject {
                        @objc public func originalMethod() -> String {
                            return "original"
                        }
                    }

                    @objc public class AddedClass: NSObject {
                        @objc public func addedMethod() -> String {
                            return "added"
                        }
                    }
                """.trimIndent()
            )

            assertEquals(
                """
                    swiftPMImport.emptyxcode/AddedClass.<init>|objc:init#Constructor[1]
                    swiftPMImport.emptyxcode/AddedClass.Companion|null[1]
                    swiftPMImport.emptyxcode/AddedClass.addedMethod|objc:addedMethod[1]
                    swiftPMImport.emptyxcode/AddedClass.init|objc:init[1]
                    swiftPMImport.emptyxcode/AddedClassMeta.<init>|<init>(){}[1]
                    swiftPMImport.emptyxcode/AddedClassMeta.allocWithZone|objc:allocWithZone:[1]
                    swiftPMImport.emptyxcode/AddedClassMeta.alloc|objc:alloc[1]
                    swiftPMImport.emptyxcode/AddedClassMeta.new|objc:new[1]
                    swiftPMImport.emptyxcode/AddedClassMeta|null[1]
                    swiftPMImport.emptyxcode/AddedClass|null[1]
                    swiftPMImport.emptyxcode/OriginalClass.<init>|objc:init#Constructor[1]
                    swiftPMImport.emptyxcode/OriginalClass.Companion|null[1]
                    swiftPMImport.emptyxcode/OriginalClass.init|objc:init[1]
                    swiftPMImport.emptyxcode/OriginalClass.originalMethod|objc:originalMethod[1]
                    swiftPMImport.emptyxcode/OriginalClassMeta.<init>|<init>(){}[1]
                    swiftPMImport.emptyxcode/OriginalClassMeta.allocWithZone|objc:allocWithZone:[1]
                    swiftPMImport.emptyxcode/OriginalClassMeta.alloc|objc:alloc[1]
                    swiftPMImport.emptyxcode/OriginalClassMeta.new|objc:new[1]
                    swiftPMImport.emptyxcode/OriginalClassMeta|null[1]
                    swiftPMImport.emptyxcode/OriginalClass|null[1]
                """.trimIndent(),
                commonizeAndDumpCinteropSignatures().trim(),
                message = "Updated cinterop signatures should match expected output"
            )
        }
    }

    @GradleTest
    fun `local package with objc sources`(version: GradleVersion) {
        project("emptyxcode", version) {
            val localPackageRelativePath = "../localObjcPackage"
            val localPackageDir = projectPath.resolve(localPackageRelativePath)
            val targetName = "LocalObjcPackage"

            createLocalSwiftPackage(localPackageDir, packageName = targetName, sourceLanguage = SwiftPackageSourceLanguage.OBJC)

            plugins {
                kotlin("multiplatform")
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    listOf(
                        iosArm64(),
                        iosSimulatorArm64()
                    ).forEach {
                        it.binaries.framework {
                            baseName = "Shared"
                            isStatic = true
                        }
                    }

                    swiftPMDependencies {
                        localSwiftPackage(
                            directory = project.layout.projectDirectory.dir(localPackageRelativePath),
                            products = listOf(targetName),
                        )
                    }
                }
            }

            assertEquals(
                """
                    swiftPMImport.emptyxcode/LocalHelper.<init>|objc:init#Constructor[1]
                    swiftPMImport.emptyxcode/LocalHelper.Companion|null[1]
                    swiftPMImport.emptyxcode/LocalHelper.init|objc:init[1]
                    swiftPMImport.emptyxcode/LocalHelperMeta.<init>|<init>(){}[1]
                    swiftPMImport.emptyxcode/LocalHelperMeta.allocWithZone|objc:allocWithZone:[1]
                    swiftPMImport.emptyxcode/LocalHelperMeta.alloc|objc:alloc[1]
                    swiftPMImport.emptyxcode/LocalHelperMeta.greeting|objc:greeting[1]
                    swiftPMImport.emptyxcode/LocalHelperMeta.new|objc:new[1]
                    swiftPMImport.emptyxcode/LocalHelperMeta|null[1]
                    swiftPMImport.emptyxcode/LocalHelper|null[1]
                """.trimIndent(),
                commonizeAndDumpCinteropSignatures().trim(),
                message = "Cinterop signatures should match expected output for local package with ObjC sources"
            )
        }
    }

    @GradleTest
    fun `check that cpp packages not visible in kotlin, but passed to xcodebuild`(version: GradleVersion) {
        project("emptyxcode", version) {
            val localPackageRelativePath = "../localCxxPackage"
            val localPackageDir = projectPath.resolve(localPackageRelativePath)
            val targetName = "LocalCxxPackage"

            createLocalSwiftPackage(localPackageDir, packageName = targetName, sourceLanguage = SwiftPackageSourceLanguage.CXX)

            plugins {
                kotlin("multiplatform")
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    listOf(
                        iosArm64(),
                        iosSimulatorArm64()
                    ).forEach {
                        it.binaries.framework {
                            baseName = "Shared"
                            isStatic = true
                        }
                    }

                    swiftPMDependencies {
                        localSwiftPackage(
                            directory = project.layout.projectDirectory.dir(localPackageRelativePath),
                            products = listOf(targetName),
                        )
                    }
                }
            }

            kotlinSourcesDir("iosMain")
                .createDirectories().resolve("temp.kt")
                .createFile()
                .writeText("class IosMain")

            assertEquals(
                "",
                commonizeAndDumpCinteropSignatures().trim(),
                message = "Cinterop signatures should be empty for local package with C++ sources"
            )

            projectPath.resolve("iosApp/iosApp/iOSApp.swift").writeText(
                """
                    import SwiftUI
                    import LocalCxxPackage

                    @main
                    struct iOSApp: App {
                        var body: some Scene {
                            WindowGroup {
                                let _ = String(cString: cxx_greeting())
                            }
                        }
                    }
                """.trimIndent()
            )

            build(
                "integrateLinkagePackage",
                environmentVariables = EnvironmentalVariables(
                    "XCODEPROJ_PATH" to "iosApp/iosApp.xcodeproj"
                )
            )

            buildXcodeProject(
                xcodeproj = projectPath.resolve("iosApp/iosApp.xcodeproj"),
            )
        }
    }

    @GradleTest
    fun `check that pure swift packages not visible in kotlin, but passed to xcodebuild`(version: GradleVersion) {
        project("emptyxcode", version) {
            val localPackageRelativePath = "../localPureSwiftPackage"
            val localPackageDir = projectPath.resolve(localPackageRelativePath)
            val targetName = "LocalPureSwiftPackage"

            createLocalSwiftPackage(localPackageDir, packageName = targetName, sourceLanguage = SwiftPackageSourceLanguage.SWIFT)

            plugins {
                kotlin("multiplatform")
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    listOf(
                        iosArm64(),
                        iosSimulatorArm64()
                    ).forEach {
                        it.binaries.framework {
                            baseName = "Shared"
                            isStatic = true
                        }
                    }

                    swiftPMDependencies {
                        localSwiftPackage(
                            directory = project.layout.projectDirectory.dir(localPackageRelativePath),
                            products = listOf(targetName),
                        )
                    }
                }
            }

            kotlinSourcesDir("iosMain")
                .createDirectories().resolve("temp.kt")
                .createFile()
                .writeText("class IosMain")

            assertEquals(
                "",
                commonizeAndDumpCinteropSignatures().trim(),
                message = "Cinterop signatures should be empty for local package with pure Swift sources"
            )

            projectPath.resolve("iosApp/iosApp/iOSApp.swift").writeText(
                """
                    import SwiftUI
                    import LocalPureSwiftPackage

                    @main
                    struct iOSApp: App {
                        var body: some Scene {
                            WindowGroup {
                                let _ = PureSwiftHelper.greeting()
                            }
                        }
                    }
                """.trimIndent()
            )

            build(
                "integrateLinkagePackage",
                environmentVariables = EnvironmentalVariables(
                    "XCODEPROJ_PATH" to "iosApp/iosApp.xcodeproj"
                )
            )

            buildXcodeProject(
                xcodeproj = projectPath.resolve("iosApp/iosApp.xcodeproj"),
            )
        }
    }

    @GradleTest
    fun `local package with binaryTarget static framework xcframework`(version: GradleVersion) {
        testLocalPackageWithBinaryTargetXcframework(
            version,
            linkage = XcframeworkLinkage.STATIC,
            buildType = XcframeworkBuildType.FRAMEWORK,
        )
    }

    @GradleTest
    fun `local package with binaryTarget dynamic framework xcframework`(version: GradleVersion) {
        testLocalPackageWithBinaryTargetXcframework(
            version,
            linkage = XcframeworkLinkage.DYNAMIC,
            buildType = XcframeworkBuildType.FRAMEWORK,
        )
    }

    @GradleTest
    fun `local package with binaryTarget library xcframework`(version: GradleVersion) {
        testLocalPackageWithBinaryTargetXcframework(
            version,
            buildType = XcframeworkBuildType.LIBRARY,
            linkage = XcframeworkLinkage.STATIC
        )
    }

    private fun testLocalPackageWithBinaryTargetXcframework(
        version: GradleVersion,
        linkage: XcframeworkLinkage,
        buildType: XcframeworkBuildType,
    ) {
        if (!isTeamCityRun) {
            Assumptions.assumeTrue(version >= GradleVersion.version("8.0"))
        }
        project("emptyxcode", version) {
            val frameworkName = "BinaryLib"
            val localPackageRelativePath = "../localBinaryPackage"
            val localPackageDir = projectPath.resolve(localPackageRelativePath)

            createLocalSwiftPackageWithBinaryTarget(
                localPackageDir = localPackageDir,
                packageName = frameworkName,
                linkage = linkage,
                buildType = buildType,
            )

            plugins {
                kotlin("multiplatform")
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    listOf(
                        iosArm64(),
                        iosSimulatorArm64()
                    ).forEach {
                        it.binaries.framework {
                            baseName = "Shared"
                            isStatic = true
                        }
                    }

                    swiftPMDependencies {
                        localSwiftPackage(
                            directory = project.layout.projectDirectory.dir(localPackageRelativePath),
                            products = listOf(frameworkName),
                        )
                    }
                }
            }

            kotlinSourcesDir("iosMain")
                .createDirectories().resolve("temp.kt")
                .createFile()
                .writeText("class IosMain")

            assertEquals(
                """
                    swiftPMImport.emptyxcode/LocalHelper.<init>|objc:init#Constructor[1]
                    swiftPMImport.emptyxcode/LocalHelper.Companion|null[1]
                    swiftPMImport.emptyxcode/LocalHelper.init|objc:init[1]
                    swiftPMImport.emptyxcode/LocalHelperMeta.<init>|<init>(){}[1]
                    swiftPMImport.emptyxcode/LocalHelperMeta.allocWithZone|objc:allocWithZone:[1]
                    swiftPMImport.emptyxcode/LocalHelperMeta.alloc|objc:alloc[1]
                    swiftPMImport.emptyxcode/LocalHelperMeta.greeting|objc:greeting[1]
                    swiftPMImport.emptyxcode/LocalHelperMeta.new|objc:new[1]
                    swiftPMImport.emptyxcode/LocalHelperMeta|null[1]
                    swiftPMImport.emptyxcode/LocalHelper|null[1]
                """.trimIndent(),
                commonizeAndDumpCinteropSignatures().trim(),
                message = "Cinterop signatures should match expected output for linkage=$linkage, buildType=$buildType"
            )

            projectPath.resolve("iosApp/iosApp/iOSApp.swift").writeText(
                """
                    import SwiftUI
                    import BinaryLib

                    @main
                    struct iOSApp: App {
                        var body: some Scene {
                            WindowGroup {
                                let _ = LocalHelper()
                                let _ = LocalHelper.greeting()
                            }
                        }
                    }
                """.trimIndent()
            )

            build(
                "integrateLinkagePackage",
                environmentVariables = EnvironmentalVariables(
                    "XCODEPROJ_PATH" to "iosApp/iosApp.xcodeproj"
                )
            )

            buildXcodeProject(
                xcodeproj = projectPath.resolve("iosApp/iosApp.xcodeproj"),
            )
        }
    }
}
