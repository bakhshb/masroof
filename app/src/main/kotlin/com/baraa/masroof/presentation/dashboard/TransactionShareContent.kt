package com.baraa.masroof.presentation.dashboard

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.presentation.common.formatCardLast4
import com.baraa.masroof.presentation.common.toUiLabelRes
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.presentation.locale.formatLocalizedTransactionDate
import java.time.format.DateTimeFormatter

@Composable
fun transactionDetailShareText(transaction: TransactionPreviewUi): String {
    val merchant = transaction.title
    return TransactionShareText.document(
        stringResource(R.string.transaction_share_detail_heading, stringResource(R.string.app_name)),
        merchant?.let {
            TransactionShareText.field(stringResource(R.string.transaction_detail_merchant), it)
        },
        TransactionShareText.field(
            stringResource(R.string.transaction_share_amount),
            formatLocalizedMoney(transaction.amount),
        ),
        TransactionShareText.field(
            stringResource(R.string.transaction_detail_type),
            transactionTypeLabel(transaction.typeLabelResHint),
        ),
        TransactionShareText.field(
            stringResource(R.string.transaction_detail_date),
            formatLocalizedTransactionDate(transaction.localDate),
        ),
        TransactionShareText.field(
            stringResource(R.string.transaction_detail_direction),
            stringResource(TransactionDirectionPresentation.labelRes(transaction.direction)),
        ),
        transaction.cardLast4?.let { last4 ->
            TransactionShareText.field(
                stringResource(R.string.transaction_detail_card),
                stringResource(R.string.dashboard_credit_card_last4, formatCardLast4(last4)),
            )
        },
        transaction.sarEquivalent?.let { sar ->
            TransactionShareText.field(
                stringResource(R.string.transaction_detail_sar_equivalent),
                formatLocalizedMoney(sar),
            )
        },
        transaction.appliedExchangeRate?.let { rate ->
            TransactionShareText.field(
                stringResource(
                    R.string.transaction_detail_exchange_rate,
                    transaction.amount.currency.name,
                ),
                rate.stripTrailingZeros().toPlainString(),
            )
        },
        transaction.exchangeRateSource?.let { source ->
            TransactionShareText.field(
                stringResource(R.string.transaction_detail_exchange_rate_source),
                stringResource(ExchangeRateSourcePresentation.labelRes(source)),
            )
        },
    )
}

/** Built on demand when the user shares, not on every recomposition. */
fun transactionListShareText(
    context: Context,
    periodLabel: String,
    filter: TransactionListFilterState,
    transactions: List<TransactionPreviewUi>,
    totalAmount: Money?,
    ownedCards: List<OwnedCardUi> = emptyList(),
    ownedAccounts: List<OwnedAccountUi> = emptyList(),
): String {
    val locale = context.resources.configuration.locales[0]
    val dateFormatter = DateTimeFormatter.ofPattern("d MMM", locale)
    fun money(amount: Money) = MoneyUiFormatter.format(amount, locale.toLanguageTag())
    fun typeLabel(type: FinancialTransactionType) = context.getString(type.toUiLabelRes())
    val typeLabels = filter.types.map(::typeLabel).sorted()
    val cardLabels = filter.cardKeys.map { key -> cardKeyDisplayLabel(context, ownedCards, key) }.sorted()
    val accountLabels = filter.accountContainerIds.mapNotNull { containerId ->
        accountDisplayLabel(context, ownedAccounts, containerId)
    }.sorted()
    val rows = transactions.map { tx ->
        TransactionShareText.listRow(
            date = dateFormatter.format(tx.localDate),
            title = tx.title ?: typeLabel(tx.typeLabelResHint),
            amount = money(tx.amount),
            type = typeLabel(tx.typeLabelResHint),
        )
    }
    return TransactionShareText.listDocument(
        title = context.getString(
            R.string.transaction_share_list_heading,
            context.getString(R.string.app_name),
            periodLabel,
        ),
        metaLines = listOfNotNull(
            filter.searchQuery.trim().takeIf { it.isNotEmpty() }?.let { query ->
                TransactionShareText.field(context.getString(R.string.transaction_share_search), query)
            },
            typeLabels.takeIf { it.isNotEmpty() }?.let { labels ->
                TransactionShareText.field(
                    context.getString(R.string.transaction_share_types),
                    labels.joinToString(" • "),
                )
            },
            cardLabels.takeIf { it.isNotEmpty() }?.let { labels ->
                TransactionShareText.field(
                    context.getString(R.string.transaction_share_cards),
                    labels.joinToString(" • "),
                )
            },
            accountLabels.takeIf { it.isNotEmpty() }?.let { labels ->
                TransactionShareText.field(
                    context.getString(R.string.transaction_share_accounts),
                    labels.joinToString(" • "),
                )
            },
            TransactionShareText.field(
                context.getString(R.string.transaction_share_count),
                transactions.size.toString(),
            ),
            totalAmount?.let { amount ->
                TransactionShareText.field(
                    context.getString(R.string.transaction_share_total),
                    money(amount),
                )
            },
        ),
        rows = rows,
    )
}
