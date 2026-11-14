package com.baraa.masroof.domain

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.assembly.BankTransactionTimePolicy
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.domain.rules.FinancialImpactCalculator
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** Fixed production examples from main after #317; no second implementation. */
class FinancialPolicyCompatibilityTest {
    @Test
    fun financialEffectsRetainTheirPersistedTypeMeaning() {
        val actual = FinancialTransactionType.entries.joinToString("\n") { type ->
            val impact = FinancialImpactCalculator.forType(type)
            "$type|${impact.countsAsExpense}|${impact.countsAsIncome}|${impact.netWorthEffect}"
        }
        val expected = javaClass.getResource("/finance-compatibility/financial-effects.txt")!!.readText()
            .lineSequence().joinToString("\n").trim()
        assertEquals(expected, actual)
    }

    @Test
    fun moneyAndDurableIdentityRemainStable() {
        assertEquals(Money.of("1.00", Currency.SAR), Money.of("1.005", Currency.SAR))
        assertNotEquals(Money.of("1", Currency.SAR), Money.of("1", Currency.USD))
        assertEquals("account:BANK_ALJAZIRA:3001", FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, " 3001 "))
        assertNotEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            FinancialContainerIdFactory.accountId(Bank("D360"), "3001"),
        )
        assertEquals(
            "transaction:438712f1ee08858c6a1d02347794cda44faab6a764d00348d71a7dce336dbae0",
            TransactionIdFactory.fromRawSmsIds(listOf("raw-b", " raw-a ")),
        )
    }

    @Test
    fun salaryWeekendAndBankZonesRemainStable() {
        val friday = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-02-27"))
        assertEquals(LocalDate.parse("2026-02-26"), friday.startDate)
        assertEquals(LocalDate.parse("2026-03-26"), friday.endDateExclusive)
        assertEquals(ZoneId.of("Asia/Riyadh"), BankTransactionTimePolicy.RIYADH)
    }
}
