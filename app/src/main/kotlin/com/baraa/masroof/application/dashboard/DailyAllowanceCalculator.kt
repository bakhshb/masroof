package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.period.FinancialPeriod
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * [left] is this period's money in minus money out minus every unpaid commitment (negative when overspent);
 * [perDay] spreads what is left over the [days] until the next salary, today included, never below zero.
 */
data class DailyAllowance(val perDay: Money, val left: SignedMoneyAmount, val days: Long, val unpaidCommitments: Money)

/** Built from the accounts' own period flows, so it works for every bank and needs no balance message. */
object DailyAllowanceCalculator {
    /** [net] is the owned accounts' money in minus money out this period ([AccountsSummary.totalRemaining]). */
    fun calculate(net: SignedMoneyAmount?, commitments: CommitmentsOverview, period: FinancialPeriod, today: LocalDate): DailyAllowance? {
        net ?: return null
        if (today < period.startDate || today >= period.endDateExclusive) return null
        // A late unpaid row is still owed, so it counts too; a payment the app did not match shows there to mark paid.
        val upcoming = commitments.upcomingUnpaid
        if (upcoming.any { it.amount.currency != net.currency }) return null
        val dues = upcoming.fold(Money.zero(net.currency)) { sum, row -> sum + row.amount }
        val days = ChronoUnit.DAYS.between(today, period.endDateExclusive)
        val left = net.amount.subtract(dues.amount)
        val perDay = left.max(BigDecimal.ZERO).divide(BigDecimal.valueOf(days), Money.SCALE, RoundingMode.DOWN)
        return DailyAllowance(Money.of(perDay, net.currency), SignedMoneyAmount(left.setScale(Money.SCALE), net.currency), days, dues)
    }
}
