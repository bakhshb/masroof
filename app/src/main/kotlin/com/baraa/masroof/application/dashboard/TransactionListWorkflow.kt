package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdParser
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import java.util.Locale

/** Immutable input facts supplied by the dashboard; filtering never reads or writes storage. */
data class TransactionListRow(
    val id: String,
    val title: String?,
    val amount: Money,
    val typeLabelResHint: FinancialTransactionType,
    val cardLast4: String?,
    val sourceContainerId: String?,
    val destinationContainerId: String?,
    val searchText: String,
)

data class TransactionListProjection(
    val selection: TransactionListFilterResult,
    val availableTypes: List<FinancialTransactionType>,
    val availableCards: List<String>,
    val availableAccounts: List<String>,
)

class TransactionListWorkflow {
    fun project(
        rows: List<TransactionListRow>,
        query: TransactionListQuery,
        ownedCardKeys: Set<String> = emptySet(),
        ownedAccountContainerIds: Set<String> = emptySet(),
        accountInvolvement: Map<String, Set<String>> = emptyMap(),
        primaryCurrency: Currency = Currency.SAR,
    ): TransactionListProjection = TransactionListProjection(
        TransactionListProjectionCalculator.apply(rows, query, primaryCurrency, accountInvolvement),
        TransactionListProjectionCalculator.availableTypes(rows),
        TransactionListProjectionCalculator.availableCardKeys(rows, ownedCardKeys),
        TransactionListProjectionCalculator.availableAccountContainerIds(rows, ownedAccountContainerIds, accountInvolvement),
    )
}

data class TransactionListQuery(
    val searchQuery: String = "",
    val types: Set<FinancialTransactionType> = emptySet(),
    /** Bank-qualified `bankId:last4` card keys, so two banks' cards with one last4 stay apart. */
    val cardKeys: Set<String> = emptySet(),
    val accountContainerIds: Set<String> = emptySet(),
    val transactionIds: Set<String> = emptySet(),
) {
    val isActive: Boolean
        get() = searchQuery.isNotBlank() ||
            types.isNotEmpty() ||
            cardKeys.isNotEmpty() ||
            accountContainerIds.isNotEmpty() ||
            transactionIds.isNotEmpty()
}

data class TransactionListFilterResult(
    val indices: List<Int>,
    val totalAmount: Money?,
)

internal object TransactionListProjectionCalculator {
    private val TYPE_DISPLAY_ORDER = listOf(
        FinancialTransactionType.EXPENSE,
        FinancialTransactionType.INCOME,
        FinancialTransactionType.EXTERNAL_TRANSFER_IN,
        FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
        FinancialTransactionType.CREDIT_CARD_PAYMENT,
        FinancialTransactionType.REFUND,
        FinancialTransactionType.CASH_WITHDRAWAL,
        FinancialTransactionType.BILL_PAYMENT,
        FinancialTransactionType.LOAN_REPAYMENT,
        FinancialTransactionType.FEE,
        FinancialTransactionType.SELF_TRANSFER,
        FinancialTransactionType.ADJUSTMENT,
        FinancialTransactionType.UNKNOWN,
    )

    fun apply(
        transactions: List<TransactionListRow>,
        filter: TransactionListQuery,
        primaryCurrency: Currency = Currency.SAR,
        accountInvolvementByTransactionId: Map<String, Set<String>> = emptyMap(),
    ): TransactionListFilterResult {
        val selected = transactions.withIndex().filter { (_, tx) ->
            matchesTransactionIds(tx, filter.transactionIds) &&
                matchesTypes(tx, filter.types) &&
                matchesCards(tx, filter.cardKeys) &&
                matchesAccounts(tx, filter.accountContainerIds, accountInvolvementByTransactionId) &&
                matchesSearch(tx, filter.searchQuery)
        }
        return TransactionListFilterResult(
            indices = selected.map { it.index },
            totalAmount = sumAmount(selected.map { it.value }, primaryCurrency),
        )
    }

    fun availableTypes(transactions: List<TransactionListRow>): List<FinancialTransactionType> {
        val present = transactions.map { it.typeLabelResHint }.toSet()
        return TYPE_DISPLAY_ORDER.filter { it in present }
    }

    fun availableCardKeys(
        transactions: List<TransactionListRow>,
        ownedCardKeys: Set<String> = emptySet(),
    ): List<String> {
        if (ownedCardKeys.isEmpty()) return emptyList()
        return transactions.flatMapTo(mutableSetOf()) { tx -> ownedCardKeys.filter(tx.cardKeyMatcher()) }.sorted()
    }

