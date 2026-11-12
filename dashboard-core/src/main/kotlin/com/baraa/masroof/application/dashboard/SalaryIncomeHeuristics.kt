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

        val records = transaction.linkedParsedEventIds.mapNotNull { parsedRecordsById[it] }
        val facts = records.mapNotNull { it.details.salaryIncomeWording }
        if (facts.isNotEmpty()) return true in facts
        // Rows parsed before the fact existed have none, and history is never re-parsed: keep their old name match.
        val names = listOfNotNull(transaction.counterparty, transaction.merchant) +
            records.flatMap { listOfNotNull(it.event.counterparty, it.event.merchant) }
        return names.any { name -> LEGACY_SALARY_WORDS.any { name.lowercase().contains(it) } }
    }

    private val LEGACY_SALARY_WORDS = listOf("راتب", "رواتب", "salary", "payroll", "wage")
}
