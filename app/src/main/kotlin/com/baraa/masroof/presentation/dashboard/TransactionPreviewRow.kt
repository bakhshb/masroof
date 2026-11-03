package com.baraa.masroof.presentation.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.presentation.locale.formatLocalizedTransactionDate
import com.baraa.masroof.ui.theme.*
@Composable
fun TransactionPreviewRow(
    row: TransactionPreviewUi,
    modifier: Modifier = Modifier,
    ownedCards: List<OwnedCardUi> = emptyList(),
    onClick: (() -> Unit)? = null,
) {
    val colors = MasroofThemeExtras.extendedColors
    val inward = row.direction.isInward
    val transfer = row.direction == TransactionDirectionUi.NEUTRAL
    val (color, background) = when (row.type) {
        com.baraa.masroof.domain.model.FinancialTransactionType.LOAN_REPAYMENT -> colors.liability to colors.liabilitySoft
        com.baraa.masroof.domain.model.FinancialTransactionType.CREDIT_CARD_PAYMENT -> colors.card to colors.cardSoft
        else -> if (inward) colors.inflow to colors.inflowSoft else if (transfer) colors.account to colors.accountSoft else colors.outflow to colors.outflowSoft
    }
    val title = transactionTitle(row)
    val money = formatLocalizedMoney(row.amount)
    val formatted = money.substringBeforeLast(' ')
    val currency = money.substringAfterLast(' ')
    com.baraa.masroof.ui.dashboard.DashboardTransactionRow(
        com.baraa.masroof.application.locale.AppDigits.display(title),
        com.baraa.masroof.application.locale.AppDigits.display(previewSubtitle(title, transactionTypeLabel(row.typeLabelResHint),
            cardDisplayLabelFromTransaction(row, ownedCards))),
        row.direction.amountSign + formatted, formatLocalizedTransactionDate(row.localDate) + " · " + currency,
        MasroofIcons.transactionType(row.typeLabelResHint), color, background, modifier, onClick)
}

/** The type is left out when the title already says it, unless nothing else would remain. */
internal fun previewSubtitle(title: String, typeLabel: String, cardLabel: String?): String =
    if (cardLabel == null) typeLabel else if (typeLabel == title) cardLabel else "$typeLabel · $cardLabel"
