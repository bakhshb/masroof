package com.baraa.masroof.presentation.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.baraa.masroof.application.dashboard.DebitCardOverview

@Composable
fun DebitCardDetailScreen(
    debit: DebitCardOverview,
    state: DashboardUiState,
    cardNetwork: com.baraa.masroof.domain.model.CardNetwork?,
    onBack: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    /** Opens this bank's card settings (network, type, account, role). */
    onManageCard: (() -> Unit)? = null,
) {
    val cardTransactions = DashboardSummaryTransactionFilter.forDebitCard(
        transactions = state.allTransactions,
        bank = debit.bank,
        last4 = debit.last4,
        debitSpendInvolvementByTransactionId = state.transactionDebitSpendInvolvement,
    )
    val title = debit.displayLabel(state.ownedCards)

    DashboardSummaryScaffold(
        title = androidx.compose.ui.res.stringResource(com.baraa.masroof.R.string.reference_card_detail_title),
        state = state,
        onBack = onBack,
        showPeriod = false,
    ) {
        DebitCardDetailSummaryCard(
            debit = debit,
            network = cardNetwork,
            modifier = Modifier.fillMaxWidth(),
            trend = state.spendingTrends?.byCard?.get(CardOwnershipKey.of(debit)),
            ownedCards = state.ownedCards,
        )

        onManageCard?.let { manage ->
            com.baraa.masroof.presentation.common.MasroofOutlinedButton(manage, Modifier.fillMaxWidth()) {
                com.baraa.masroof.presentation.common.MasroofText(androidx.compose.ui.res.stringResource(com.baraa.masroof.R.string.settings_card_setup))
            }
        }
        val trend = state.spendingTrends?.byCard?.get(CardOwnershipKey.of(debit))
        trend?.let { CardReferenceAverage(it) }
        SpendingTrendSection(trend, androidx.compose.ui.res.stringResource(com.baraa.masroof.R.string.card_daily),
            role = com.baraa.masroof.ui.theme.MasroofHeroRole.Card,
            explanation = androidx.compose.ui.res.stringResource(com.baraa.masroof.R.string.dashboard_mada_trend_basis),
            chartKey = "card:${CardOwnershipKey.of(debit)}", scopeLabel = title)
        DashboardSummaryTransactionsSection(
            transactions = cardTransactions,
            onOpenTransaction = onOpenTransaction,
            ownedCards = state.ownedCards,
            title = androidx.compose.ui.res.stringResource(com.baraa.masroof.R.string.reference_card_movements),
        )
    }
}
