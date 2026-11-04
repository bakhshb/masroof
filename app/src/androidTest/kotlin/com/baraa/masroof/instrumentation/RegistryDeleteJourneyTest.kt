package com.baraa.masroof.instrumentation

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.baraa.masroof.R
import com.baraa.masroof.application.settings.DeleteBlocker
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.presentation.settings.LinkedTransactionUi
import com.baraa.masroof.presentation.settings.ManagedAccountUi
import com.baraa.masroof.presentation.settings.RegistryDeleteUi
import com.baraa.masroof.presentation.settings.SettingsDeleteDialog
import com.baraa.masroof.presentation.settings.StopTrackingTarget
import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.ui.theme.MasroofTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** Deleting an account, card or loan needs the pasted exact name; linked items are refused with stop tracking offered. */
class RegistryDeleteJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val name = "حساب الرواتب"
    private fun ar(id: Int) = AppLocaleContext.wrap(compose.activity, "ar").getString(id)
    private fun account(ownership: OwnershipStatus = OwnershipStatus.OWNED) =
        ManagedAccountUi("areg_1", Bank.BANK_ALJAZIRA, "3001", ownership, displayName = name)

    private fun show(request: RegistryDeleteUi, onDelete: () -> Unit = {}, onStop: () -> Unit = {}) {
        compose.setContent {
            val context = AppLocaleContext.wrap(LocalContext.current, "ar")
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
                LocalLayoutDirection provides LayoutDirection.Rtl) {
                MasroofTheme { Surface { SettingsDeleteDialog(request, name, false, {}, onDelete, onStop) } }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) = ScreenshotArtifactWriter.capture(compose, "registry-delete", name)

    @Test fun unlinkedAccountDeletesOnlyAfterTheCopiedNameIsPasted() {
        var deleted = false
        show(RegistryDeleteUi(StopTrackingTarget.Account(account()), emptyList(), 0, null), onDelete = { deleted = true })
        compose.onNodeWithText(ar(R.string.registry_delete_account_title)).assertIsDisplayed()
        compose.onNodeWithTag("registry-delete-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("registry-delete-confirmation").performTextInput("اسم آخر")
        compose.onNodeWithTag("registry-delete-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("registry-delete-confirmation").performTextClearance()
        compose.onNodeWithTag("registry-delete-copy").performClick()
        compose.waitForIdle()
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(name, clipboard.primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString())
        compose.onNodeWithTag("registry-delete-confirmation").performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.onNodeWithTag("registry-delete-confirm").assertIsEnabled()
        capture("account-delete-matched")
        compose.onNodeWithTag("registry-delete-confirm").performClick()
        compose.runOnIdle { assertTrue(deleted) }
    }

    @Test fun linkedAccountIsRefusedWithItsTransactionsAndOffersStopTracking() {
        var stopped = false
        val linked = listOf(
            LinkedTransactionUi("tx-1", "TEST SHOP", FinancialTransactionType.EXPENSE, Money.of("25.00", Currency.SAR), LocalDate.of(2026, 9, 10)),
            LinkedTransactionUi("tx-2", null, FinancialTransactionType.FEE, Money.of("40.00", Currency.SAR), LocalDate.of(2026, 9, 2)),
        )
        show(RegistryDeleteUi(StopTrackingTarget.Account(account()), linked, 5, DeleteBlocker.LINKED_TRANSACTIONS), onStop = { stopped = true })
        compose.onNodeWithTag("registry-delete-blocked-name").assertTextEquals(name)
        compose.onAllNodesWithTag("registry-delete-linked").assertCountEquals(2)
        compose.onNodeWithText("TEST SHOP").assertIsDisplayed()
        compose.onNodeWithText(ar(R.string.txn_type_fee)).assertIsDisplayed()
        // Resolved in the app language, not the dialog window's default locale.
        compose.onNodeWithText(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy",
            com.baraa.masroof.application.locale.AppLocale.displayLocale("ar")).format(LocalDate.of(2026, 9, 2))).assertIsDisplayed()
        compose.onNodeWithTag("registry-delete-confirm").assertDoesNotExist()
        compose.onNodeWithTag("registry-delete-confirmation").assertDoesNotExist()
        capture("account-delete-blocked")
        compose.onNodeWithTag("registry-delete-stop").performClick()
        compose.runOnIdle { assertTrue(stopped) }
    }

    @Test fun anAlreadyStoppedLinkedItemOnlyOffersClose() {
        show(RegistryDeleteUi(StopTrackingTarget.Account(account(OwnershipStatus.EXTERNAL)), emptyList(), 0, DeleteBlocker.MESSAGE_PATTERN))
        compose.onNodeWithText(ar(R.string.registry_delete_blocked_pattern)).assertIsDisplayed()
        compose.onNodeWithTag("registry-delete-stop").assertDoesNotExist()
        compose.onNodeWithTag("registry-delete-close").assertIsDisplayed()
    }
}
