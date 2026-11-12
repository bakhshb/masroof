plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.baraa.masroof.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testFixtures { enable = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            it.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
            it.forkEvery = 50
            // Robolectric SDK 36 sets FileDescriptor internals through SharedSecrets on JDK 21.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

// Room schemas for every database of the app export here.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":finance-core"))
    implementation(project(":ml-assist"))
    api(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    kspTestFixtures(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testFixturesApi(libs.androidx.room.runtime)
    testFixturesApi(libs.junit)

    testImplementation(testFixtures(project(":finance-core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    detektPlugins(libs.detekt.formatting)
}

detekt {
    config.setFrom("$rootDir/detekt.yml")
    baseline = file("detekt-baseline.xml")
    buildUponDefaultConfig = false
    parallel = true
}
