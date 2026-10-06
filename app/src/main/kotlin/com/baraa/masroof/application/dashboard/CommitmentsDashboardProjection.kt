package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.repository.CommitmentRepository
import com.baraa.masroof.domain.repository.FinancialTransactionRepository

data class CommitmentsDashboardSection(
    val loansOverview: LoansOverview,
    val transactionLoanInvolvement: Map<String, Set<String>>,
    val commitmentsOverview: CommitmentsOverview,
)

/**
 * Loans and recurring commitments (subscriptions, loan installments, statement dues).
 *
 * Owns this section's extra reads: commitments, their source transactions outside the
 * period, and credit-card payments that settle a statement due in the period — each with
 * evidence added through [evidenceSource].
 */
class CommitmentsDashboardProjection(
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val commitmentRepository: CommitmentRepository,
    private val evidenceSource: DashboardEvidenceSource,
    private val sarEquivalentResolver: TransactionSarEquivalentResolver,
) {
    suspend fun build(
        context: DashboardProjectionContext,
        creditFacilities: CreditFacilitiesOverview,
    ): CommitmentsDashboardSection = with(context) {
        val commitments = commitmentRepository.listAll()
        val commitmentSourceTransactions = commitments.mapNotNull { commitment ->
            if (commitment.sourceTransactionId in transactionIds) {
                null
            } else {
                financialTransactionRepository.getById(commitment.sourceTransactionId)
            }
        }
        val commitmentSarEquivalents = resolveSar(context, commitmentSourceTransactions)
        val loansOverview = LoanOverviewBuilder.build(
            salaryPeriod = period,
            loans = loans,
            transactions = transactions,
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            zoneId = zoneId,
            displayLocale = displayLocale,
        )
        val creditCardPaymentTransactions = creditFacilityPaymentTransactionsOutsidePeriod(context, creditFacilities)
        val creditCardPaymentSarEquivalents = resolveSar(context, creditCardPaymentTransactions)
        val commitmentTransactions = buildList {
            addAll(transactions)
            addAll(commitmentSourceTransactions)
            addAll(creditCardPaymentTransactions)
        }.distinctBy { it.id }
        CommitmentsDashboardSection(
            loansOverview = loansOverview,
            transactionLoanInvolvement = LoanRepaymentAttribution.buildInvolvementIndex(
                transactions = transactions,
                parsedRecords = parsedRecords,
            ),
            commitmentsOverview = CommitmentsOverviewBuilder.build(
                salaryPeriod = period,
                commitments = commitments,
                creditFacilities = creditFacilities,
                loansOverview = loansOverview,
                transactions = commitmentTransactions,
                primaryCurrency = primaryCurrency,
                sarEquivalents = sarEquivalents + commitmentSarEquivalents + creditCardPaymentSarEquivalents,
                zoneId = zoneId,
            ),
        )
    }

    private suspend fun resolveSar(
        context: DashboardProjectionContext,
        transactions: List<FinancialTransaction>,
    ) = evidenceSource.extend(context.evidence, transactions).let { evidence ->
        sarEquivalentResolver.resolve(
            transactions = transactions,
            parsedRecords = evidence.parsedRecords,
            rawSmsById = evidence.rawSmsById,
            primaryCurrency = context.primaryCurrency,
        ).sarAmounts()
    }

    private suspend fun creditFacilityPaymentTransactionsOutsidePeriod(
        context: DashboardProjectionContext,
        creditFacilities: CreditFacilitiesOverview,
    ): List<FinancialTransaction> {
        val relevantFacilities = creditFacilities.facilities.filter { facility ->
            val due = facility.facilityDue ?: return@filter false
            CommitmentsOverviewBuilder.isStatementDueInPeriod(due, context.period, context.zoneId)
        }
        if (relevantFacilities.isEmpty()) return emptyList()

        val earliestDueUpdate = relevantFacilities.mapNotNull { it.facilityDue?.updatedAt }.min()
        val creditCardPayments = financialTransactionRepository.listByTypesOccurredSince(
            types = listOf(FinancialTransactionType.CREDIT_CARD_PAYMENT),
            startInclusive = earliestDueUpdate,
        )
        return relevantFacilities.flatMap { facility ->
            val due = facility.facilityDue ?: return@flatMap emptyList()
            val cardIds = facility.allCards.mapNotNull { card ->
                FinancialContainerIdFactory.cardId(card.bank, card.last4)
            }.toSet()
            creditCardPayments.filter { transaction ->
                transaction.id !in context.transactionIds &&
                    transaction.destinationContainerId in cardIds &&
                    !transaction.occurredAt.isBefore(due.updatedAt)
            }
        }.distinctBy { it.id }
    }
}
