package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/**
 * Detects salary-like inflows from transaction type and persisted parse facts.
 *
 * Salary is not a separate [FinancialTransactionType]; it may appear as [INCOME]
 * or as an inbound transfer whose SMS mentions salary at parse time.
 * Counterparty and merchant names are never read for salary wording.
 */
object SalaryIncomeHeuristics {
    fun isSalaryIncome(
        transaction: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
    ): Boolean {
        if (transaction.type == FinancialTransactionType.INCOME) return true
        if (transaction.type != FinancialTransactionType.EXTERNAL_TRANSFER_IN) return false

        return transaction.linkedParsedEventIds.any { eventId ->
            parsedRecordsById[eventId]?.details?.salaryIncomeWording == true
        }
    }
}