    fun availableAccountContainerIds(
        transactions: List<TransactionListRow>,
        ownedAccountContainerIds: Set<String> = emptySet(),
        involvementByTransactionId: Map<String, Set<String>> = emptyMap(),
    ): List<String> {
        if (ownedAccountContainerIds.isEmpty()) return emptyList()
        val fromContainers = transactions.flatMap { tx ->
            FinancialContainerIdParser
                .accountContainerIdsFromContainers(tx.sourceContainerId, tx.destinationContainerId)
                .toList()
        }
        val fromInvolvement = involvementByTransactionId.values.flatten()
        val present = (fromContainers + fromInvolvement).toSet()
        return present.intersect(ownedAccountContainerIds).sorted()
    }

    private fun matchesTransactionIds(
        tx: TransactionListRow,
        transactionIds: Set<String>,
    ): Boolean = transactionIds.isEmpty() || tx.id in transactionIds

    private fun matchesTypes(
        tx: TransactionListRow,
        types: Set<FinancialTransactionType>,
    ): Boolean = types.isEmpty() || tx.typeLabelResHint in types

    private fun matchesCards(tx: TransactionListRow, cardKeys: Set<String>): Boolean =
        cardKeys.isEmpty() || cardKeys.any(tx.cardKeyMatcher())

    private fun matchesAccounts(
        tx: TransactionListRow,
        accountContainerIds: Set<String>,
        involvementByTransactionId: Map<String, Set<String>> = emptyMap(),
    ): Boolean {
        if (accountContainerIds.isEmpty()) return true
        if (tx.sourceContainerId in accountContainerIds || tx.destinationContainerId in accountContainerIds) {
            return true
        }
        return involvementByTransactionId[tx.id].orEmpty().any { it in accountContainerIds }
    }

    private fun matchesSearch(tx: TransactionListRow, rawQuery: String): Boolean {
        val query = rawQuery.trim()
        if (query.isEmpty()) return true

        if (matchesAmount(tx, query)) return true

        val lowered = query.lowercase(Locale.ROOT)
        if (tx.searchText.contains(lowered)) return true
        return tx.title?.contains(query, ignoreCase = true) == true
    }

    private fun matchesAmount(tx: TransactionListRow, query: String): Boolean {
        val normalized = query.replace(",", "").trim()
        if (normalized.isEmpty() || normalized.none { it.isDigit() }) return false

        val amount = tx.amount.amount
        val plain = amount.stripTrailingZeros().toPlainString()
        val fixed = amount.setScale(Money.SCALE).toPlainString()

        if (plain.contains(normalized) || fixed.contains(normalized)) return true

        normalized.toBigDecimalOrNull()?.let { queryAmount ->
            if (amount.compareTo(queryAmount) == 0) return true
            if (plain.startsWith(normalized) || fixed.startsWith(normalized)) return true
        }
        return false
    }

    private fun sumAmount(
        transactions: List<TransactionListRow>,
        currency: Currency,
    ): Money? {
        // Amounts are unsigned, so a total is only meaningful for one currency and one type.
        if (transactions.isEmpty() || transactions.any { it.amount.currency != currency }) return null
        if (transactions.distinctBy { it.typeLabelResHint }.size != 1) return null
        return transactions.fold(Money.zero(currency)) { acc, tx -> acc + tx.amount }
    }
}

private fun TransactionListRow.cardOwnershipKeys(): List<String> =
    listOfNotNull(sourceContainerId, destinationContainerId).mapNotNull { containerId ->
        val last4 = FinancialContainerIdParser.cardLast4(containerId) ?: return@mapNotNull null
        val bankId = FinancialContainerIdParser.cardBankId(containerId) ?: return@mapNotNull null
        "${Bank(bankId).id}:$last4"
    }

/**
 * A card container decides the card. Without one, the row's last4 matches a key at the row's
 * account bank; a row with no bank at all still matches every bank's card with that last4.
 * Container ids are parsed once per row, not once per tested key.
 */
private fun TransactionListRow.cardKeyMatcher(): (String) -> Boolean {
    val cardKeys = cardOwnershipKeys()
    if (cardKeys.isNotEmpty()) return { it in cardKeys }
    val last4 = cardLast4 ?: return { false }
    val banks = listOfNotNull(sourceContainerId, destinationContainerId)
        .mapNotNull(FinancialContainerIdParser::accountBankId).map { Bank(it).id }.toSet()
    return { cardKey ->
        cardKey.substringAfterLast(':') == last4 && (banks.isEmpty() || cardKey.substringBefore(':') in banks)
    }
}
