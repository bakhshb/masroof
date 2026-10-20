package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.application.BetaStartState
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

/** Synthetic instrumentation package on CI emulator only; never resets a user installation. */
class BetaStartActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    @org.junit.Before fun allowSyntheticInbox() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().packageName,android.Manifest.permission.READ_SMS)
    }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var originalLocales: android.os.LocaleList? = null
    private fun isolatedEmulator() = context.packageName == "com.baraa.masroof.lab.beta.test" &&
        (Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"))
    @Before fun setup() {
        Assume.assumeTrue("Synthetic emulator only", isolatedEmulator())
        BetaContainer.resetOffline(context)
    }
    @After fun cleanup() {
        if (isolatedEmulator()) {
            if (Build.VERSION.SDK_INT >= 33) originalLocales?.let { original ->
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = original
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
            drain(); BetaContainer.resetOffline(context)
        }
    }

    @Test fun freshWelcomeSurvivesBackAndRecreationUntilExplicitStart() {
        val protected = protectedFiles()
        val before = protected.map(::hash)
        ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
            await("beta-start-new")
            compose.onNodeWithTag("dashboard-ready").assertDoesNotExist()
            compose.onNodeWithTag("beta-history").assertDoesNotExist()
            compose.onNodeWithTag("beta-settings").assertDoesNotExist()
            capture("m0-new-welcome")
            scenario.recreate(); await("beta-start-new")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        }
        drain()
        BetaContainer(context).use { assertEquals(BetaStartState.NEW, it.startState()); assertTrue(it.evidencePage().isEmpty()); assertNull(it.historySession()) }
        ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
            await("beta-start-new"); compose.onNodeWithTag("beta-start-continue").performClick()
            await("onboarding-IMPORT"); compose.onNodeWithTag("beta-start-new").assertDoesNotExist()
            scenario.recreate(); await("onboarding-IMPORT")
            compose.onNodeWithTag("beta-return").assertDoesNotExist()
        }
        drain()
        BetaContainer(context).use {
            assertEquals(BetaStartState.READY, it.startState()); assertTrue(it.assets().isEmpty())
            assertTrue(it.bankChoices().all { choice -> choice.state == com.baraa.masroof.lab.beta.application.BankSelectionState.ENABLED }); assertTrue(it.evidencePage().isEmpty()); assertNull(it.historySession())
        }
        assertEquals(before, protected.map(::hash)); assertProtectedPreferences()
    }

    @Test fun previousBetaDataRequiresExplicitResumeAndIsNeverReset() {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(android.app.LocaleManager::class.java)
            originalLocales = locales.applicationLocales
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                locales.applicationLocales = android.os.LocaleList.forLanguageTags("ar")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        val protected = protectedFiles(); val before = protected.map(::hash)
        val bank = BankId("BANK_ALJAZIRA")
        val asset = OwnedAsset(AssetId(Environment.BETA, bank, "synthetic-m0-existing"), AssetKind.ACCOUNT, "4321")
        BetaContainer(context).use { beta -> beta.registerAsset(asset); beta.setBankEnabled(bank, true); beta.putMarker("human-note", "synthetic-keep"); beta.putPreferenceProbe("keep") }
        ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
            await("beta-start-existing"); compose.onNodeWithTag("dashboard-ready").assertDoesNotExist()
            if (Build.VERSION.SDK_INT >= 33) scenario.onActivity {
                assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, it.resources.configuration.layoutDirection)
            }
            capture("m0-existing-preserved")
            compose.onNodeWithTag("beta-return").performClick()
        }
        drain()
        BetaContainer(context).use { assertEquals(BetaStartState.EXISTING, it.startState()); assertEquals(listOf(asset), it.assets()); assertEquals(1, it.markerCount()) }
        ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
            await("beta-start-existing"); compose.onNodeWithTag("beta-start-continue").performClick(); await("dashboard-ready")
            scenario.recreate(); await("dashboard-ready"); compose.onNodeWithTag("beta-start-existing").assertDoesNotExist()
        }
        drain()
        BetaContainer(context).use { assertEquals(BetaStartState.READY, it.startState()); assertEquals(listOf(asset), it.assets()); assertEquals(1, it.markerCount()); assertEquals("keep", it.preferenceProbe()) }
        assertEquals(before, protected.map(::hash)); assertProtectedPreferences()
    }

    @Test fun corruptExistingBetaIsRetainedAndOnlyOffersRetryOrReturn() {
        val protected = protectedFiles(); val before = protected.map(::hash)
        val file = context.getDatabasePath(BetaEnvironment.DATABASE_NAME)
        file.parentFile?.mkdirs(); file.writeText("synthetic-corrupt-beta-must-not-be-deleted")
        val original = hash(file)
        ActivityScenario.launch(BetaActivity::class.java).use { scenario ->
            await("beta-startup-failure"); compose.onNodeWithTag("dashboard-ready").assertDoesNotExist()
            compose.onNodeWithTag("beta-start-continue").assertDoesNotExist()
            capture("m0-corrupt-retained")
            compose.onNodeWithTag("beta-startup-retry").performClick(); await("beta-startup-failure")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        }
        drain(); assertTrue(file.isFile); assertEquals(original, hash(file)); assertEquals(before, protected.map(::hash)); assertProtectedPreferences()
    }

    @Test fun confirmedCleanupRunsOnceAndKeepsNewDataOnLaterLaunches() {
        val protected = protectedFiles()
        val hashes = protected.map(::hash)
        BetaContainer(context).use { it.putMarker("old-experiment", "discard"); it.putPreferenceProbe("old") }
        ActivityScenario.launch(BetaActivity::class.java).use {
            await("beta-start-existing"); compose.onNodeWithTag("beta-start-fresh").performClick()
            compose.onNodeWithTag("beta-start-fresh-confirm").assertIsDisplayed()
            capture("m0-cleanup-confirmation")
            compose.onNodeWithTag("beta-start-fresh-cancel").performClick()
            compose.onNodeWithTag("beta-return").performClick()
        }
        drain()
        BetaContainer(context).use { assertEquals(1, it.markerCount()); assertEquals("old", it.preferenceProbe()) }
        ActivityScenario.launch(BetaActivity::class.java).use {
            await("beta-start-existing"); compose.onNodeWithTag("beta-start-fresh").performClick()
            compose.onNodeWithTag("beta-start-fresh-confirm").performClick()
            await("beta-start-new"); compose.onNodeWithTag("beta-start-fresh").assertDoesNotExist()
            compose.onNodeWithTag("beta-start-continue").performClick(); await("onboarding-IMPORT")
        }
        drain()
        BetaContainer(context).use {
            assertEquals(0, it.markerCount()); assertNull(it.preferenceProbe())
            it.putMarker("new-experience", "keep"); it.putPreferenceProbe("new")
        }
        ActivityScenario.launch(BetaActivity::class.java).use { await("onboarding-IMPORT"); compose.onNodeWithTag("beta-start-fresh").assertDoesNotExist(); compose.onNodeWithTag("beta-return").assertDoesNotExist() }
        drain()
        BetaContainer(context).use { assertEquals(BetaStartState.READY, it.startState()); assertEquals(1, it.markerCount()); assertEquals("new", it.preferenceProbe()) }
        assertEquals(hashes, protected.map(::hash)); assertProtectedPreferences()
    }

    private fun await(tag: String) { compose.waitUntil(20000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }; compose.waitForIdle() }
    private fun drain() { val done = CountDownLatch(1); BetaIo.execute { done.countDown() }; assertTrue(done.await(20, TimeUnit.SECONDS)) }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun protectedFiles(): List<File> {
        BetaEnvironment.forbiddenPreferenceNames.forEach { name -> assertTrue(context.getSharedPreferences(name, 0).edit().putString("m0-sentinel", "synthetic-$name").commit()) }
        return listOf(BetaEnvironment.PRODUCTION_DATABASE_NAME, BetaEnvironment.PARSING_LAB_DATABASE_NAME,
            BetaEnvironment.MIGRATION_STAGING_DATABASE_NAME).map { name -> context.getDatabasePath(name).apply { parentFile?.mkdirs(); writeText("synthetic-$name") } }
    }
    private fun assertProtectedPreferences() { BetaEnvironment.forbiddenPreferenceNames.forEach { name -> assertEquals("synthetic-$name", context.getSharedPreferences(name, 0).getString("m0-sentinel", null)) } }
    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(100, 5000)
        fun command(value: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(value)).use { it.readBytes() }
        command("mkdir -p /sdcard/beta-dashboard-screenshots")
        command("screencap -p /sdcard/beta-dashboard-screenshots/$name.png")
        assertTrue(command("cat /sdcard/beta-dashboard-screenshots/$name.png").size > 8)
        val tags = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().joinToString("\n") { "root=${it.id}" }
        val tree = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.joinToString("\n") { index -> compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToString() }
        val metadata = "api=${Build.VERSION.SDK_INT}\nhardware=${Build.HARDWARE}\n" + context.resources.configuration.toString() + "\n" + tags + "\n" + tree
        val directory = File(context.getExternalFilesDir(null), "m0-entry").apply { check(isDirectory || mkdirs()) }
        val file = File(directory, "$name.txt")
        file.writeText(metadata)
        val saved = file.readText()
        assertTrue(saved.contains("api=${Build.VERSION.SDK_INT}") && saved.contains("hardware=${Build.HARDWARE}"))
        // AGP uninstalls the test APK before the CI helper pulls artifacts. Retain the
        // synthetic diagnostics beside the screenshots while this package still exists.
        command("cp ${file.absolutePath} /sdcard/beta-dashboard-screenshots/$name.txt")
        assertEquals(metadata, command("cat /sdcard/beta-dashboard-screenshots/$name.txt").toString(Charsets.UTF_8))
    }
}
