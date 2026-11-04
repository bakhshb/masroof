package com.baraa.masroof.presentation.navigation

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.presentation.settings.SettingsBankSummaryUi
import com.baraa.masroof.presentation.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNavigationTest {
    private val alJazira = Bank("BANK_ALJAZIRA")
    private val rajhi = Bank("BANK_RAJHI")

    private fun summary(bank: Bank) = SettingsBankSummaryUi(
        bank = bank,
        followedAccountCount = 1,
        unregisteredAccountCount = 0,
        stoppedAccountCount = 0,
        followedCardCount = 1,
        unregisteredCardCount = 0,
        stoppedCardCount = 0,
        loanCount = 0,
    )

    @Test
    fun resolveManageSettingsLaunch_singleBank_opensTargetScreenDirectly() {
        val state = SettingsUiState(
            bankSummaries = listOf(summary(alJazira)),
        )

        assertEquals(
            SettingsLaunchRequest(SettingsDestination.BankCards(alJazira.id)),
            resolveManageSettingsLaunch(state, ManageSettingsTarget.Cards),
        )
        assertEquals(
            SettingsLaunchRequest(SettingsDestination.BankAccounts(alJazira.id)),
            resolveManageSettingsLaunch(state, ManageSettingsTarget.Accounts),
        )
        assertEquals(
            SettingsLaunchRequest(SettingsDestination.BankLoans(alJazira.id)),
            resolveManageSettingsLaunch(state, ManageSettingsTarget.Loans),
        )
    }

    @Test
    fun resolveManageSettingsLaunch_multipleBanks_opensCategoryList() {
        val state = SettingsUiState(
            bankSummaries = listOf(summary(alJazira), summary(rajhi)),
        )

        assertEquals(
            SettingsLaunchRequest(SettingsDestination.MyCards),
            resolveManageSettingsLaunch(state, ManageSettingsTarget.Cards),
        )
        assertEquals(
            SettingsLaunchRequest(SettingsDestination.MyAccounts),
            resolveManageSettingsLaunch(state, ManageSettingsTarget.Accounts),
        )
        assertEquals(
            SettingsLaunchRequest(SettingsDestination.MyLoans),
            resolveManageSettingsLaunch(state, ManageSettingsTarget.Loans),
        )
    }

    @Test
    fun resolveNotificationSettingsLaunch_about_returnsAboutDestination() {
        val state = SettingsUiState(bankSummaries = listOf(summary(alJazira)))

        assertEquals(
            SettingsLaunchRequest(SettingsDestination.About),
            resolveNotificationSettingsLaunch(
                com.baraa.masroof.application.notification.NotificationAction.OPEN_SETTINGS_ABOUT,
                state,
            ),
        )
    }

    @Test
    fun resolveNotificationSettingsLaunch_markReadOnly_returnsNull() {
        val state = SettingsUiState(bankSummaries = listOf(summary(alJazira)))

        assertNull(
            resolveNotificationSettingsLaunch(
                com.baraa.masroof.application.notification.NotificationAction.MARK_READ_ONLY,
                state,
            ),
        )
    }
}
