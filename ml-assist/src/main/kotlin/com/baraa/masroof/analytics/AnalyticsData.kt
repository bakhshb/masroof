package com.baraa.masroof.analytics

import java.security.MessageDigest
import java.time.LocalDate
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Shared training/inference contract, isolated from SMS-family classification. */
object AnalyticsVersion {
    const val CLEANING = "financial-clean-v2"
    const val FEATURES = "purchase-days-v1"
    const val MODEL = "huber-l2-v2"
    const val EVALUATION = "rolling-horizons-v2"
}

data class FinancialFact(val id: String, val day: Long, val amount: Long?, val refund: Boolean,
    val included: Boolean, val merchant: String? = null, val recurringEligible: Boolean = false,
    val recurringScope: String? = null, val comparisonScope: String? = null)
data class PurchaseDay(val day: Long, val gross: Long, val refunds: Long, val count: Int,
    val phase: Int, val covered: Boolean) {
    val net: Long get() = Math.subtractExact(gross, refunds)
}
data class QualityReport(val used: Int, val excluded: Map<String, List<String>>, val unusualIds: List<String>,
    val coveredDays: Int, val coverageKnown: Boolean) {
    val excludedCount: Int get() = excluded.values.sumOf { it.size }
}
data class CleanData(val days: List<PurchaseDay>, val recurring: List<FinancialFact>, val quality: QualityReport,
    val fingerprint: String)

object FinancialCleaner {
    // Recurring series and their accepted/rejected decisions are keyed by this grouping, so it stays as
    // stored even where the dashboard's merchant key also joins spacing around punctuation.
    fun merchantKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun clean(facts: List<FinancialFact>, start: Long, today: Long, coveredFrom: Long?, gaps: Set<Long> = emptySet(),
        phase: (Long) -> Int): CleanData {
        require(start <= today)
        val excluded = linkedMapOf<String, MutableList<String>>()
        fun exclude(reason: String, id: String) { excluded.getOrPut(reason) { mutableListOf() }.add(id) }
        val accepted = mutableListOf<FinancialFact>()
        val recurring = mutableListOf<FinancialFact>()
        val seen = mutableMapOf<String, FinancialFact>()
        for (fact in facts.sortedWith(compareBy({ it.day }, { it.id }))) {
            val prior = seen.putIfAbsent(fact.id, fact)
            if (prior != null) {
                require(prior == fact) { "Conflicting financial identity" }
                exclude("DUPLICATE_ID", fact.id); continue
            }
            if (fact.day > today) { exclude("FUTURE_DATE", fact.id); continue }
            if (fact.day < start) continue
            if (!fact.included) {
                if (fact.amount != null && fact.amount >= 0 && fact.recurringEligible && !fact.refund && !fact.merchant.isNullOrBlank()) recurring += fact
                exclude("OUTSIDE_PURCHASE_SCOPE", fact.id); continue
            }
            if (fact.amount == null) { exclude("MISSING_VALID_AMOUNT_OR_FX", fact.id); continue }
            if (fact.amount < 0) { exclude("INVALID_AMOUNT", fact.id); continue }
            if (fact.recurringEligible && !fact.refund && !fact.merchant.isNullOrBlank()) recurring += fact
            accepted += fact
        }
        val grouped = accepted.groupBy { it.day }
        // An eligible purchase with unknown money must not teach the model a zero-spending day.
        val incompleteDays = facts.filter { it.included && (it.amount == null || it.amount < 0) }.map { it.day }.toSet()
        val days = (start..today).map { day ->
            val rows = grouped[day].orEmpty()
            PurchaseDay(day, rows.filterNot { it.refund }.fold(0L) { total, row -> Math.addExact(total, row.amount!!) },
                rows.filter { it.refund }.fold(0L) { total, row -> Math.addExact(total, row.amount!!) }, rows.size,
                phase(day), coveredFrom != null && day >= coveredFrom && day !in gaps && day !in incompleteDays)
        }
        val amounts = accepted.filterNot { it.refund }.map { it.amount!!.toDouble() }
        val median = median(amounts)
        val mad = median(amounts.map { abs(it - median) })
        val unusual = if (amounts.size < 5) emptyList() else accepted.filter {
            !it.refund && it.amount!!.toDouble() > median + maxOf(6 * mad, median * 3, 10000.0)
        }.map { it.id }
        val report = QualityReport(accepted.size, excluded, unusual, days.count { it.covered }, coveredFrom != null)
        val canonical = facts.distinct().sortedBy { it.id }.joinToString("\n") { "${it.id}|${it.day}|${it.amount}|${it.refund}|${it.included}|${it.merchant}|${it.recurringEligible}|${it.recurringScope}|${it.comparisonScope}" }
        return CleanData(days, recurring, report, hash("${AnalyticsVersion.CLEANING}|$start|$today|$coveredFrom|${gaps.sorted()}|$canonical"))
    }
}

fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .toHexString()
fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted(); val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}
fun weekday(day: Long): Int = LocalDate.ofEpochDay(day).dayOfWeek.value - 1
