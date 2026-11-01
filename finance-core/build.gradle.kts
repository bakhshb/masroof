plugins {
    kotlin("jvm")
    alias(libs.plugins.detekt)
}
apply(from = "../build-logic/pure-jvm-boundaries.gradle.kts")
kotlin { jvmToolchain(21) }
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    detektPlugins(libs.detekt.formatting)
}
detekt {
    config.setFrom("$rootDir/detekt.yml")
    baseline = file("detekt-baseline.xml")
    buildUponDefaultConfig = false
    parallel = true
}

// Ownership checks inspect the app tree as well as this module's sources.
tasks.test {
    inputs.files(
        fileTree("src/main/kotlin") { include("**/*.kt") },
        rootProject.fileTree("app/src/main/kotlin") { include("**/*.kt") },
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}

// The pure modules use Jupiter; Android and Robolectric retain their native runner.
tasks.test { useJUnitPlatform() }
