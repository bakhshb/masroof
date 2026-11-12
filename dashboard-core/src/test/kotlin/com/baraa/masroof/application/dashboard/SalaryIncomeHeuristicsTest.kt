package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.testsupport.parsedRecord
import com.baraa.masroof.testsupport.tx
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SalaryIncomeHeuristicsTest {
    @Test
    fun incomeType_isAlwaysSalary() {
        val tx = tx(type = FinancialTransactionType.INCOME, amount = "100.00", dest = "account:bank_aljazira:3001")
        assertTrue(
            SalaryIncomeHeuristics.isSalaryIncome(tx, emptyMap()),
        )
    }

    @Test
    fun externalTransferWithSalaryFact_isSalary() {
        val tx = tx(
            type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
            linked = listOf("evt-1"),
            amount = "100.00",
            dest = "account:bank_aljazira:3001",
        )
        val records = mapOf(
            "evt-1" to parsedRecord("evt-1", salaryIncomeWording = true, family = MessageFamily.TRANSFER_IN, direction = MoneyDirection.INCOMING),
        )
        assertTrue(SalaryIncomeHeuristics.isSalaryIncome(tx, records))
    }

    @Test
    fun externalTransferWithoutSalaryFact_isNotSalary() {
        val tx = tx(
            type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
            linked = listOf("evt-1"),
            amount = "100.00",
            dest = "account:bank_aljazira:3001",
        )
        val records = mapOf(
            "evt-1" to parsedRecord("evt-1", salaryIncomeWording = false, family = MessageFamily.TRANSFER_IN, direction = MoneyDirection.INCOMING),
        )
        assertFalse(SalaryIncomeHeuristics.isSalaryIncome(tx, records))
    }

    @Test
    fun payrollCounterpartyWithoutSalaryFact_isNotSalary() {
        val tx = tx(
            type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
            linked = listOf("evt-1"),
            amount = "100.00",
            dest = "account:bank_aljazira:3001",
        ).copy(counterparty = "Payroll Co", merchant = "راتب")
        val records = mapOf(
            "evt-1" to parsedRecord("evt-1", salaryIncomeWording = false, family = MessageFamily.TRANSFER_IN, direction = MoneyDirection.INCOMING),
        )
        assertFalse(SalaryIncomeHeuristics.isSalaryIncome(tx, records))
        // A row parsed before the fact existed keeps its name match.
        assertTrue(SalaryIncomeHeuristics.isSalaryIncome(tx, mapOf("evt-1" to parsedRecord("evt-1", salaryIncomeWording = null, family = MessageFamily.TRANSFER_IN, direction = MoneyDirection.INCOMING))))
    }
}
