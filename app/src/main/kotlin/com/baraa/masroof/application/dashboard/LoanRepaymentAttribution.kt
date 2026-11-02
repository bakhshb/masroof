package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/**
 * Canonical SMS-driven loan repayment detection.
 *
 * A transaction repays a loan when stored as [FinancialTransactionType.LOAN_REPAYMENT],
 * or when still stored as [FinancialTransactionType.FEE] with linked
 * [MessageFamily.FINANCING_INSTALLMENT] SMS. Explicit reclassification to any other
 * stored type overrides SMS evidence.
 */
object LoanRepaymentAttribution {
    fun isLoanRepayment(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
    ): Boolean = loanContainerId(tx, parsedRecordsById) != null

    fun loanContainerId(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
    ): String? {
        when (tx.type) {
            FinancialTransactionType.LOAN_REPAYMENT -> {
                tx.destinationContainerId?.let { return it }
                return loanContainerIdFromFinancingSms(tx, parsedRecordsById)
            }

            FinancialTransactionType.FEE ->
                return loanContainerIdFromFinancingSms(tx, parsedRecordsById)

            else -> return null
        }
    }

    private fun loanContainerIdFromFinancingSms(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
    ): String? {
        financingInstallmentRecords(tx, parsedRecordsById).forEach { record ->
            val loanType = record.details.loanType ?: return@forEach
            return FinancialContainerIdFactory.loanId(record.event.bank, loanType)
        }
        return null
    }

    fun matchesLoan(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
        bank: Bank,
        loanType: LoanType,
    ): Boolean {
        val expected = FinancialContainerIdFactory.loanId(bank, loanType)
        return loanContainerId(tx, parsedRecordsById) == expected
    }

    fun buildInvolvementIndex(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
    ): Map<String, Set<String>> {
        if (transactions.isEmpty()) return emptyMap()
        val parsedRecordsById = parsedRecords.associateBy { it.event.id }
        return transactions.mapNotNull { tx ->
            loanContainerId(tx, parsedRecordsById)?.let { loanId -> tx.id to setOf(loanId) }
        }.toMap()
    }

    private fun financingInstallmentRecords(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
    ): List<ParsedEventRecord> =
        tx.linkedParsedEventIds.mapNotNull { parsedRecordsById[it] }
            .filter { it.event.messageFamily == MessageFamily.FINANCING_INSTALLMENT }
}
