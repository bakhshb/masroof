package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.beta.application.evidence.betaAssetChoiceKey
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.DashboardSnapshot
import com.baraa.masroof.lab.finance.dashboard.TransactionFacts
import com.baraa.masroof.lab.finance.posting.FinancialType
import java.util.Locale

/** Source list semantics with full registry identities, including same-suffix cards at different banks. */
data class BetaTransactionFilter(
    val search: String = "",
    val types: Set<String> = emptySet(),
    val accounts: Set<String> = emptySet(),
    val cards: Set<String> = emptySet(),
) {
    val active: Boolean get() = search.isNotBlank() || types.isNotEmpty() || accounts.isNotEmpty() || cards.isNotEmpty()
}

object BetaTransactionListWorkflow {
    private val typeOrder = listOf(FinancialType.EXPENSE, FinancialType.INCOME, FinancialType.EXTERNAL_TRANSFER_IN,
        FinancialType.EXTERNAL_TRANSFER_OUT, FinancialType.CREDIT_CARD_PAYMENT, FinancialType.REFUND,
        FinancialType.CASH_WITHDRAWAL, FinancialType.BILL_PAYMENT, FinancialType.LOAN_REPAYMENT,
        FinancialType.FEE, FinancialType.SELF_TRANSFER, FinancialType.ADJUSTMENT, FinancialType.UNKNOWN)
    fun types(rows: List<TransactionFacts>): List<FinancialType> = typeOrder.filter { type -> rows.any { it.effectiveType == type } }
    fun accounts(snapshot: DashboardSnapshot, rows: List<TransactionFacts>): List<AssetId> = snapshot.accounts.map { it.id }
        .filter { id -> rows.any { id in it.accounts || id == it.source || id == it.destination } }
        .sortedWith(compareBy<AssetId> { it.bank.value }.thenBy { it.localId })
    fun cards(snapshot: DashboardSnapshot, rows: List<TransactionFacts>): List<AssetId> =
        (snapshot.debitCards.map { it.cardId } + snapshot.creditFacilities.flatMap { f -> f.cards.map { it.cardId } } + snapshot.standaloneCreditCards.map { it.cardId })
            .filter { id -> rows.any { id in it.cards || id in it.debitSpendingCards } }.distinct()
            .sortedWith(compareBy<AssetId> { it.bank.value }.thenBy { it.localId })
    fun filter(rows: List<TransactionFacts>, filter: BetaTransactionFilter): List<TransactionFacts> = rows.filter { row ->
        (filter.types.isEmpty() || row.effectiveType.name in filter.types) &&
            (filter.accounts.isEmpty() || (row.accounts + listOfNotNull(row.source, row.destination)).any { betaAssetChoiceKey(it) in filter.accounts }) &&
            (filter.cards.isEmpty() || (row.cards + row.debitSpendingCards).any { betaAssetChoiceKey(it) in filter.cards }) &&
            matchesSearch(row, filter.search)
    }.sortedWith(compareByDescending<TransactionFacts> { it.time.instant })
    /** Financial presentation calculation is owned by the dashboard calculator. */
    fun shareTotal(rows: List<TransactionFacts>,currency: com.baraa.masroof.lab.finance.value.Currency) =
        com.baraa.masroof.lab.beta.application.dashboard.BetaTransactionShareCalculator.total(rows,currency)
    private fun matchesSearch(row: TransactionFacts, search: String): Boolean {
        val text = BetaDisplayDigits.display(search).trim()
        if (text.isEmpty()) return true
        val number = text.replace(",", "")
        if (number.any(Char::isDigit) && (row.amount.amount.stripTrailingZeros().toPlainString().contains(number) ||
                row.amount.amount.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString().contains(number))) return true
        return listOfNotNull(row.merchant, row.counterparty, row.primaryCardLast4).any { BetaDisplayDigits.display(it).lowercase(Locale.ROOT).contains(text.lowercase(Locale.ROOT)) }
    }
}
