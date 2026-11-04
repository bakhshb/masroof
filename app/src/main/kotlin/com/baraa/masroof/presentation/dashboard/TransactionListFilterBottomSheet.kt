package com.baraa.masroof.presentation.dashboard

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.presentation.common.*

/** Filter choices are drafts until Apply. All dismissal paths discard them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListFilterBottomSheet(visible: Boolean, onDismiss: () -> Unit,
    filterState: TransactionListFilterState, availableTypes: List<FinancialTransactionType>,
    availableCards: List<String>, availableAccounts: List<String>, ownedCards: List<OwnedCardUi> = emptyList(),
    ownedAccounts: List<OwnedAccountUi> = emptyList(), onApply: (TransactionListFilterState) -> Unit) {
    if (!visible) return
    var types by rememberSaveable { mutableStateOf(filterState.types.map { it.name }) }
    var cards by rememberSaveable { mutableStateOf(filterState.cardKeys.toList()) }
    var accounts by rememberSaveable { mutableStateOf(filterState.accountContainerIds.toList()) }
    var resetAll by rememberSaveable { mutableStateOf(false) }
    val draft = filterState.copy(types = types.mapNotNull { runCatching { FinancialTransactionType.valueOf(it) }.getOrNull() }.toSet(),
        cardKeys = cards.toSet(), accountContainerIds = accounts.toSet(),
        searchQuery = if (resetAll) "" else filterState.searchQuery)
    MasroofBottomSheet(stringResource(R.string.transaction_list_filter_sheet_title), onDismiss,
        onApply = { onApply(draft); onDismiss() }, applyLabel = stringResource(R.string.transaction_list_filter_apply),
        resetLabel = stringResource(R.string.transaction_list_clear_filters),
        onReset = { types = emptyList(); cards = emptyList(); accounts = emptyList(); resetAll = true }) {
        MasroofGuidanceLabel(stringResource(R.string.transaction_list_filter_sheet_title),
            stringResource(R.string.transaction_list_multi_filter_hint))
        if (availableTypes.isNotEmpty()) {
            MasroofText(stringResource(R.string.transaction_list_filter_type_section), style = MaterialTheme.typography.titleSmall)
            FilterChip(types.isEmpty(), onClick = { types = emptyList() }, label = { MasroofText(stringResource(R.string.transaction_list_filter_all_types)) })
            MasroofSheetChoices(availableTypes, draft.types, { transactionTypeLabel(it) },
                { value -> types = if (value.name in types) types - value.name else types + value.name }, { "filter-type-${it.name}" })
        }
        if (availableAccounts.isNotEmpty()) {
            MasroofText(stringResource(R.string.transaction_list_filter_account_section), style = MaterialTheme.typography.titleSmall)
            FilterChip(accounts.isEmpty(), onClick = { accounts = emptyList() }, label = { MasroofText(stringResource(R.string.transaction_list_filter_all_accounts)) })
            MasroofSheetChoices(availableAccounts, draft.accountContainerIds, { accountDisplayLabel(ownedAccounts, it) ?: it },
                { value -> accounts = if (value in accounts) accounts - value else accounts + value }, { "filter-account-$it" })
        }
        if (availableCards.isNotEmpty()) {
            MasroofText(stringResource(R.string.transaction_list_filter_card_section), style = MaterialTheme.typography.titleSmall)
            FilterChip(cards.isEmpty(), onClick = { cards = emptyList() }, label = { MasroofText(stringResource(R.string.transaction_list_filter_all_cards)) })
            MasroofSheetChoices(availableCards, draft.cardKeys, { cardKeyDisplayLabel(ownedCards, it) },
                { value -> cards = if (value in cards) cards - value else cards + value }, { "filter-card-$it" })
        }
    }
}
