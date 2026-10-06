package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.domain.repository.FinancialTransactionRepository

data class CardsDashboardSection(
    val creditFacilities: CreditFacilitiesOverview,
    val transactionCardInvolvement: Map<String, Set<String>>,
    val transactionDebitSpendInvolvement: Map<String, Set<String>>,
)

/**
 * Credit facilities (statement window), debit-card spending, and card involvement.
 *
 * Owns the one extra read this section needs: transactions in the credit-card statement
 * window, with their evidence added through [evidenceSource].
 */
class CardsDashboardProjection(
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val evidenceSource: DashboardEvidenceSource,
    private val sarEquivalentResolver: TransactionSarEquivalentResolver,
) {
    suspend fun build(context: DashboardProjectionContext): CardsDashboardSection = with(context) {
        val statementStart = CreditCardOverviewBuilder.resolveStatementSpendingStart(
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            zoneId = zoneId,
            periodEndExclusive = periodEndExclusive,
        )
        val cardQueryStart = minOf(
            FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zoneId),
            statementStart,
        )
        val cardTransactions = financialTransactionRepository.listOccurredBetween(
            startInclusive = cardQueryStart,
            endExclusive = periodEndExclusive,
        )
        val cardEvidence = evidenceSource.extend(evidence, cardTransactions)
        val enrichedCardTransactions = TransactionDisplayEnricher.enrichMerchants(
            transactions = cardTransactions,
            parsedRecords = cardEvidence.parsedRecords,
        )
        val cardSarEquivalents = sarEquivalentResolver.resolve(
            transactions = enrichedCardTransactions,
            parsedRecords = cardEvidence.parsedRecords,
            rawSmsById = cardEvidence.rawSmsById,
            primaryCurrency = primaryCurrency,
        ).sarAmounts()
        val creditCardsFlat = CreditCardOverviewBuilder.build(
            salaryPeriod = period,
            cardTransactions = enrichedCardTransactions,
            parsedRecords = cardEvidence.parsedRecords,
            rawSmsById = cardEvidence.rawSmsById,
            zoneId = zoneId,
            primaryCurrency = primaryCurrency,
            sarEquivalents = cardSarEquivalents,
            displayLocale = displayLocale,
        )
        val debitSpend = DebitCardOverviewBuilder.buildSpendingByCardKey(
            salaryPeriod = period,
            debitCards = cardRegistry,
            transactions = transactions,
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            ownedAccountContainerIds = ownedAccountContainerIds,
            ownedAccountLast4s = ownedAccountLast4s,
            zoneId = zoneId,
            displayLocale = displayLocale,
        )
        CardsDashboardSection(
            creditFacilities = CreditFacilityOverviewBuilder.build(
                overview = creditCardsFlat,
                registryCards = cardRegistry,
                registryAccounts = ownedAccounts,
                debitSpendingByCardKey = debitSpend.spendingByCardKey,
                debitSalaryPeriodLabel = debitSpend.salaryPeriodLabel ?: creditCardsFlat.salaryPeriodLabel,
                parsedRecords = parsedRecords,
                rawSmsById = rawSmsById,
            ),
            transactionCardInvolvement = CardTransactionInvolvementResolver.buildIndex(
                transactions = transactions,
                parsedRecords = parsedRecords,
                rawSmsById = rawSmsById,
            ),
            transactionDebitSpendInvolvement = debitSpend.transactionDebitSpendInvolvement,
        )
    }
}
