package com.baraa.masroof.instrumentation

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import com.baraa.masroof.R
import com.baraa.masroof.application.patterns.MessagePatternsWorkflow
import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.engine.bank.MessageFamily
import com.baraa.masroof.engine.bank.MoneyDirection
import com.baraa.masroof.engine.bank.patterns.PatternFactContract
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.presentation.patterns.MessagePatternsScreen
import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.ui.theme.MasroofTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PatternManagementJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var keyboard: SoftwareKeyboardController? = null
    private fun ar(id: Int) = AppLocaleContext.wrap(compose.activity, "ar").getString(id)
    private fun click(id: Int) {
        val node = compose.onNodeWithText(ar(id))
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        node.performClick()
    }
    private fun capture(name: String) {
        compose.runOnIdle { keyboard?.hide() }
        compose.waitForIdle()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.findObject(By.res("com.android.systemui", "dismiss_button"))?.click()
        device.waitForIdle(1_500)
        ScreenshotArtifactWriter.capture(compose, "pattern-management", name)
    }
    private fun show(workflow: MessagePatternsWorkflow, openPattern: Boolean = true,
        setContent: (@Composable () -> Unit) -> Unit = { compose.setContent(it) }) {
        setContent {
            val context = AppLocaleContext.wrap(LocalContext.current, "ar")
            keyboard = LocalSoftwareKeyboardController.current
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
                LocalLayoutDirection provides LayoutDirection.Rtl) {
                MasroofTheme { Surface { MessagePatternsScreen(workflow, "BANK_STC", onBack = {}) } }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pattern-open-BANK_STC-stc-sarie-in").fetchSemanticsNodes().isNotEmpty() }
        if (openPattern) {
            compose.onNodeWithTag("pattern-open-BANK_STC-stc-sarie-in").performScrollTo().performClick()
            capture("pattern-overview")
        }
    }

    @Test fun catalogPreviewIsReadOnlyAndDetailsKeepThePreviewExpanded() = runBlocking {
        fixture { workflow, store ->
            val before = store.load()
            show(workflow, openPattern = false)
            compose.onNodeWithTag("pattern-preview-BANK_STC-stc-sarie-in").performScrollTo().performClick()
            compose.onNodeWithTag("masroof-bottom-sheet").assertIsDisplayed()
            compose.onNodeWithTag("pattern-preview").assertIsDisplayed()
            compose.onNodeWithTag("sheet-cancel").performClick()
            assertEquals(before, store.load())
            compose.onNodeWithTag("pattern-open-BANK_STC-stc-sarie-in").performScrollTo().performClick()
            compose.onNodeWithTag("pattern-preview").performScrollTo().assertIsDisplayed().assert(hasClickAction().not())
            compose.onNodeWithTag("pattern-delete").performScrollTo().assertIsNotEnabled()
            assertEquals(before, store.load())
        }
    }

    @Test fun disabledBundledPatternRequiresCopiedExactNameAndCanBeCancelledBeforePermanentDeletion() = runBlocking {
        fixture { workflow, store ->
            show(workflow)
            compose.onNodeWithTag("pattern-delete").performScrollTo().assertIsNotEnabled()
            capture("enabled-pattern")
            compose.onNodeWithTag("pattern-toggle-BANK_STC-stc-sarie-in").performScrollTo().performClick()
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("pattern-delete").assertIsEnabled() }.isSuccess }
            compose.onNodeWithTag("pattern-delete").performScrollTo().performClick()
            compose.onNodeWithTag("pattern-delete-confirm").assertIsNotEnabled()
            capture("delete-empty")
            compose.onNodeWithTag("pattern-delete-confirmation").performTextInput("اسم آخر")
            compose.onNodeWithTag("pattern-delete-confirm").assertIsNotEnabled()
            compose.onNodeWithTag("pattern-delete-confirmation").performTextClearance()
            compose.onNodeWithTag("pattern-delete-copy").performScrollTo().performClick()
            compose.waitForIdle()
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString()
            assertEquals(AppLocaleContext.wrap(compose.activity, "ar").resources.getStringArray(R.array.pattern_operations)[1], copied)
            compose.onNodeWithTag("pattern-delete-confirmation").performSemanticsAction(SemanticsActions.PasteText) { it() }
            compose.onNodeWithTag("pattern-delete-confirm").assertIsEnabled()
            compose.onNodeWithTag("pattern-delete-confirmation").performImeAction()
            capture("delete-matched")
            compose.onNodeWithText(ar(R.string.settings_cancel)).performClick()
            assertTrue(store.load().banks.single().patterns.any { it.id == "stc-sarie-in" })
            compose.onNodeWithTag("pattern-delete").performScrollTo().performClick()
            compose.onNodeWithTag("pattern-delete-confirm").assertIsNotEnabled()
            compose.onNodeWithTag("pattern-delete-confirmation").performTextInput(copied)
            compose.onNodeWithTag("pattern-delete-confirmation").performImeAction()
            compose.onNodeWithTag("pattern-delete-confirm").performClick()
            compose.waitUntil(10_000) { store.load().banks.single().patterns.none { it.id == "stc-sarie-in" } }
            assertTrue(workflow.load().banks.single().patterns.none { it.id == "stc-sarie-in" })
            assertTrue("stc-sarie-in" in workflow.load().banks.single().deletedPatternIds)
            compose.onNodeWithTag("pattern-open-BANK_STC-stc-sarie-in").assertDoesNotExist()
        }
    }

    @Test fun editingPrepopulatesMatchingFieldsAndSavesANewUnapprovedRevision() = runBlocking {
        fixture { workflow, store ->
            val before = store.load()
            val bank = before.banks.single()
            val pattern = bank.currentPatterns.single { it.id == "stc-sarie-in" }
            store.saveBank(before.generation, bank.copy(patterns = listOf(pattern.copy(revision = 2,
                factContract = PatternFactContract(MessageFamily.TRANSFER_IN, MoneyDirection.INCOMING)))))
            show(workflow)
            compose.onNodeWithTag("pattern-edit").performScrollTo().performClick()
            val body = "Inward transfer (SARIE)\n100.00 SAR\nFrom TEST PERSON\nFrom BJAZ\nAccount\n02-09-2026 08:01\nRef. No. *8PJ7"
            compose.onNodeWithTag("pattern-body").performTextInput(body)
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithText(ar(R.string.teaching_next)).assertIsEnabled() }.isSuccess }
            click(R.string.teaching_next)
            capture("edit-fields")
            click(R.string.pattern_preview)
            click(R.string.pattern_save)
            compose.waitUntil(10_000) { store.load().banks.single().currentPatterns.single { it.id == pattern.id }.revision == 3 }
            val versions = store.load().banks.single().patterns.filter { it.id == pattern.id }
            assertEquals(listOf(1, 2, 3), versions.map { it.revision })
            assertNull(versions.last().factContract)
            assertNotNull(versions[1].factContract)
        }
    }

    @Test fun editRestoredAfterRotationStillSavesARevisionNotACopy() = runBlocking {
        fixture { workflow, store ->
            val patternsBefore = store.load().banks.single().currentPatterns
            val pattern = patternsBefore.single { it.id == "stc-sarie-in" }
            val restoration = StateRestorationTester(compose)
            show(workflow, openPattern = false, setContent = { restoration.setContent(it) })
            compose.onNodeWithTag("pattern-open-BANK_STC-stc-sarie-in").performScrollTo().performClick()
            compose.onNodeWithTag("pattern-edit").performScrollTo().performClick()
            val body = "Inward transfer (SARIE)\n100.00 SAR\nFrom TEST PERSON\nFrom BJAZ\nAccount\n02-09-2026 08:01\nRef. No. *8PJ7"
            compose.onNodeWithTag("pattern-body").performTextInput(body)
            // Rotation or process restore while editing must keep editing the same pattern.
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(ar(R.string.pattern_edit_title)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pattern-body").assertTextContains("Inward transfer (SARIE)", substring = true)
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithText(ar(R.string.teaching_next)).assertIsEnabled() }.isSuccess }
            click(R.string.teaching_next)
            click(R.string.pattern_preview)
            click(R.string.pattern_save)
            compose.waitUntil(10_000) { store.load().banks.single().currentPatterns.single { it.id == pattern.id }.revision == pattern.revision + 1 }
            assertEquals(patternsBefore.map { it.id }.sorted(), store.load().banks.single().currentPatterns.map { it.id }.sorted())
        }
    }

    private suspend fun fixture(block: suspend (MessagePatternsWorkflow, MessagePatternStore) -> Unit) {
        val directory = File(compose.activity.cacheDir, "pattern-management-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val bank = compose.activity.assets.open("bank-patterns/BANK_STC.patterns.json").bufferedReader().use {
                MessagePatternConfigurationCodec.decode(it.readText()).banks.single()
            }
            val store = MessagePatternStore(File(directory, "patterns.json"))
            store.installMissingBanks(listOf(bank))
            block(MessagePatternsWorkflow(store, DatabaseAccessGate(), bundledBanks = listOf(bank), requiredBundledBankIds = setOf(bank.bankId)), store)
        } finally { directory.deleteRecursively() }
    }
}
