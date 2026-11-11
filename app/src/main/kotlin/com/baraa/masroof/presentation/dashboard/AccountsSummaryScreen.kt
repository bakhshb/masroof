package com.baraa.masroof.presentation.dashboard

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.baraa.masroof.presentation.common.MasroofSectionHeader


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.cashPosition
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.ui.theme.MasroofThemeExtras

@Composable
fun AccountsSummaryRoute(
    viewModel: DashboardViewModel,
    onBack: () -> Unit,
    onOpenTransaction: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var selectedAccountKey by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedAccount = selectedAccountKey?.let { key ->
        state.ownedAccounts.find { ownedAccountKey(it) == key }
    }

    BackHandler {
        when {
            selectedAccount != null -> selectedAccountKey = null
            else -> onBack()
        }
    }

    when {
        selectedAccount != null -> {
            AccountDetailScreen(
                account = selectedAccount,
                state = state,
                onBack = { selectedAccountKey = null },
                onOpenTransaction = onOpenTransaction,
            )
        }

        else -> {
            AccountsSummaryScreen(
                state = state,
                onBack = onBack,
                onOpenAccount = { account -> selectedAccountKey = ownedAccountKey(account) },
            )
        }
    }
}

private fun ownedAccountKey(account: OwnedAccountUi): String =
    "${account.bank.id}:${account.maskedNumber}"

@Composable
fun AccountsSummaryScreen(
    state: DashboardUiState,
    onBack: () -> Unit,
    onOpenAccount: (OwnedAccountUi) -> Unit,
) {
    DashboardSummaryScaffold(
        title = stringResource(R.string.dashboard_accounts_summary_screen_title),
        state = state,
        onBack = onBack,
    ) {
        DashboardSummaryHeroCard(
            spec = accountsSummaryHeroSpec(
                fleet = resolveDashboardAccountsFleet(
                    ownedAccounts = state.ownedAccounts,
                    fleet = state.accountsFleet,
                ),
            ),
        )

        SpendingTrendSection(state.spendingTrends?.accounts, stringResource(R.string.accounts_daily),
            explanation = stringResource(R.string.reference_accounts_scope), role = com.baraa.masroof.ui.theme.MasroofHeroRole.Account, chartKey = "accounts")
        MasroofSectionHeader(title = stringResource(R.string.reference_followed_accounts))

        val hierarchy = state.bankHierarchy
        if (state.ownedAccounts.isEmpty()) {
            Text(
                stringResource(R.string.dashboard_accounts_summary_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val accounts = if (hierarchy != null && hierarchy.hasContent) {
                hierarchy.banks.flatMap { bankTree ->
                    ((bankTree.savingsAccounts + bankTree.walletAccounts).map { it.bank to it.maskedNumber } +
                        bankTree.currentAccounts.map { it.bank to it.maskedNumber })
                        .mapNotNull { (bank, maskedNumber) -> resolveOwnedAccountUi(state.ownedAccounts, bank, maskedNumber) }
                }
            } else {
                state.ownedAccounts
            }
            // hasActivity is precomputed; accounts without a summary stay full cards.
            val (idle, active) = accounts.partition { it.periodSummary?.hasActivity == false }
            active.forEach { account -> AccountsSummaryAccountCard(account = account, onClick = { onOpenAccount(account) }) }
            if (idle.isNotEmpty()) {
                var expanded by rememberSaveable { mutableStateOf(false) }
                val stateLabel = stringResource(if (expanded) R.string.entities_state_expanded else R.string.entities_state_collapsed)
                val unknownLast4 = com.baraa.masroof.presentation.common.formatCardLast4(null)
                val masked = idle.mapNotNull { account ->
                    DashboardRegistryLabels.accountLast4(account.maskedNumber).takeIf { it != unknownLast4 }
                        ?.let { stringResource(R.string.dashboard_account_item, it) }
                }
                com.baraa.masroof.presentation.common.MasroofCard(androidx.compose.ui.Modifier
                    .semantics { stateDescription = stateLabel }
                    .clickable(onClickLabel = stringResource(if (expanded) R.string.entities_idle_collapse else R.string.entities_idle_expand),
                        role = androidx.compose.ui.semantics.Role.Button) { expanded = !expanded }) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.entities_no_activity), androidx.compose.ui.Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (masked.isNotEmpty()) Text(masked.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.material3.Icon(
                            if (expanded) androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp else androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                            null, androidx.compose.ui.Modifier.size(com.baraa.masroof.ui.theme.MasroofIconSizes.lg), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (expanded) idle.forEach { account -> AccountsSummaryAccountCard(account = account, onClick = { onOpenAccount(account) }) }
            }
        }
    }
}

private fun resolveOwnedAccountUi(
    ownedAccounts: List<OwnedAccountUi>,
    bank: Bank,
    maskedNumber: String,
): OwnedAccountUi? = ownedAccounts.find { it.bank == bank && it.maskedNumber == maskedNumber }

@Composable
private fun AccountsSummaryAccountCard(account: OwnedAccountUi, onClick: () -> Unit) {
    val movement = account.periodSummary?.cashPosition()
    val unavailable = stringResource(R.string.dashboard_value_unavailable)
    EntitySummaryCard(account.displayLabel(), "",
        MasroofIcons.moneyMovement, MasroofThemeExtras.extendedColors.account, onClick = onClick,
        guidance = stringResource(R.string.dashboard_account_remaining_calculated_hint), actionLabel = stringResource(R.string.reference_account_details), bankLabel = com.baraa.masroof.presentation.settings.settingsBankLabel(account.bank)) {
        com.baraa.masroof.presentation.common.MasroofDetailRow(stringResource(R.string.dashboard_remaining_title), movement?.remaining?.let { formatLocalizedMoney(it) } ?: unavailable, dense = true)
        com.baraa.masroof.presentation.common.MasroofDetailRow(stringResource(R.string.dashboard_total_inflow), movement?.inflow?.let { formatLocalizedMoney(it) } ?: unavailable, dense = true)
        com.baraa.masroof.presentation.common.MasroofDetailRow(stringResource(R.string.dashboard_total_spent), movement?.outflow?.let { formatLocalizedMoney(it) } ?: unavailable, dense = true)
    }
}
