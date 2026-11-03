
package com.baraa.masroof.presentation.dashboard

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.baraa.masroof.presentation.common.MasroofSectionHeader
import com.baraa.masroof.presentation.locale.formatLocalizedMoney

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.CreditCardDashboardRow
import com.baraa.masroof.application.dashboard.DebitCardOverview
import com.baraa.masroof.application.dashboard.aggregateDebitSalaryPeriodSpending
import java.time.ZoneId
import androidx.compose.foundation.layout.Row
import com.baraa.masroof.application.dashboard.aggregateCreditSalaryPeriodSpending
import com.baraa.masroof.application.dashboard.aggregateCreditStatementSpending

@Composable
fun CardsSummaryRoute(
    viewModel: DashboardViewModel,
    initialSelectedCardKey: String? = null,
    initialSelectedDebitKey: String? = null,
    detailBackExitsSummary: Boolean = false,
    onInitialSelectionConsumed: () -> Unit = {},
    onBack: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    onManageCard: (com.baraa.masroof.domain.model.Bank) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    var selectedCardKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDebitKey by rememberSaveable { mutableStateOf<String?>(null) }
    val followedFacilities = state.followedCreditFacilities()
    val cardNetworks = state.ownedCards.associate { CardOwnershipKey.of(it) to it.cardNetwork }

    androidx.compose.runtime.LaunchedEffect(initialSelectedCardKey, initialSelectedDebitKey) {
        when {
            initialSelectedCardKey != null -> {
                selectedCardKey = initialSelectedCardKey
                onInitialSelectionConsumed()
            }
            initialSelectedDebitKey != null -> {
                selectedDebitKey = initialSelectedDebitKey
                onInitialSelectionConsumed()
            }
        }
    }

    val selectedCard = selectedCardKey?.let { key ->
        followedFacilities?.facilities
            ?.flatMap { it.allCards }
            ?.find { CardOwnershipKey.of(it) == key }
    }
    val selectedDebit = selectedDebitKey?.let { key ->
        followedFacilities?.debitCards?.find { CardOwnershipKey.of(it) == key }
    }

    fun clearDetailSelection() {
        if (detailBackExitsSummary) {
            onBack()
        } else {
            selectedDebitKey = null
            selectedCardKey = null
        }
    }

    BackHandler {
        when {
            selectedDebit != null || selectedCard != null -> clearDetailSelection()
            else -> onBack()
        }
    }

    when {
        selectedDebit != null -> {
            DebitCardDetailScreen(
                debit = selectedDebit,
                state = state,
                cardNetwork = cardNetworks[CardOwnershipKey.of(selectedDebit)] ?: selectedDebit.network,
                onBack = { clearDetailSelection() },
                onOpenTransaction = onOpenTransaction,
                onManageCard = { onManageCard(selectedDebit.bank) },
            )
        }

        selectedCard != null -> {
            CardDetailScreen(
                row = selectedCard,
                state = state,
                onBack = { clearDetailSelection() },
                onOpenTransaction = onOpenTransaction,
                onManageCard = { onManageCard(selectedCard.bank) },
            )
        }

        else -> {
            CardsSummaryScreen(
                state = state,
                onBack = onBack,
                onOpenCard = { row -> selectedCardKey = CardOwnershipKey.of(row) },
                onOpenDebit = { debit -> selectedDebitKey = CardOwnershipKey.of(debit) },
                cardNetworksByLast4 = cardNetworks,
            )
        }
    }
}

