package com.baraa.masroof.instrumentation

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.presentation.settings.*
import com.baraa.masroof.ui.theme.MasroofTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CardSetupInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val language get() = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("language") ?: "ar"
    @Composable private fun TestTheme(content: @Composable () -> Unit) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val localized = com.baraa.masroof.presentation.locale.AppLocaleContext.wrap(context, language)
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalContext provides localized,
            androidx.compose.ui.platform.LocalConfiguration provides localized.resources.configuration,
            androidx.compose.ui.platform.LocalLayoutDirection provides if (language == "ar") androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
        ) { MasroofTheme { content() } }
    }
    private val bank = Bank.BANK_ALJAZIRA
    private val legacy = ManagedCardUi("card", bank, "1234", OwnershipStatus.OWNED,
        cardNetwork = CardNetwork.MADA, cardType = CardType.DEBIT, linkedAccountMaskedNumber = "3001")

    @Test fun debitCardHasOneSetupActionAndStillShowsAccountChoices() = verifySingleSetupAction(legacy)

    @Test fun creditCardHasOneSetupActionAndStillShowsRoles() = verifySingleSetupAction(
        legacy.copy(cardNetwork = CardNetwork.VISA, cardType = CardType.CREDIT,
            cardRole = CardRole.STANDALONE, linkedAccountMaskedNumber = null),
    )

    private fun verifySingleSetupAction(card: ManagedCardUi) {
        var stopped = false
        compose.setContent { TestTheme {
            var target by remember { mutableStateOf<ManagedCardUi?>(null) }
            SettingsRegistryCardRow(card, false, { stopped = true }, {}, { target = it }, {})
            SettingsCardSetupSheet(target, null, listOf(card), emptyList(), false, false, { target = null }, {})
        } }
        // The overflow menu is a popup outside the test's locale override, so its items are found by tag.
        compose.onNodeWithTag("registry-more-actions").performClick()
        compose.onNodeWithTag("registry-stop-tracking-action").performClick()
        compose.runOnIdle { assertTrue(stopped) }
        compose.onNodeWithTag("registry-more-actions").performClick()
        compose.onAllNodesWithTag("registry-card-setup-action").assertCountEquals(1)
        compose.onNodeWithTag("registry-card-setup-action").performClick()
        if (card.cardType == CardType.CREDIT) {
            compose.onNodeWithTag("card-role-PRIMARY").assertExists()
            compose.onNodeWithTag("card-account-").assertDoesNotExist()
        } else {
            compose.onNodeWithTag("card-account-").assertExists()
            compose.onNodeWithTag("card-role-PRIMARY").assertDoesNotExist()
        }
    }

    @Test fun correctingLegacyCardToVisaCreditShowsRolesAndDropsAccountFromDraft() {
        var saved: CardConfiguration? = null
        compose.setContent { TestTheme {
            SettingsCardSetupSheet(legacy, CardType.CREDIT, listOf(legacy), emptyList(), false, false, {}, { saved = it })
        } }
        compose.onNodeWithTag("card-network-VISA").performScrollTo().performClick()
        compose.onNodeWithTag("card-type-CREDIT").performScrollTo().performClick()
        compose.onNodeWithTag("card-role-PRIMARY").performScrollTo().performClick()
        compose.onNodeWithTag("card-account-").assertDoesNotExist()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).takeScreenshot(
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "card-setup-credit-$language.png"))
        compose.onNodeWithTag("sheet-apply").performClick()
        compose.runOnIdle {
            assertEquals(CardConfiguration(CardNetwork.VISA, CardType.CREDIT, CardRole.PRIMARY), saved)
        }
    }

    @Test fun choosingMadaShowsSameBankAccountsAndNoCreditRole() {
        var saved: CardConfiguration? = null
        val card = legacy.copy(cardNetwork = CardNetwork.VISA, cardType = CardType.CREDIT, cardRole = CardRole.STANDALONE)
        val account = ManagedAccountUi("account", bank, "3001", OwnershipStatus.OWNED)
        val foreign = account.copy(id = "foreign", bank = Bank("OTHER"), maskedNumber = "9999")
        compose.setContent { TestTheme {
            SettingsCardSetupSheet(card, null, listOf(card), listOf(account, foreign), false, false, {}, { saved = it })
        } }
        compose.onNodeWithTag("card-network-MADA").performScrollTo().performClick()
        compose.onNodeWithTag("card-role-PRIMARY").assertDoesNotExist()
        compose.onNodeWithTag("card-type-CREDIT").assertDoesNotExist()
        compose.onNodeWithTag("card-account-9999").assertDoesNotExist()
        compose.onNodeWithTag("card-account-3001").performScrollTo().performClick()
        compose.onNodeWithTag("sheet-apply").performClick()
        compose.runOnIdle { assertEquals(CardConfiguration(CardNetwork.MADA, CardType.DEBIT,
            linkedAccount = AccountReference(bank, "3001")), saved) }
    }

    @Test fun cancelDiscardsNetworkTypeAndRoleChanges() {
        var saved: CardConfiguration? = null
        var open by mutableStateOf(true)
        compose.setContent { TestTheme {
            if (open) SettingsCardSetupSheet(legacy, null, listOf(legacy), emptyList(), false, false, { open = false }, { saved = it })
        } }
        compose.onNodeWithTag("card-network-VISA").performScrollTo().performClick()
        compose.onNodeWithTag("card-type-CREDIT").performScrollTo().performClick()
        compose.onNodeWithTag("sheet-cancel").performClick()
        compose.runOnIdle { assertFalse(open); assertNull(saved); open = true }
        compose.onNodeWithTag("card-network-MADA").performScrollTo().assertIsOn()
        compose.onNodeWithTag("card-type-CREDIT").assertDoesNotExist()
    }

    @Test fun unknownVisaNeedsExplicitTypeInsteadOfAssumingCreditOrDebit() {
        val card = legacy.copy(cardNetwork = null, cardType = null, linkedAccountMaskedNumber = null)
        compose.setContent { TestTheme {
            SettingsCardSetupSheet(card, null, listOf(card), emptyList(), false, false, {}, {})
        } }
        compose.onNodeWithTag("card-network-VISA").performScrollTo().performClick()
        compose.onNodeWithTag("sheet-apply").assertIsNotEnabled()
        compose.onNodeWithTag("card-role-PRIMARY").assertDoesNotExist()
        compose.onNodeWithTag("card-type-DEBIT").performScrollTo().performClick()
        compose.onNodeWithTag("card-account-").performScrollTo().assertExists()
        compose.onNodeWithTag("sheet-apply").assertIsEnabled()
    }

    @Test fun parentWithChildrenCannotBeConverted() {
        val parent = legacy.copy(cardNetwork = CardNetwork.VISA, cardType = CardType.CREDIT,
            cardRole = CardRole.PRIMARY, linkedAccountMaskedNumber = null)
        val child = legacy.copy(id = "child", last4 = "5678", cardType = CardType.CREDIT,
            cardRole = CardRole.SUPPLEMENTARY, parentCardLast4 = "1234")
        compose.setContent { TestTheme {
            SettingsCardSetupSheet(parent, null, listOf(parent, child), emptyList(), false, false, {}, {})
        } }
        compose.onNodeWithTag("card-network-MADA").performScrollTo().performClick()
        compose.onNodeWithTag("sheet-apply").assertIsNotEnabled()
    }

    @Test fun supplementaryRequiresAConfirmedPrimaryInTheSameBank() {
        var saved: CardConfiguration? = null
        val card = legacy.copy(cardNetwork = CardNetwork.VISA, cardType = CardType.CREDIT, cardRole = CardRole.STANDALONE)
        val parent = card.copy(id = "parent", last4 = "5678", cardRole = CardRole.PRIMARY)
        val foreign = parent.copy(id = "foreign", bank = Bank("OTHER"), last4 = "9999")
        compose.setContent { TestTheme {
            SettingsCardSetupSheet(card, null, listOf(card, parent, foreign), emptyList(), false, false, {}, { saved = it })
        } }
        compose.onNodeWithTag("card-role-SUPPLEMENTARY").performScrollTo().performClick()
        compose.onNodeWithTag("sheet-apply").assertIsNotEnabled()
        compose.onNodeWithTag("card-primary-9999").assertDoesNotExist()
        compose.onNodeWithTag("card-primary-5678").performScrollTo().performClick()
        compose.onNodeWithTag("sheet-apply").performClick()
        compose.runOnIdle { assertEquals(CardConfiguration(CardNetwork.VISA, CardType.CREDIT,
            CardRole.SUPPLEMENTARY, "5678"), saved) }
    }
}
