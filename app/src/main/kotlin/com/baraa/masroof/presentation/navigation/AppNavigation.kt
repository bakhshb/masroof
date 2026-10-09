package com.baraa.masroof.presentation.navigation

import com.baraa.masroof.application.notification.NotificationAction
import com.baraa.masroof.presentation.settings.SettingsRegistryCategory
import com.baraa.masroof.presentation.settings.SettingsUiState
import com.baraa.masroof.presentation.settings.listDestination
import com.baraa.masroof.presentation.settings.singleBankDirectDestination

internal enum class HomeDestination {
    Dashboard,
    AccountsSummary,
    CardsSummary,
    LoansSummary,
    MerchantsSummary,
    NotificationCenter,
    Review,
    AllTransactions,
    Settings,
}

internal enum class ManageSettingsTarget {
    Accounts,
    Cards,
    Loans,
}

/**
 * Open Settings on [destination], replacing the settings stack.
 *
 * Back from that screen leaves Settings and returns to the caller of
 * `openSettings`. Taps after landing push onto the stack and pop normally.
 */
data class SettingsLaunchRequest(
    val destination: SettingsDestination,
)

internal fun ManageSettingsTarget.toRegistryCategory(): SettingsRegistryCategory =
    when (this) {
        ManageSettingsTarget.Accounts -> SettingsRegistryCategory.Accounts
        ManageSettingsTarget.Cards -> SettingsRegistryCategory.Cards
        ManageSettingsTarget.Loans -> SettingsRegistryCategory.Loans
    }

internal fun resolvePendingDestination(
    pending: SettingsDestination,
    state: SettingsUiState,
): SettingsDestination =
    when (pending) {
        SettingsDestination.Banks -> SettingsDestination.MyAccounts
        SettingsDestination.MyAccounts,
        SettingsDestination.MyCards,
        SettingsDestination.MyLoans,
        SettingsDestination.MyCommitments,
        is SettingsDestination.CommitmentDetail,
        is SettingsDestination.BankAccounts,
        is SettingsDestination.BankCards,
        is SettingsDestination.BankLoans,
        is SettingsDestination.BankHub,
        SettingsDestination.App,
        SettingsDestination.DataBackup,
        SettingsDestination.StatementReconciliation,
        SettingsDestination.Hub,
        SettingsDestination.About,
        SettingsDestination.Logs,
        SettingsDestination.DesignCatalog,
        -> pending
    }

internal fun resolveManageSettingsLaunch(
    state: SettingsUiState,
    target: ManageSettingsTarget,
): SettingsLaunchRequest {
    val category = target.toRegistryCategory()
    category.singleBankDirectDestination(state)?.let { direct ->
        return SettingsLaunchRequest(direct)
    }
    return SettingsLaunchRequest(category.listDestination())
}

internal fun resolveNotificationSettingsLaunch(
    action: NotificationAction,
    state: SettingsUiState,
): SettingsLaunchRequest? =
    when (action) {
        NotificationAction.OPEN_SETTINGS_CARDS ->
            resolveManageSettingsLaunch(state, ManageSettingsTarget.Cards)

        NotificationAction.OPEN_SETTINGS_ACCOUNTS ->
            resolveManageSettingsLaunch(state, ManageSettingsTarget.Accounts)

        NotificationAction.OPEN_SETTINGS_ABOUT ->
            SettingsLaunchRequest(SettingsDestination.About)

        else -> null
    }

internal fun SettingsDestination.recoverRegistryListDestination(): SettingsDestination? =
    when (this) {
        is SettingsDestination.BankAccounts -> SettingsDestination.MyAccounts
        is SettingsDestination.BankCards -> SettingsDestination.MyCards
        is SettingsDestination.BankLoans -> SettingsDestination.MyLoans
        else -> null
    }
