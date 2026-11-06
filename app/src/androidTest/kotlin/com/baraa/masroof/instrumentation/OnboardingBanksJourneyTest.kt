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
import com.baraa.masroof.R
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.presentation.onboarding.*
import com.baraa.masroof.presentation.settings.SettingsViewModel
import com.baraa.masroof.presentation.settings.settingsViewModelFactory
import com.baraa.masroof.sms.datasource.InboxRow
import com.baraa.masroof.sms.datasource.SmsDataSource
import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.testsupport.TestStorageContext
import com.baraa.masroof.testsupport.ar
import com.baraa.masroof.ui.theme.MasroofTheme
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingBanksJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun click(id: Int) {
        val label = compose.ar(id)
        compose.waitForIdle()
        if (compose.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithText(label)
        if (!node.isDisplayed()) {
            if (compose.onAllNodes(hasText(label) and hasAnyAncestor(hasScrollToNodeAction())).fetchSemanticsNodes().isNotEmpty())
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
            else node.performScrollTo()
        }
        // Registry refreshes briefly disable actions after a manual entry is saved.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(label) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        node.assertIsEnabled().performClick()
        compose.waitForIdle()
    }
    private fun input(tag: String, value: String) {
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))).performTextInput(value)
    }
    private fun capture(name: String) = ScreenshotArtifactWriter.capture(compose, "onboarding-banks", name)

    @Test fun customBankOnly_nativeDatesAndSharedManualSheets_completeWithoutInventingTransactions() = runBlocking {
        val context = TestStorageContext(compose.activity, "onboarding-banks-${java.util.UUID.randomUUID()}")
        var permission = false
        val queries = java.util.concurrent.atomic.AtomicInteger()
        val source = object : SmsDataSource {
            override suspend fun <T> withInbox(receivedAfter: Instant?, block: suspend (Sequence<InboxRow>) -> T): T = block(queryInbox(receivedAfter))
            fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> { queries.incrementAndGet(); return emptySequence() }
        }
        val container = AppContainer(context, smsDataSourceOverride = source)
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, onboardingViewModelFactory(container, container.onboardingPreferencesRepository) { permission })[OnboardingViewModel::class.java]
        val settings = ViewModelProvider(store, settingsViewModelFactory(container, "test", { permission }))[SettingsViewModel::class.java]
        try {
            compose.setContent {
                val localized = AppLocaleContext.wrap(LocalContext.current, "ar")
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                    LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MasroofTheme { Surface { OnboardingRoute(vm, {}, {}, {}, settings) } }
                }
            }
            compose.waitUntil(10_000) { !vm.uiState.value.loadingBanks }
            capture("welcome")
            click(R.string.reference_start_setup)
            assertEquals(OnboardingStep.BANKS, vm.uiState.value.step)
            assertTrue(vm.uiState.value.selectedBankIds.isEmpty())
            compose.onNodeWithText(compose.ar(R.string.teaching_next)).assertIsNotEnabled()
            capture("choose-banks")
            click(R.string.settings_add_bank)
            input("bank-name", "بنك التجربة")
            input("bank-sender", "OnboardingTestBank")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            click(R.string.settings_save)
            compose.waitUntil(10_000) { vm.uiState.value.selectedBankIds.size == 1 }
            val bankId = vm.uiState.value.selectedBankIds.single()
            assertEquals("بنك التجربة", vm.uiState.value.bankCatalog?.banks?.single { it.bankId == bankId }?.displayName)
            click(R.string.teaching_next)
            assertEquals(OnboardingStep.PERMISSION, vm.uiState.value.step)
            assertEquals(0, queries.get())
            compose.runOnIdle { permission = true; vm.onPermissionResult(true); settings.refreshSmsPermissionState() }
            compose.waitUntil(10_000) { vm.uiState.value.step == OnboardingStep.IMPORT_DATE }
            compose.onNodeWithTag("import-from").performScrollTo().performClick()
            compose.onNodeWithTag("bank-import-date-picker").assertExists()
            compose.onNodeWithTag("sheet-cancel").performClick()
            compose.onNodeWithTag("import-through").performScrollTo().performClick()
            compose.onNodeWithTag("bank-import-date-picker").assertExists()
            compose.onNodeWithTag("sheet-apply").performClick()
            capture("date-range")
            assertEquals(setOf(bankId), container.onboardingPreferencesRepository.getSetupDraft()?.bankIds)
            click(R.string.onboarding_start_import)
            compose.waitUntil(10_000) { vm.uiState.value.step == OnboardingStep.OWNERSHIP && !settings.uiState.value.loading }
            assertEquals(1, queries.get())
            compose.onNodeWithText(compose.ar(R.string.onboarding_bank_teach_later)).performScrollTo().assertIsDisplayed()
            capture("bank-needs-teaching")

            click(R.string.onboarding_add_manual)
            click(R.string.settings_add_account_manual)
            compose.onNodeWithTag("sheet-apply").performClick()
            input("manual-last4", "3001")
            click(R.string.pattern_financial_account_owned)
            compose.onNodeWithTag("sheet-cancel").performClick()
            assertTrue(container.accountRegistryRepository.listAll().isEmpty())
            click(R.string.onboarding_add_manual)
            click(R.string.settings_add_account_manual)
            compose.onNodeWithTag("sheet-apply").performClick()
            input("manual-last4", "3001")
            click(R.string.pattern_financial_account_owned)
            compose.onNodeWithTag("sheet-apply").performClick()
            compose.waitUntil(10_000) { vm.uiState.value.ownedAccountsCount == 1 }
            click(R.string.onboarding_add_manual)
            click(R.string.settings_add_card_manual)
            compose.onNodeWithTag("sheet-apply").performClick()
            input("manual-last4", "8219")
            click(R.string.settings_manual_card_owned)
            compose.onNodeWithTag("sheet-apply").performClick()
            compose.onNodeWithTag("card-type-CREDIT").performScrollTo().performClick()
            capture("shared-card-setup")
            compose.onNodeWithTag("sheet-apply").performClick()
            compose.waitUntil(10_000) { vm.uiState.value.ownedCardsCount == 1 && !settings.uiState.value.updating }
            assertEquals(CardType.CREDIT, container.cardRegistryRepository.listAll().single().cardType)
            assertTrue(vm.uiState.value.accounts.all { !it.discoveredFromSms })
            assertTrue(vm.uiState.value.cards.all { !it.discoveredFromSms })
            assertTrue(container.financialTransactionRepository.listAll().isEmpty())
            capture("review-confirmed")
            click(R.string.onboarding_finalize)
            compose.waitUntil(10_000) { vm.uiState.value.step == OnboardingStep.FINALIZE }
            assertTrue(container.onboardingPreferencesRepository.isOnboardingCompleted())
            capture("complete")
            click(R.string.onboarding_enter_app)
            assertEquals(OnboardingStep.HOME, vm.uiState.value.step)
        } finally {
            store.clear()
            container.close()
            context.getDatabasePath("masroof.db").parentFile?.deleteRecursively()
        }
    }
}
