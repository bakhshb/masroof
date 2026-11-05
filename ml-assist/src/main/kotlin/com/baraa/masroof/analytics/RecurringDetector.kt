package com.baraa.masroof.analytics

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/** Cadence from the rules, or the user's own decision for a series. */
enum class RecurringLabel { NONE, WEEKLY, MONTHLY, YEARLY }

data class RecurringPattern(val key: String, val name: String, val cadence: String, val nextDay: Long,
    val estimate: Long, val lower: Long, val upper: Long, val sourceIds: List<String>,
    val latestAmount: Long, val referenceAmount: Long?, val amountIncreased: Boolean,
    val seriesKey: String = "", val origin: String = "RULES")

data class RecurringSeries(val key: String, val rows: List<FinancialFact>) {
    val dates = rows.takeLast(6).map { LocalDate.ofEpochDay(it.day) }
    private val gaps = rows.takeLast(6).zipWithNext().map { (a, b) -> (b.day - a.day).toDouble() }
    val ruleLabel: RecurringLabel get() {
        val pairs = dates.zipWithNext()
        fun sameDay(a: LocalDate, b: LocalDate, tolerance: Int) = abs(a.dayOfMonth - b.dayOfMonth) <= tolerance ||
            (a.dayOfMonth == a.lengthOfMonth() && b.dayOfMonth == b.lengthOfMonth())
        val steps = pairs.map { (a, b) -> ChronoUnit.MONTHS.between(YearMonth.from(a), YearMonth.from(b)) }
        val amounts = rows.takeLast(6).map { it.amount!! }
        return when {
            dates.size >= 3 && gaps.all { abs(it - 7) <= 2 } -> RecurringLabel.WEEKLY
            // One missed month (a skipped or unreceived payment) does not break a monthly series.
            dates.size >= 3 && steps.all { it in 1L..2L } && steps.count { it == 2L } <= 1 &&
                pairs.all { (a, b) -> sameDay(a, b, 5) } -> RecurringLabel.MONTHLY
            // Two payments a year apart are enough (few users have three years of messages), so both must look
            // like a renewal: nearly the same day and amount, not two visits to a shop in the same season.
            steps.all { it == 12L } && pairs.all { (a, b) -> sameDay(a, b, 3) } &&
                amounts.max() - amounts.min() <= amounts.max() / 10 -> RecurringLabel.YEARLY
            else -> RecurringLabel.NONE
        }
    }
}

object RecurringDetector {
    fun series(facts: List<FinancialFact>): List<RecurringSeries> = facts
        .filter { it.recurringEligible && !it.refund && it.amount != null && it.amount > 0 && !it.merchant.isNullOrBlank() }
        .groupBy { listOfNotNull(it.recurringScope, FinancialCleaner.merchantKey(it.merchant!!)).joinToString("|") }
        .mapNotNull { (identity, rows) ->
            // A same-day pair is ambiguous evidence, not grounds to discard an entire merchant's history.
            val ordered = rows.distinctBy { it.id }.groupBy { it.day }.values
                .filter { it.size == 1 }.map { it.single() }.sortedBy { it.day }
            // Two payments can only ever qualify as yearly; weekly and monthly still need three.
            if (ordered.size < 2) null else RecurringSeries(hash("series:$identity"), ordered)
        }

    /** [overrides] are the user's explicit cadence decisions by series key; every other series uses its rule label. */
    fun detect(facts: List<FinancialFact>, today: Long,
        overrides: Map<String, RecurringLabel> = emptyMap()): List<RecurringPattern> = series(facts).mapNotNull { series ->
            val ordered = series.rows
            val recent = ordered.takeLast(6)
            val dates = series.dates
            val decided = overrides[series.key]
            val label = (decided ?: series.ruleLabel).takeIf { it != RecurringLabel.NONE } ?: return@mapNotNull null
            if (label != RecurringLabel.YEARLY && ordered.size < 3) return@mapNotNull null
            val cadence = label.name
            fun endAligned(date: LocalDate, next: LocalDate) =
                if (date.dayOfMonth == date.lengthOfMonth()) next.withDayOfMonth(next.lengthOfMonth()) else next
            val due = dates.last().let { last -> when (label) {
                RecurringLabel.WEEKLY -> last.plusWeeks(1)
                RecurringLabel.YEARLY -> endAligned(last, last.plusYears(1))
                else -> endAligned(last, last.plusMonths(1))
            } }
            // A missed upcoming payment expires the suggestion; the next real one revives the series.
            val grace = when (label) { RecurringLabel.WEEKLY -> 2; RecurringLabel.YEARLY -> 15; else -> 5 }
            if (today > due.toEpochDay() + grace) return@mapNotNull null
            val amounts = recent.map { it.amount!!.toDouble() }
            var smoothed = amounts.first(); amounts.drop(1).forEach { smoothed = .3 * it + .7 * smoothed }
            val med = median(amounts); val mad = median(amounts.map { abs(it - med) })
            val margin = maxOf(mad * 2, amounts.max() - amounts.min())
            val reference = ordered.dropLast(1).takeLast(6).map { it.amount!!.toDouble() }.takeIf { it.size >= 3 }?.let(::median)
            val last = ordered.last().amount!!
            RecurringPattern(hash("${series.key}|$cadence"), ordered.last().merchant!!, cadence, due.toEpochDay(),
                smoothed.roundToLong(), (smoothed - margin).roundToLong().coerceAtLeast(0), (smoothed + margin).roundToLong(),
                ordered.map { it.id }, last, reference?.roundToLong(), reference != null && last - reference >= maxOf(reference * .2, 1000.0),
                series.key, if (decided != null) "HUMAN_DECISION" else "RULES")
        }.sortedBy { it.nextDay }
}
