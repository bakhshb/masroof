package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class CreditCardsOverviewDueTest {
    @Test
    fun resolveLatestStatementDue_usesLatestStatementAmongCards() {
        val older = Instant.parse("2026-08-10T09:00:00Z")
        val newer = Instant.parse("2026-08-11T09:00:00Z")

        val due = resolveLatestStatementDue(
            listOf(
                cardRow(
                    last4 = "7271",
                    dueAmount = "0.00",
                    statementIssuedAt = older,
                    dueDate = LocalDate.parse("2026-09-07"),
                ),
                cardRow(
                    last4 = "3478",
                    dueAmount = "8755.50",
                    statementIssuedAt = newer,
                    dueDate = LocalDate.parse("2026-09-07"),
                ),
            ),
        )

        assertEquals(Money.of("8755.50", Currency.SAR), due?.amount)
        assertEquals(newer, due?.updatedAt)
        assertEquals(LocalDate.parse("2026-09-07"), due?.dueDate)
    }

    @Test
    fun resolveLatestStatementDue_ignoresPurchaseSnapshotWithoutStatementIssuedAt() {
        val due = resolveLatestStatementDue(
            listOf(
                cardRow(
                    last4 = "7271",
                    dueAmount = "8755.50",
                    statementIssuedAt = null,
                    updatedAt = Instant.parse("2026-08-20T19:10:00Z"),
                ),
            ),
        )

        assertNull(due)
    }

    @Test
    fun detailDue_keepsLatestFacilityAmountAndUnknownDateTogether() {
        val older = Instant.parse("2026-08-10T09:00:00Z")
        val newer = older.plusSeconds(86400)
        val primary = cardRow("7271", "100", older, LocalDate.parse("2026-09-07"))
        val supplementary = cardRow("3478", "200", newer)
        val facility = CreditFacilityOverview(primary.bank, primary, listOf(supplementary),
            resolveLatestStatementDue(listOf(primary, supplementary)), SignedMoneyAmount.zero(Currency.SAR),
            SignedMoneyAmount.zero(Currency.SAR), null, null, Currency.SAR)
        val due = primary.statementDueFor(facility)
        assertEquals(Money.of("200", Currency.SAR), due?.amount)
        assertEquals(newer, due?.updatedAt)
        assertNull(due?.dueDate)
    }

    @Test
    fun detailDue_doesNotBorrowAnotherBanksOrAnotherCardsStatement() {
        val at = Instant.parse("2026-08-10T09:00:00Z")
        val row = cardRow("7271", "100", at)
        val other = row.copy(bank = Bank("OTHER"))
        val facility = CreditFacilityOverview(other.bank, other, emptyList(),
            StatementDueSnapshot(Money.of("900", Currency.SAR), at, LocalDate.parse("2026-09-07")),
            SignedMoneyAmount.zero(Currency.SAR), SignedMoneyAmount.zero(Currency.SAR), null, null, Currency.SAR)
        assertEquals(Money.of("100", Currency.SAR), row.statementDueFor(facility)?.amount)
        assertEquals(Money.of("100", Currency.SAR), row.statementDueFor(facility.copy(bank = row.bank, primary = row.copy(last4 = "9999")))?.amount)
        assertNull(row.copy(snapshot = row.snapshot!!.copy(statementIssuedAt = null)).statementDueFor())
    }

    private fun cardRow(
        last4: String,
        dueAmount: String,
        statementIssuedAt: Instant?,
        dueDate: LocalDate? = null,
        updatedAt: Instant = statementIssuedAt ?: Instant.parse("2026-08-10T09:00:00Z"),
    ) = CreditCardDashboardRow(
        bank = Bank.BANK_ALJAZIRA,
        last4 = last4,
        statementSpendingNet = SignedMoneyAmount.zero(Currency.SAR),
        salaryPeriodSpendingNet = SignedMoneyAmount.zero(Currency.SAR),
        statementPeriodLabel = null,
        snapshot = CreditCardBalanceSnapshot(
            availableBalance = null,
            dueAmount = Money.of(dueAmount, Currency.SAR),
            dueDate = dueDate,
            statementIssuedAt = statementIssuedAt,
            updatedAt = updatedAt,
        ),
    )
}
