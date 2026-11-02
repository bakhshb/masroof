import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val appVersionName =
    project.findProperty("appVersionName") as? String
        ?: "0.3.18"
val appVersionCode =
    (project.findProperty("appVersionCode") as? String)?.toIntOrNull()
        ?: 71
val githubOwner = "bakhshb"
val githubRepo = "Masroof"

android {
    namespace = "com.baraa.masroof"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.baraa.masroof"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "GITHUB_OWNER", "\"$githubOwner\"")
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
        buildConfigField("boolean", "ENABLE_DESIGN_CATALOG", "false")

        testInstrumentationRunner = "com.baraa.masroof.instrumentation.MasroofAndroidTestRunner"
        // Orchestrator clears app data before each device smoke so journeys do not leak.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // Two-phase process-death tests share the database across am instrument
        // invocations. clearPackageData=true would wipe that database, so the
        // orchestrated suite never runs them. scripts/m13-process-death.sh does.
        testInstrumentationRunnerArguments["notPackage"] =
            "com.baraa.masroof.instrumentation.processdeath"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "ENABLE_DESIGN_CATALOG", providers.gradleProperty("masroofDesignCatalog").orElse("false").get())
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        animationsDisabled = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            it.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
            it.forkEvery = 50
            // Robolectric SDK 36 sets FileDescriptor internals through SharedSecrets on JDK 21.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            // The feature-export test enrolls only explicitly reviewed corpus ids.
            it.inputs.file(rootProject.file("ml-assist/tools/corpus-splits.json"))
                .withPathSensitivity(PathSensitivity.RELATIVE)
            listOf("MASROOF_CORPUS_SPLITS", "MASROOF_REVIEWED_CORPUS", "MASROOF_FEATURE_EXPORT").forEach { name ->
                it.inputs.property(name, providers.environmentVariable(name).orElse(""))
            }
            System.getenv("MASROOF_REVIEWED_CORPUS")?.let { path -> it.inputs.file(path) }
            System.getenv("MASROOF_CORPUS_SPLITS")?.let { path -> it.inputs.file(path) }
            // Architecture/schema tests read source files, including relocated policies.
            it.inputs.files(
                fileTree("src/main/kotlin") { include("**/*.kt") },
                rootProject.fileTree("finance-core/src/main/kotlin") { include("**/*.kt") },
                fileTree("schemas/com.baraa.masroof.data.room.MasroofDatabase") { include("*.json") },
            ).withPathSensitivity(PathSensitivity.RELATIVE)
            if (it.name == "testDebugUnitTest") {
                it.outputs.file(layout.buildDirectory.file("model-training/features.json"))
            }
        }
    }

    // Canonical Room schema exports used by device verification.
    sourceSets {
        getByName("androidTest").assets.directories += "$projectDir/schemas"
        getByName("androidTest").assets.directories += "src/test/resources/testdata"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":ui-kit"))
    implementation(project(":bank-engine"))
    implementation(project(":finance-core"))
    implementation(project(":ml-assist"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime)

    testImplementation(testFixtures(project(":bank-engine")))
    androidTestImplementation(testFixtures(project(":bank-engine")))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)

    detektPlugins(libs.detekt.formatting)
}

detekt {
    config.setFrom("$rootDir/detekt.yml")
    baseline = file("detekt-baseline.xml")
    buildUponDefaultConfig = false
    parallel = true
}
