package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.testsupport.tx
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DailySpendingTrendBuilderTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private val period = FinancialPeriod(
        startDate = LocalDate.parse("2026-07-27"),
        endDateExclusive = LocalDate.parse("2026-07-30"),
    )

    @Test
    fun zeroFillsEveryPeriodDay_andAveragesNetDailySpending() {
        val trend = build(
            transactions = listOf(
                tx("expense", FinancialTransactionType.EXPENSE, "100", occurredAt = Instant.parse("2026-07-27T07:00:00Z")),
                tx("bill", FinancialTransactionType.BILL_PAYMENT, "30", occurredAt = Instant.parse("2026-07-28T07:00:00Z")),
                tx("refund", FinancialTransactionType.REFUND, "20", occurredAt = Instant.parse("2026-07-28T08:00:00Z")),
            ),
            today = LocalDate.parse("2026-07-29"),
        )

        assertEquals(
            listOf(
                LocalDate.parse("2026-07-27"),
                LocalDate.parse("2026-07-28"),
                LocalDate.parse("2026-07-29"),
            ),
            trend.points.map { it.date },
        )
        assertEquals(
            listOf("100.00", "10.00", "0.00"),
            trend.points.map { it.spending.amount.toPlainString() },
        )
        assertEquals("36.67", trend.averageDailySpending.amount.toPlainString())
    }

    @Test
    fun excludesNonSpendingMovements_andUsesSarEquivalents() {
        val foreignExpense = tx(
            id = "foreign",
            type = FinancialTransactionType.EXPENSE,
            amount = "10",
            occurredAt = Instant.parse("2026-07-27T07:00:00Z"),
            currency = Currency.USD,
        )
        val trend = DailySpendingTrendBuilder.build(
            period = period,
            transactions = listOf(
                foreignExpense,
                tx("cash", FinancialTransactionType.CASH_WITHDRAWAL, "100", occurredAt = Instant.parse("2026-07-27T07:00:00Z")),
                tx("transfer", FinancialTransactionType.EXTERNAL_TRANSFER_OUT, "100", occurredAt = Instant.parse("2026-07-27T07:00:00Z")),
                tx("payment", FinancialTransactionType.CREDIT_CARD_PAYMENT, "100", occurredAt = Instant.parse("2026-07-27T07:00:00Z")),
                tx("loan", FinancialTransactionType.LOAN_REPAYMENT, "100", occurredAt = Instant.parse("2026-07-27T07:00:00Z")),
            ),
            parsedRecords = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = mapOf(foreignExpense.id to Money.of("37.50", Currency.SAR)),
            zoneId = zone,
            today = LocalDate.parse("2026-07-29"),
        )

        assertEquals("37.50", trend.points.first().spending.amount.toPlainString())
        assertEquals("12.50", trend.averageDailySpending.amount.toPlainString())
    }

    @Test
    fun currentPeriod_omitsFutureDaysFromChartAndAverage() {
        val trend = build(
            transactions = listOf(
                tx("expense", FinancialTransactionType.EXPENSE, "100", occurredAt = Instant.parse("2026-07-27T07:00:00Z")),
            ),
            today = LocalDate.parse("2026-07-28"),
        )

        assertEquals(
            listOf(
                LocalDate.parse("2026-07-27"),
                LocalDate.parse("2026-07-28"),
            ),
            trend.points.map { it.date },
        )
        assertEquals("50.00", trend.averageDailySpending.amount.toPlainString())
    }

    @Test
    fun allOutputs_includeCashTransfersAndRepayments_withoutCountingSettlementsTwice() {
        val at = "2026-07-27T07:00:00Z"
        val types = listOf(
            FinancialTransactionType.EXPENSE to "100",
            FinancialTransactionType.BILL_PAYMENT to "30",
            FinancialTransactionType.FEE to "10",
            FinancialTransactionType.LOAN_REPAYMENT to "200",
            FinancialTransactionType.EXTERNAL_TRANSFER_OUT to "300",
            FinancialTransactionType.CASH_WITHDRAWAL to "50",
            FinancialTransactionType.REFUND to "20",
            FinancialTransactionType.CREDIT_CARD_PAYMENT to "100",
            FinancialTransactionType.SELF_TRANSFER to "500",
            FinancialTransactionType.INCOME to "1000",
        )
        val trend = DailySpendingTrendBuilder.build(period,
            types.mapIndexed { index, (type, amount) -> tx("$index", type, amount, occurredAt = Instant.parse(at)) },
            emptyList(), Currency.SAR, emptyMap(), zone, LocalDate.parse("2026-07-29"),
            includeFinancialOutflows = true)
        assertEquals("670.00", trend.totalSpending.amount.toPlainString())
        assertEquals("223.33", trend.averageDailySpending.amount.toPlainString())
        assertEquals(listOf("670.00", "0.00", "0.00"), trend.points.map { it.spending.amount.toPlainString() })
    }

    @Test
    fun averageUsesElapsedDays_includingZeroDays_beyondFourteenDays() {
        val longPeriod = FinancialPeriod(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-01"))
        val trend = DailySpendingTrendBuilder.build(longPeriod,
            listOf(tx("expense", FinancialTransactionType.EXPENSE, "200", occurredAt = Instant.parse("2026-07-01T07:00:00Z"))),
            emptyList(), Currency.SAR, emptyMap(), zone, LocalDate.parse("2026-07-20"), true)
        assertEquals(20, trend.points.size)
        assertEquals("10.00", trend.averageDailySpending.amount.toPlainString())
    }

    @Test
    fun unresolvedForeignAmounts_areNotAddedAsLocalCurrency() {
        val trend = DailySpendingTrendBuilder.build(period,
            listOf(tx("usd", FinancialTransactionType.EXPENSE, "100", occurredAt = Instant.parse("2026-07-27T07:00:00Z"), currency = Currency.USD)),
            emptyList(), Currency.SAR, emptyMap(), zone, LocalDate.parse("2026-07-29"), true)
        assertEquals("0.00", trend.totalSpending.amount.toPlainString())
    }

    @Test
    fun futurePeriod_hasNoPointsNoAverageAndNoTransactionIds() {
        val trend = build(listOf(tx("future", FinancialTransactionType.EXPENSE, "100", occurredAt = Instant.parse("2026-07-27T07:00:00Z"))),
            today = LocalDate.parse("2026-07-26"))
        assertEquals(emptyList<DailySpendingPoint>(), trend.points)
        assertEquals("0.00", trend.averageDailySpending.amount.toPlainString())
        assertEquals(emptyMap<LocalDate, Set<String>>(), trend.transactionIdsByDate)
    }

    @Test
    fun futureTransactionsInCurrentPeriod_doNotLeakIntoDaySelection() {
        val trend = build(listOf(tx("future", FinancialTransactionType.EXPENSE, "100", occurredAt = Instant.parse("2026-07-29T07:00:00Z"))),
            today = LocalDate.parse("2026-07-28"))
        assertEquals(2, trend.points.size)
        assertEquals("0.00", trend.totalSpending.amount.toPlainString())
        assertEquals(emptyMap<LocalDate, Set<String>>(), trend.transactionIdsByDate)
    }

    private fun build(
        transactions: List<FinancialTransaction>,
        today: LocalDate = LocalDate.parse("2026-07-29"),
    ): DailySpendingTrend =
        DailySpendingTrendBuilder.build(
            period = period,
            transactions = transactions,
            parsedRecords = emptyList(),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            zoneId = zone,
            today = today,
        )
}
