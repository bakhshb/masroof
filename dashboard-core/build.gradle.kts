plugins {
    kotlin("jvm")
    alias(libs.plugins.detekt)
}
apply(from = "../build-logic/pure-jvm-boundaries.gradle.kts")
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":finance-core"))
    testImplementation(testFixtures(project(":finance-core")))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    detektPlugins(libs.detekt.formatting)
}
detekt {
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = false
    baseline = file("detekt-baseline.xml")
    parallel = true
}
