package com.baraa.masroof.instrumentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.R
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.presentation.settings.SettingsRoute
import com.baraa.masroof.presentation.settings.SettingsViewModel
import com.baraa.masroof.presentation.settings.settingsViewModelFactory
import com.baraa.masroof.presentation.statement.StatementReconciliationViewModel
import com.baraa.masroof.sms.datasource.SmsDataSource
import com.baraa.masroof.sms.datasource.InboxRow
import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.testsupport.TestStorageContext
import com.baraa.masroof.ui.theme.MasroofTheme
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsBanksJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun ar(id: Int) = AppLocaleContext.wrap(compose.activity, "ar").getString(id)
    private fun click(id: Int) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(ar(id)).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            capture("missing-screen")
            throw error
        }
        compose.onNodeWithText(ar(id)).performScrollTo().performClick()
        compose.waitForIdle()
    }
    private fun back() {
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun input(tag: String, value: String) {
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))).performTextInput(value)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Compose idleness precedes the SurfaceFlinger frame (same capture protocol as the fidelity matrix).
        Thread.sleep(250)
        ScreenshotArtifactWriter.publish(instrumentation.uiAutomation.takeScreenshot(), "bank-settings", name)
    }

    @Test fun manageBankPickDatesAndSaveManualEntriesThroughTheUnifiedSettings() = runBlocking {
        val context = TestStorageContext(compose.activity, "settings-banks-${java.util.UUID.randomUUID()}")
        var permissionGranted = false
        val queries = java.util.concurrent.atomic.AtomicInteger()
        val source = object : SmsDataSource {
            override suspend fun <T> withInbox(receivedAfter: Instant?, block: suspend (Sequence<InboxRow>) -> T): T = block(queryInbox(receivedAfter))
            fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> { queries.incrementAndGet(); return emptySequence() }
        }
        val container = AppContainer(context, smsDataSourceOverride = source)
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, settingsViewModelFactory(container, "1.0-test", { permissionGranted }))[SettingsViewModel::class.java]
        val statement = StatementReconciliationViewModel(container.statementReconciliationWorkflow)
        try {
            compose.setContent {
                val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
                CompositionLocalProvider(LocalContext provides localized,
                    LocalConfiguration provides localized.resources.configuration,
                    LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MasroofTheme { Surface {
                        SettingsRoute(vm, statement, 0, {}, {}, onLocaleChanged = {}, onRequestExport = {},
                            onRequestImport = {}, onRequestExportLogs = {})
                    } }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(ar(R.string.settings_banks_title)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(ar(R.string.settings_statement_reconciliation_title)).assertDoesNotExist()
            capture("settings-hub")
            click(R.string.settings_banks_title)
            click(R.string.settings_add_bank)
            input("bank-name", "بنك التجربة")
            input("bank-sender", "TestBank")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            click(R.string.settings_save)
            compose.waitUntil(10_000) { runBlocking { container.messagePatternsWorkflow.catalog().banks.any { it.displayName == "بنك التجربة" } } }
            val bankId = container.messagePatternsWorkflow.catalog().banks.single { it.displayName == "بنك التجربة" }.bankId
            compose.waitUntil(10_000) { compose.onAllNodesWithText(ar(R.string.pattern_import_bank)).fetchSemanticsNodes().isNotEmpty() }
            capture("new-bank")
            click(R.string.pattern_import_bank)
            compose.onNodeWithTag("import-selected-sms").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { permissionGranted = true; vm.refreshSmsPermissionState() }
            compose.onNodeWithTag("import-selected-sms").assertIsEnabled()
            assertEquals(0, queries.get())
            compose.onNodeWithTag("import-from").performScrollTo().performClick()
            compose.onNodeWithTag("bank-import-date-picker").assertExists()
            capture("date-picker")
            compose.onNodeWithTag("sheet-cancel").performClick()
            compose.onNodeWithTag("import-through").performScrollTo().performClick()
            compose.onNodeWithTag("bank-import-date-picker").assertExists()
            compose.onNodeWithTag("sheet-apply").performClick()
            compose.onNodeWithTag("import-selected-sms").performScrollTo().performClick()
            compose.waitUntil(10_000) { vm.uiState.value.scopedSmsImportResult != null }
            assertEquals(0, vm.uiState.value.scopedSmsImportResult?.inserted)
            compose.waitUntil(10_000) { !vm.uiState.value.importingSms }
            compose.onNodeWithTag("import-selected-sms").assertIsEnabled()
            back()
            click(R.string.settings_bank_accounts_title)
            click(R.string.settings_add_account_manual)
            input("manual-last4", "3001")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            click(R.string.pattern_financial_account_owned)
            compose.onNodeWithTag("sheet-apply").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { runBlocking { container.accountRegistryRepository.listAll().any { it.bank.id == bankId } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("masroof-bottom-sheet").fetchSemanticsNodes().isEmpty() }
            back()
            click(R.string.settings_bank_cards_title)
            click(R.string.settings_add_card_manual)
            input("manual-last4", "8219")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            click(R.string.settings_manual_card_owned)
            compose.onNodeWithTag("sheet-apply").assertIsEnabled().performClick()
            compose.onNodeWithTag("card-type-CREDIT").performScrollTo().performClick()
            compose.onNodeWithTag("sheet-apply").performClick()
            compose.waitUntil(10_000) { runBlocking { container.cardRegistryRepository.get(CardReference(Bank(bankId), "8219"))?.cardType == CardType.CREDIT } }
            assertTrue(container.financialTransactionRepository.listAll().isEmpty())
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("masroof-bottom-sheet").fetchSemanticsNodes().isEmpty() }
            back(); back(); back()
            click(R.string.settings_about_section)
            compose.onNodeWithText("بنك التجربة").performScrollTo().assertIsDisplayed()
            capture("about-support")
            click(R.string.settings_updates_title)
            compose.onNodeWithText(ar(R.string.settings_current_version).replace("%1\$s", "1.0-test")).assertExists()
            capture("updates-version")
        } finally {
            store.clear()
            container.close()
            context.getDatabasePath("masroof.db").parentFile?.deleteRecursively()
        }
    }
}
