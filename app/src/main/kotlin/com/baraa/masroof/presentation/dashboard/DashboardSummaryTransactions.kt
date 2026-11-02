package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.presentation.common.MasroofSectionHeader

import com.baraa.masroof.ui.theme.MasroofSpacing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.EntityTransactionScope
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType

/** Thin mapping of [EntityTransactionScope] decisions onto preview rows. */
object DashboardSummaryTransactionFilter {
    fun forAccount(
        transactions: List<TransactionPreviewUi>,
        bank: Bank,
        maskedNumber: String,
        involvementByTransactionId: Map<String, Set<String>> = emptyMap(),
    ): List<TransactionPreviewUi> {
        val account = EntityTransactionScope.account(bank, maskedNumber)
        return transactions.mapNotNull { tx ->
            if (!EntityTransactionScope.involvesAccount(account, tx.sourceContainerId, tx.destinationContainerId,
                    involvementByTransactionId[tx.id].orEmpty())) return@mapNotNull null
            val direction = AccountTransactionPresentation.directionForAccount(tx, account)
            if (direction == tx.direction) tx else tx.copy(direction = direction)
        }
    }

    fun forLoan(
        transactions: List<TransactionPreviewUi>,
        bank: Bank,
        loanType: LoanType,
        involvementByTransactionId: Map<String, Set<String>> = emptyMap(),
    ): List<TransactionPreviewUi> = transactions.filter { tx ->
        EntityTransactionScope.involvesLoan(bank, loanType, tx.type, tx.destinationContainerId, involvementByTransactionId[tx.id].orEmpty())
    }

    fun forDebitCard(
        transactions: List<TransactionPreviewUi>,
        bank: Bank,
        last4: String,
        debitSpendInvolvementByTransactionId: Map<String, Set<String>>,
    ): List<TransactionPreviewUi> = transactions.filter { tx ->
        EntityTransactionScope.involvesDebitCard(bank, last4, debitSpendInvolvementByTransactionId[tx.id].orEmpty())
    }

    fun forCard(
        transactions: List<TransactionPreviewUi>,
        bank: Bank,
        last4: String,
        cardInvolvementByTransactionId: Map<String, Set<String>> = emptyMap(),
    ): List<TransactionPreviewUi> = transactions.filter { tx ->
        EntityTransactionScope.involvesCard(bank, last4, tx.sourceContainerId, tx.destinationContainerId, tx.cardLast4,
            cardInvolvementByTransactionId[tx.id].orEmpty())
    }
}

@Composable
fun DashboardSummaryTransactionsSection(
    transactions: List<TransactionPreviewUi>,
    onOpenTransaction: (String) -> Unit,
    onViewAll: (() -> Unit)? = null,
    ownedCards: List<OwnedCardUi> = emptyList(),
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap),
    ) {
        MasroofSectionHeader(
            title = title ?: stringResource(R.string.dashboard_summary_transactions_title, transactions.size),
            onViewAll = if (onViewAll != null && transactions.isNotEmpty()) onViewAll else null,
            viewAllLabel = if (onViewAll != null && transactions.isNotEmpty()) {
                stringResource(R.string.dashboard_summary_view_all_transactions)
            } else {
                null
            },
        )
        if (transactions.isEmpty()) {
            Text(
                stringResource(R.string.dashboard_summary_transactions_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            com.baraa.masroof.presentation.common.MasroofCard {
            transactions.forEach { row ->
                TransactionPreviewRow(
                    row = row,
                    ownedCards = ownedCards,
                    onClick = { onOpenTransaction(row.id) },
                )
            }
            }
        }
    }
}
