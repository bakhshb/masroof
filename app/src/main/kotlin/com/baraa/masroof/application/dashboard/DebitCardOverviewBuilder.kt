package com.baraa.masroof.application.dashboard

import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DebitCardSpendBuildResult(
    val spendingByCardKey: Map<String, SignedMoneyAmount>,
    val salaryPeriodLabel: String?,
    /** Transaction id → card keys that count this tx toward Mada salary-period spending. */
    val transactionDebitSpendInvolvement: Map<String, Set<String>>,
)

/**
 * Attributes POS purchases, cash withdrawals, and refunds to owned debit (Mada) cards via parsed card refs.
 */
object DebitCardOverviewBuilder {
    fun buildSpendingByCardKey(
        salaryPeriod: FinancialPeriod,
        debitCards: List<CardRegistryEntry>,
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
        ownedAccountContainerIds: Set<String>,
        ownedAccountLast4s: Set<String>,
        zoneId: ZoneId,
        displayLocale: Locale = Locale.forLanguageTag(AppLocale.TAG_AR),
    ): DebitCardSpendBuildResult {
        val ownedDebit = debitCards.filter {
            it.ownership == OwnershipStatus.OWNED &&
                CardRegistryDebitClassifier.isDebitRegistryEntry(
                    it,
                    parsedRecords = parsedRecords,
                )
        }
        if (ownedDebit.isEmpty()) {
            return DebitCardSpendBuildResult(
                spendingByCardKey = emptyMap(),
                salaryPeriodLabel = null,
                transactionDebitSpendInvolvement = emptyMap(),
            )
        }

        val salaryPeriodStart = FinancialPeriodPolicy.toInclusiveStartInstant(salaryPeriod.startDate, zoneId)
        val salaryPeriodLabel = DateTimeFormatter.ofPattern("d MMMM", displayLocale)
            .format(salaryPeriod.startDate)
        val cardInvolvement = CardTransactionInvolvementResolver.buildIndex(
            transactions = transactions,
            parsedRecords = parsedRecords,
        )
        val context = AccountFlowClassifier.buildContext(
            transactions = transactions,
            parsedRecords = parsedRecords,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
        )
        val debitSpendInvolvement = mutableMapOf<String, MutableSet<String>>()

        val spending = ownedDebit.associate { entry ->
            val cardKey = CardTransactionInvolvementResolver.cardKey(entry.bank.id, entry.last4)
            val scope = DebitCardSpendClassifier.scopeFor(
                entry = entry,
                ownedAccountContainerIds = ownedAccountContainerIds,
                ownedAccountLast4s = ownedAccountLast4s,
            )

            var gross = Money.zero(primaryCurrency)
            var refund = Money.zero(primaryCurrency)
            for (tx in transactions) {
                if (tx.occurredAt.isBefore(salaryPeriodStart)) continue
                val amount = TransactionAmountResolver.effectiveAmount(tx, primaryCurrency, sarEquivalents)
                    ?: continue
                when (
                    DebitCardSpendClassifier.effectFor(
                        tx = tx,
                        bankId = entry.bank.id,
                        last4 = entry.last4,
                        scope = scope,
                        context = context,
                        cardInvolvement = cardInvolvement,
                    )
                ) {
                    null -> Unit
                    DebitCardSpendClassifier.Effect.Expense -> {
                        gross += amount
                        debitSpendInvolvement.getOrPut(tx.id) { mutableSetOf() }.add(cardKey)
                    }
                    DebitCardSpendClassifier.Effect.Refund -> {
                        refund += amount
                        debitSpendInvolvement.getOrPut(tx.id) { mutableSetOf() }.add(cardKey)
                    }
                }
            }
            cardKey to SignedMoneyAmount.difference(gross, refund)
        }
        return DebitCardSpendBuildResult(
            spendingByCardKey = spending,
            salaryPeriodLabel = salaryPeriodLabel,
            transactionDebitSpendInvolvement = debitSpendInvolvement.mapValues { it.value.toSet() },
        )
    }
}
