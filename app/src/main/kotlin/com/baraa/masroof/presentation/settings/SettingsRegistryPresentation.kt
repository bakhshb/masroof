package com.baraa.masroof.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.presentation.navigation.SettingsDestination

enum class SettingsRegistryCategory {
    Accounts,
    Cards,
    Loans,
}

fun SettingsRegistryCategory.listDestination(): SettingsDestination =
    when (this) {
        SettingsRegistryCategory.Accounts -> SettingsDestination.MyAccounts
        SettingsRegistryCategory.Cards -> SettingsDestination.MyCards
        SettingsRegistryCategory.Loans -> SettingsDestination.MyLoans
    }

fun SettingsRegistryCategory.bankDestination(bankId: String): SettingsDestination =
    when (this) {
        SettingsRegistryCategory.Accounts -> SettingsDestination.BankAccounts(bankId)
        SettingsRegistryCategory.Cards -> SettingsDestination.BankCards(bankId)
        SettingsRegistryCategory.Loans -> SettingsDestination.BankLoans(bankId)
    }

fun SettingsRegistryCategory.banksWithContent(summaries: List<SettingsBankSummaryUi>): List<SettingsBankSummaryUi> =
    when (this) {
        SettingsRegistryCategory.Accounts -> summaries.filter { it.accountCount > 0 }
        SettingsRegistryCategory.Cards -> summaries.filter { it.cardCount > 0 }
        SettingsRegistryCategory.Loans -> summaries.filter { it.loanCount > 0 }
    }

fun SettingsRegistryCategory.singleBankDirectDestination(state: SettingsUiState): SettingsDestination? {
    val eligible = banksWithContent(state.bankSummaries)
    when {
        eligible.size == 1 -> return bankDestination(eligible.single().bank.id)
        state.bankSummaries.size == 1 -> return bankDestination(state.bankSummaries.single().bank.id)
        else -> return null
    }
}

@Composable
fun SettingsRegistryCategory.screenTitle(): String =
    when (this) {
        SettingsRegistryCategory.Accounts -> stringResource(R.string.settings_accounts_section)
        SettingsRegistryCategory.Cards -> stringResource(R.string.settings_cards_section)
        SettingsRegistryCategory.Loans -> stringResource(R.string.settings_loans_followed)
    }

@Composable
fun SettingsRegistryCategory.screenHint(): String =
    when (this) {
        SettingsRegistryCategory.Accounts -> stringResource(R.string.settings_registry_accounts_hint)
        SettingsRegistryCategory.Cards -> stringResource(R.string.settings_registry_cards_hint)
        SettingsRegistryCategory.Loans -> stringResource(R.string.settings_registry_loans_hint)
    }

@Composable
fun SettingsRegistryCategory.bankRowSubtitle(summary: SettingsBankSummaryUi): String =
    when (this) {
        SettingsRegistryCategory.Accounts -> formatRegistryCategorySubtitle(
            state = resolveRegistryCategorySubtitle(
                followed = summary.followedAccountCount,
                unregistered = summary.unregisteredAccountCount,
                stopped = summary.stoppedAccountCount,
            ),
            emptyLabel = stringResource(R.string.settings_accounts_empty),
            followedUnregisteredRes = R.plurals.settings_hub_accounts_subtitle,
            followedOnlyRes = R.plurals.settings_hub_accounts_subtitle_followed_only,
            stoppedOnlyRes = R.plurals.settings_hub_accounts_subtitle_stopped_only,
            followedStoppedRes = R.plurals.settings_hub_accounts_subtitle_followed_stopped,
        )
        SettingsRegistryCategory.Cards -> formatRegistryCategorySubtitle(
            state = resolveRegistryCategorySubtitle(
                followed = summary.followedCardCount,
                unregistered = summary.unregisteredCardCount,
                stopped = summary.stoppedCardCount,
            ),
            emptyLabel = stringResource(R.string.settings_cards_empty),
            followedUnregisteredRes = R.plurals.settings_hub_cards_subtitle,
            followedOnlyRes = R.plurals.settings_hub_cards_subtitle_followed_only,
            stoppedOnlyRes = R.plurals.settings_hub_cards_subtitle_stopped_only,
            followedStoppedRes = R.plurals.settings_hub_cards_subtitle_followed_stopped,
        )
        SettingsRegistryCategory.Loans -> if (summary.loanCount > 0) {
            pluralStringResource(R.plurals.settings_bank_summary_loans, summary.loanCount, summary.loanCount)
        } else {
            stringResource(R.string.settings_loans_empty)
        }
    }
