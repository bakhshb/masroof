plugins {
    kotlin("jvm")
    `java-test-fixtures`
}
apply(from = "../build-logic/pure-jvm-boundaries.gradle.kts")
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":finance-core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.kotlinx.serialization.json)
}

// Boundary tests inspect files outside this module. Include those files in the test cache key.
tasks.test {
    inputs.files(rootProject.fileTree(".") {
        include("*/build.gradle.kts", "bank-engine/src/main/kotlin/**/*.kt",
            "finance-core/src/main/kotlin/**/*.kt", "ml-assist/src/main/kotlin/**/*.kt", "app/src/main/kotlin/**/*.kt")
    }, rootProject.file("app/build.gradle.kts")).withPathSensitivity(PathSensitivity.RELATIVE)
}

// The pure modules use Jupiter; Android and Robolectric retain their native runner.
tasks.test { useJUnitPlatform() }
