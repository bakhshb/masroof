package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text
import com.baraa.masroof.lab.beta.presentation.approved.*
import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeLabel as financialTypeLabel
import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeIcon as reviewTypeIcon
import com.baraa.masroof.lab.beta.application.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.FinancialType
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.dashboard.*
import com.baraa.masroof.ui.theme.*
import java.time.format.DateTimeFormatter

/** One source-derived movement renderer for account, card, loan, merchant and daily scopes. */
@Composable
internal fun BetaScopeTransactionRows(snapshot: DashboardSnapshot, rows: List<TransactionFacts>,title: String,
    enabled: Boolean,onTransaction: (String)->Unit,complete: Boolean,account: AssetId?=null, showTitle: Boolean=true, tagPrefix: String="dash-transaction") {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
        if (showTitle) ApprovedSectionTitle(title)
        when {
            !complete -> Text(stringResource(R.string.beta_dash_unavailable_help), Modifier.testTag("transactions-unavailable"))
            rows.isEmpty() -> Text(stringResource(R.string.beta_approved_transactions_empty), Modifier.testTag("transactions-empty"),
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            else -> MasroofCard {
                rows.forEach { row ->
                    BetaMovementRow(snapshot,row,enabled,onTransaction,account,tagPrefix)
                }
            }
        }
    }
}

/** Canonical source TransactionPreviewRow shared by lists and entity scopes. */
@Composable internal fun BetaMovementRow(snapshot: DashboardSnapshot,row: TransactionFacts,enabled: Boolean,
    onTransaction: (String)->Unit,account: AssetId?=null,tagPrefix: String="dash-transaction") {
    val language=LocalConfiguration.current.locales[0].toLanguageTag()
    val locale=LocalConfiguration.current.locales[0]
    val labels=dashboardAssetLabels(snapshot)
                    val type = row.effectiveType
                    val direction = transactionDirection(row, account)
                    val inward = direction == BetaTransactionDirection.INWARD
                    val neutral = direction == BetaTransactionDirection.NEUTRAL
                    val colors = MasroofThemeExtras.extendedColors
                    val (color, background) = when (type) {
                        FinancialType.LOAN_REPAYMENT -> colors.liability to colors.liabilitySoft
                        FinancialType.CREDIT_CARD_PAYMENT -> colors.card to colors.cardSoft
                        else -> if (inward) colors.inflow to colors.inflowSoft else if (neutral) colors.account to colors.accountSoft else colors.outflow to colors.outflowSoft
                    }
                    val label = stringResource(financialTypeLabel(type))
                    val cardLabel=row.cards.singleOrNull()?.let(labels::get) ?: row.primaryCardLast4?.let {"•• $it"}
                    val value = MasroofMoneyDisplay.format(row.amount.amount, row.amount.currency.name, language)
                    DashboardTransactionRow(row.merchant ?: row.counterparty ?: label,
                        label + (cardLabel?.let { " · $it" } ?: ""),
                        (if (inward) "+" else if (neutral) "" else "−") + value.substringBeforeLast(' '),
                        DateTimeFormatter.ofPattern("d MMM", locale).format(row.time.instant.atZone(row.time.zone).toLocalDate()) + " · " + value.substringAfterLast(' '),
                        reviewTypeIcon(type), color, background, Modifier.testTag("$tagPrefix-${row.key.transactionId}"),
                        onClick = if (enabled) ({ onTransaction(row.key.transactionId) }) else null)
}