@Composable
fun CardsSummaryScreen(
    state: DashboardUiState,
    onBack: () -> Unit,
    onOpenCard: (CreditCardDashboardRow) -> Unit,
    onOpenDebit: (DebitCardOverview) -> Unit,
    cardNetworksByLast4: Map<String, com.baraa.masroof.domain.model.CardNetwork?>,
) {
    val followedFacilities = state.followedCreditFacilities()
    var creditSelected by rememberSaveable(followedFacilities?.facilities?.isEmpty()) { mutableStateOf(followedFacilities?.facilities?.isNotEmpty() == true) }
    val locale = LocalConfiguration.current.locales[0]

    DashboardSummaryScaffold(
        title = stringResource(R.string.dashboard_cards_summary_screen_title),
        state = state,
        onBack = onBack,
        showPeriod = true,
    ) {
        if (followedFacilities == null || !followedFacilities.hasContent) {
            Text(
                stringResource(R.string.dashboard_cards_summary_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofReference.shortcutGap), verticalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofReference.shortcutGap)) {
                com.baraa.masroof.presentation.common.MasroofChoice(stringResource(R.string.reference_credit_tab), creditSelected, { creditSelected = true })
                com.baraa.masroof.presentation.common.MasroofChoice(stringResource(R.string.reference_mada_tab), !creditSelected, { creditSelected = false })
            }
            if (creditSelected && followedFacilities.facilities.isNotEmpty()) {
                CreditFacilitiesSummaryHero(followedFacilities, locale)
                com.baraa.masroof.presentation.common.MasroofMetricRow { tile ->
                    com.baraa.masroof.presentation.common.MasroofMetric(stringResource(R.string.reference_salary_spending),
                        formatLocalizedMoney(followedFacilities.aggregateCreditSalaryPeriodSpending()), com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.card, tile)
                    com.baraa.masroof.presentation.common.MasroofMetric(stringResource(R.string.reference_statement_spending),
                        formatLocalizedMoney(followedFacilities.aggregateCreditStatementSpending()), MaterialTheme.colorScheme.onSurface, tile)
                }
                SpendingTrendSection(state.spendingTrends?.creditCards, stringResource(R.string.approved_credit_daily), role = com.baraa.masroof.ui.theme.MasroofHeroRole.Card,
                    showAverage = true, chartKey = "credit")
            } else {
                val debitTrend = state.spendingTrends?.debitCards
                com.baraa.masroof.presentation.common.MasroofHeroCard(
                    stringResource(R.string.approved_total_expenses),
                    formatLocalizedMoney(followedFacilities.aggregateDebitSalaryPeriodSpending()),
                    subtitle = if (debitTrend?.spentWithoutRefunds == true) stringResource(R.string.entities_no_refunds) else null,
                    role = com.baraa.masroof.ui.theme.MasroofHeroRole.Account,
                )
                // With no refunds the two tiles would only repeat the hero amount.
                debitTrend?.takeIf { it.hasRefunds }?.let { trend ->
                    com.baraa.masroof.presentation.common.MasroofMetricRow { tile ->
                        com.baraa.masroof.presentation.common.MasroofMetric(stringResource(R.string.dashboard_expenses_before_refunds), formatLocalizedMoney(trend.totalBeforeRefunds),
                            com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.outflow, tile)
                        com.baraa.masroof.presentation.common.MasroofMetric(stringResource(R.string.reference_refunds), formatLocalizedMoney(trend.totalRefunds),
                            com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.inflow, tile)
                    }
                }
            }
            MasroofSectionHeader(title = stringResource(R.string.reference_followed_cards))
            if (creditSelected) followedFacilities.facilities.forEach { facility ->
                CreditFacilityCard(
                    facility = facility,
                    cardNetworksByLast4 = cardNetworksByLast4,
                    zoneId = ZoneId.systemDefault(),
                    ownedCards = state.ownedCards,
                    modifier = Modifier.fillMaxWidth(),
                    onOpenCard = onOpenCard,
                    trends = state.spendingTrends?.byCard.orEmpty(),
                )
            }
            if (!creditSelected) followedFacilities.debitCards.forEach { debit ->
                DebitCardSummaryTile(
                    debit = debit,
                    network = cardNetworksByLast4[CardOwnershipKey.of(debit)] ?: debit.network,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenDebit(debit) },
                    trend = state.spendingTrends?.byCard?.get(CardOwnershipKey.of(debit)),
                    ownedCards = state.ownedCards,
                )
            }
        }
    }
}
