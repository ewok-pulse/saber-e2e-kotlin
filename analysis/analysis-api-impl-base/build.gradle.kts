import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm")
    id("java-test-fixtures")
    id("project-tests-convention")
    id("test-inputs-check")
}

dependencies {
    api(project(":compiler:psi:psi-api"))
    api(project(":analysis:analysis-api"))
    api(project(":analysis:analysis-api-platform-interface"))
    api(project(":analysis:kt-references"))
    api(project(":compiler:resolution.common.jvm"))
    implementation(project(":analysis:decompiled:decompiler-to-psi"))
    implementation(project(":compiler:backend"))
    implementation(kotlinxCollectionsImmutable())
    api(intellijCore())
    implementation(project(":analysis:analysis-internal-utils"))
    implementation(libs.caffeine)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":compiler:cli-base"))

    testFixturesApi(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter.api)
    testFixturesImplementation(kotlinTest("junit"))
    testFixturesImplementation(project(":analysis:analysis-api"))
    testFixturesImplementation(project(":analysis:analysis-api-standalone:analysis-api-standalone-base"))
    testFixturesImplementation(testFixtures(project(":compiler:tests-common")))
    testFixturesApi(testFixtures(project(":compiler:test-infrastructure-utils")))
    testFixturesApi(testFixtures(project(":compiler:test-infrastructure")))
    testFixturesImplementation(testFixtures(project(":plugins:plugin-sandbox")))
    testFixturesImplementation(testFixtures(project(":compiler:tests-common-new")))
    testFixturesImplementation(project(":analysis:decompiled:decompiler-to-file-stubs"))
    testFixturesImplementation(project(":analysis:decompiled:light-classes-for-decompiled"))
    testFixturesImplementation(project(":analysis:decompiled:decompiler-native"))
    testFixturesImplementation(testFixtures(project(":analysis:analysis-test-framework")))
    testFixturesImplementation(commonDependency("org.jetbrains.kotlin:kotlin-reflect")) { isTransitive = false }
    testFixturesCompileOnly(toolsJarApi())
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
    "testFixtures" { projectDefault() }
}

projectTests {
    testTask(jUnitMode = JUnitMode.JUnit5)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.optIn.addAll(
        listOf(
            "org.jetbrains.kotlin.analysis.api.KaImplementationDetail",
            "org.jetbrains.kotlin.analysis.api.KaExperimentalApi",
            "org.jetbrains.kotlin.analysis.api.KaNonPublicApi",
            "org.jetbrains.kotlin.analysis.api.KaIdeApi",
            "org.jetbrains.kotlin.analysis.api.KaPlatformInterface",
            "org.jetbrains.kotlin.analysis.api.KaContextParameterApi",
            "org.jetbrains.kotlin.analysis.api.components.KaSessionComponentImplementationDetail",
            "org.jetbrains.kotlin.analysis.api.KaSpiExtensionPoint",
        )
    )
}

testsJar()
