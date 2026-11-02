plugins {
    kotlin("jvm")
    alias(libs.plugins.detekt)
}
apply(from = "../build-logic/pure-jvm-boundaries.gradle.kts")
kotlin { jvmToolchain(21) }
dependencies { testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    detektPlugins(libs.detekt.formatting) }

detekt {
    config.setFrom("$rootDir/detekt.yml")
    baseline = file("detekt-baseline.xml")
    buildUponDefaultConfig = false
    parallel = true
}


tasks.register<JavaExec>("trainRecurringSeed") {
    group = "verification"
    description = "Fit/evaluate a reviewed numerical corpus with the on-device implementation"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.baraa.masroof.analytics.RecurringTrainingCliKt")
    args(providers.gradleProperty("recurringCorpus").getOrElse("$projectDir/tools/recurring-corpus.tsv"),
        providers.gradleProperty("recurringOutput").getOrElse("$projectDir/src/main/resources/ml/recurring-seed.json"),
        providers.gradleProperty("recurringReport").getOrElse("$projectDir/RECURRING_EVALUATION.json"))
    if (providers.gradleProperty("recurringCheck").orNull == "true") args("--check")
}

// The pure modules use Jupiter; Android and Robolectric retain their native runner.
tasks.test { useJUnitPlatform() }
