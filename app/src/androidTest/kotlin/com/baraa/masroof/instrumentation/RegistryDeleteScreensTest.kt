package com.baraa.masroof.instrumentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.presentation.settings.*
import com.baraa.masroof.ui.theme.MasroofTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Every tab's Delete action on the account, card and loan screens opens the dialog for exactly that item. */
class RegistryDeleteScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val bank = Bank.BANK_ALJAZIRA
    private val tabs = listOf(
        OwnershipStatus.OWNED to R.string.reference_followed,
        OwnershipStatus.UNKNOWN to R.string.reference_unconfirmed,
        OwnershipStatus.EXTERNAL to R.string.reference_stopped,
    )
    private fun ar(id: Int) = AppLocaleContext.wrap(compose.activity, "ar").getString(id)
    private var state by mutableStateOf(SettingsUiState(loading = false))
    private val requested = mutableListOf<StopTrackingTarget>()
    private val actions = RegistryDeleteActions(
        onRequest = { target ->
            requested += target
            state = state.copy(deleteRequest = RegistryDeleteUi(target, emptyList(), 0, null))
        },
        onDismiss = { state = state.copy(deleteRequest = null) },
        onConfirm = {},
        onStopInstead = {},
    )

    private fun show(screen: @Composable () -> Unit) {
        compose.setContent {
            val context = AppLocaleContext.wrap(LocalContext.current, "ar")
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
                LocalLayoutDirection provides LayoutDirection.Rtl) {
                MasroofTheme { Surface { screen() } }
            }
        }
    }

    /** Presses Delete on each tab and returns the targets in tab order, checking the dialog names that item. */
    private fun deleteOnEveryTab(names: List<String>): List<StopTrackingTarget> {
        tabs.forEachIndexed { index, (_, tab) ->
            compose.onNodeWithText(ar(tab)).performClick()
            // Followed cards keep Delete in the row's overflow menu.
            if (compose.onAllNodesWithTag("registry-more-actions").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("registry-more-actions").performScrollTo().performClick()
            compose.onNodeWithTag("registry-delete-action").performScrollTo().performClick()
            compose.onNodeWithTag("registry-delete-name").assert(hasText(names[index], substring = true))
            compose.onNodeWithTag("registry-delete-cancel").performClick()
            compose.waitForIdle()
        }
        return requested.toList()
    }

    @Test fun accountScreenDeletesTheItemOfEachTab() {
        val accounts = tabs.mapIndexed { index, (ownership, _) ->
            ManagedAccountUi("areg_$index", bank, "300$index", ownership, displayName = "Account $ownership")
        }
        state = state.copy(followedAccounts = listOf(accounts[0]), unregisteredAccounts = listOf(accounts[1]),
            stoppedAccounts = listOf(accounts[2]))
        show {
            SettingsBankAccountsScreen(bank, state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, deleteActions = actions)
        }
        assertEquals(accounts, deleteOnEveryTab(accounts.map { it.displayName!! })
            .map { (it as StopTrackingTarget.Account).account })
    }

    @Test fun cardScreenDeletesTheItemOfEachTab() {
        val cards = tabs.mapIndexed { index, (ownership, _) ->
            ManagedCardUi("creg_$index", bank, "727$index", ownership, displayName = "Card $ownership")
        }
        state = state.copy(followedCards = listOf(cards[0]), unregisteredCards = listOf(cards[1]), stoppedCards = listOf(cards[2]))
        show {
            SettingsBankCardsScreen(bank, state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, deleteActions = actions)
        }
        assertEquals(cards, deleteOnEveryTab(cards.map { it.displayName!! }).map { (it as StopTrackingTarget.Card).card })
    }

    @Test fun loanScreenDeletesTheItemOfEachTab() {
        val loans = tabs.mapIndexed { index, (ownership, _) ->
            ManagedLoanUi("lreg_$index", bank, LoanType.entries[index], ownership, displayName = "Loan $ownership")
        }
        state = state.copy(loans = loans)
        show {
            SettingsBankLoansScreen(bank, state, {}, {}, {}, {}, {}, {}, {}, deleteActions = actions)
        }
        assertEquals(loans, deleteOnEveryTab(loans.map { it.displayName!! }).map { (it as StopTrackingTarget.Loan).loan })
    }
}
