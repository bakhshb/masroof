package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

data class DailySpendingPoint(
    val date: LocalDate,
    val spending: SignedMoneyAmount,
    val beforeRefunds: SignedMoneyAmount = SignedMoneyAmount(spending.amount.max(BigDecimal.ZERO), spending.currency),
    val refunds: SignedMoneyAmount = SignedMoneyAmount(spending.amount.negate().max(BigDecimal.ZERO), spending.currency),
)

data class DailySpendingTrend(
    val points: List<DailySpendingPoint>,
    val averageDailySpending: SignedMoneyAmount,
    val transactionIdsByDate: Map<LocalDate, Set<String>> = emptyMap(),
    val categoryTrends: Map<ChartDataCategory, DailySpendingTrend> = emptyMap(),
) {
    val totalBeforeRefunds: SignedMoneyAmount get() = if (points.isEmpty()) SignedMoneyAmount.zero(averageDailySpending.currency)
        else SpendingAmounts.sum(points.map { it.beforeRefunds })
    val totalRefunds: SignedMoneyAmount get() = if (points.isEmpty()) SignedMoneyAmount.zero(averageDailySpending.currency)
        else SpendingAmounts.sum(points.map { it.refunds })
    val hasRefunds: Boolean get() = totalRefunds.amount.signum() != 0
    val totalSpending: SignedMoneyAmount
        get() = if (points.isEmpty()) SignedMoneyAmount.zero(averageDailySpending.currency)
            else SpendingAmounts.sum(points.map { it.spending })
    init {
        require(points.isNotEmpty() || averageDailySpending.amount.signum() == 0)
        require(points.all { it.spending.currency == averageDailySpending.currency })
    }

    companion object {
        fun empty(
            period: FinancialPeriod,
            currency: Currency = Currency.SAR,
        ): DailySpendingTrend =
            DailySpendingTrendBuilder.build(
                period = period,
                transactions = emptyList(),
                parsedRecords = emptyList(),
                primaryCurrency = currency,
                sarEquivalents = emptyMap(),
                zoneId = ZoneId.systemDefault(),
                today = LocalDate.now(ZoneId.systemDefault()),
            )
    }
}

/**
 * Builds the ordinary-spending trend used in the dashboard analysis section.
 *
 * Spending matches [MonthlyFinancialSummaryCalculator]: expenses, bills, and
 * non-loan fees, less refunds. Transfers, withdrawals, repayments, and card
 * settlements are movements rather than ordinary spending.
 */
object DailySpendingTrendBuilder {
    fun build(
        period: FinancialPeriod,
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
        zoneId: ZoneId,
        today: LocalDate,
        includeFinancialOutflows: Boolean = false,
    ): DailySpendingTrend {
        val parsedById = parsedRecords.associateBy { it.event.id }
        val amounts = transactions.distinctBy { it.id }.associate { transaction ->
            transaction.id to signedSpendingAmount(transaction, primaryCurrency, sarEquivalents, parsedById, includeFinancialOutflows)
        }.mapNotNull { (id, amount) -> amount?.let { id to it } }.toMap()
        return buildWithAmounts(period, transactions, primaryCurrency, zoneId, today, amounts)
    }

    /** Shared date/FX-independent projection after a specialist has classified each contribution. */
    internal fun buildWithAmounts(
        period: FinancialPeriod,
        transactions: List<FinancialTransaction>,
        primaryCurrency: Currency,
        zoneId: ZoneId,
        today: LocalDate,
        signedAmounts: Map<String, BigDecimal>,
    ): DailySpendingTrend {
        if (period.startDate.isAfter(today)) return DailySpendingTrend(
            emptyList(), SignedMoneyAmount.zero(primaryCurrency),
        )
        val totalsByDay = mutableMapOf<LocalDate, BigDecimal>()
        val grossByDay = mutableMapOf<LocalDate, BigDecimal>()
        val refundsByDay = mutableMapOf<LocalDate, BigDecimal>()
        val idsByDay = mutableMapOf<LocalDate, MutableSet<String>>()

        transactions.distinctBy { it.id }.forEach { transaction ->
            val signedAmount = signedAmounts[transaction.id] ?: return@forEach
            val date = transaction.occurredAt.atZone(zoneId).toLocalDate()
            if (date.isBefore(period.startDate) || !date.isBefore(period.endDateExclusive) || date.isAfter(today)) return@forEach
            idsByDay.getOrPut(date) { mutableSetOf() }.add(transaction.id)
            if (signedAmount.signum() >= 0) grossByDay[date] = (grossByDay[date] ?: BigDecimal.ZERO).add(signedAmount)
            else refundsByDay[date] = (refundsByDay[date] ?: BigDecimal.ZERO).subtract(signedAmount)
            totalsByDay[date] = (totalsByDay[date] ?: BigDecimal.ZERO)
                .add(signedAmount)
                .setScale(Money.SCALE, RoundingMode.HALF_EVEN)
        }

        val lastInclusiveDate = minOf(period.displayEndDateInclusive, today)
        val points = generateSequence(period.startDate) { date ->
            date.plusDays(1).takeIf { !it.isAfter(lastInclusiveDate) }
        }.map { date ->
            DailySpendingPoint(
                date = date,
                spending = SignedMoneyAmount(
                    amount = (totalsByDay[date] ?: BigDecimal.ZERO)
                        .setScale(Money.SCALE, RoundingMode.HALF_EVEN),
                    currency = primaryCurrency,
                ),
                beforeRefunds = SignedMoneyAmount((grossByDay[date] ?: BigDecimal.ZERO).setScale(Money.SCALE), primaryCurrency),
                refunds = SignedMoneyAmount((refundsByDay[date] ?: BigDecimal.ZERO).setScale(Money.SCALE), primaryCurrency),
            )
        }.toList()
        val total = points.fold(BigDecimal.ZERO) { sum, point -> sum.add(point.spending.amount) }
            .setScale(Money.SCALE, RoundingMode.HALF_EVEN)

        return DailySpendingTrend(
            points = points,
            transactionIdsByDate = idsByDay.mapValues { it.value.toSet() },
            averageDailySpending = SignedMoneyAmount(
                amount = total.divide(
                    points.size.toBigDecimal(),
                    Money.SCALE,
                    RoundingMode.HALF_EVEN,
                ),
                currency = primaryCurrency,
            ),
        )
    }

    internal fun signedSpendingAmount(
        transaction: FinancialTransaction,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
        parsedRecordsById: Map<String, ParsedEventRecord>,
        includeFinancialOutflows: Boolean,
    ): BigDecimal? {
        val amount = TransactionAmountResolver.effectiveAmount(transaction, primaryCurrency, sarEquivalents)
            ?: return null
        return when (transaction.type) {
            FinancialTransactionType.EXPENSE,
            FinancialTransactionType.BILL_PAYMENT,
            -> amount.amount

            FinancialTransactionType.FEE ->
                amount.amount.takeUnless { !includeFinancialOutflows && LoanRepaymentAttribution.isLoanRepayment(transaction, parsedRecordsById) }

            FinancialTransactionType.LOAN_REPAYMENT,
            FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            FinancialTransactionType.CASH_WITHDRAWAL,
            -> amount.amount.takeIf { includeFinancialOutflows }
            // Settlement is a cash movement: card purchases are already counted once.
            FinancialTransactionType.CREDIT_CARD_PAYMENT,
            FinancialTransactionType.SELF_TRANSFER,
            -> null
            FinancialTransactionType.REFUND -> amount.amount.negate()
            else -> null
        }
    }
}
