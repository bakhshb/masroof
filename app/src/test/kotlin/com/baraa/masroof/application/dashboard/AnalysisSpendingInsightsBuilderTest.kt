package com.baraa.masroof.application.dashboard

import org.junit.Assert.*
import org.junit.Test

class AnalysisSpendingInsightsBuilderTest {
    private fun fact(id: String, day: Long, amount: Long, kind: String = "PURCHASES", merchant: String? = "Shop") =
        AnalysisSpendingFact(id, day, amount, kind, merchant)
    private fun build(rows: List<AnalysisSpendingFact>, covered: Set<Long> = (0L..35L).toSet(), today: Long = 35) =
        AnalysisSpendingInsightsBuilder.build(rows, 30, today, 0, 30, covered)

    @Test fun componentsUseTheSameContributionsKeepRefundsAndDeduplicateOnlyIdentity() {
        val row = fact("a", 30, 1000)
        val result = build(listOf(row, row, row.copy(id = "b"), fact("refund", 32, -500, "REFUNDS"),
            fact("cash", 33, 2000, "CASH"), fact("transfer", 34, 5000, "TRANSFERS")))
        assertEquals(8500L, result.components.sumOf { it.amount })
        assertEquals(1500L, result.merchants.single().amount)
        assertEquals(setOf("a", "b", "refund"), result.merchants.single().ids.toSet())
        assertEquals(2, result.components.first { it.key == "PURCHASES" }.ids.size)
    }
    @Test fun comparesEqualCompleteDaysAndNeverComparesTodaysPartialSpendingToAFullDay() {
        val result = build(listOf(fact("old", 4, 1000), fact("outside", 5, 99999), fact("now", 34, 1500), fact("today", 35, 100000)))
        val c = result.comparison!!
        assertEquals(1500L, c.current); assertEquals(1000L, c.previous); assertEquals(500L, c.delta)
        assertEquals("50.0", c.percent)
        assertEquals(34L, c.through); assertEquals(4L, c.previousThrough)
        assertFalse(c.changes.flatMap { it.group.ids + it.previousIds }.contains("today"))
        assertFalse(c.changes.flatMap { it.group.ids + it.previousIds }.contains("outside"))
    }
    @Test fun gapsMissingComparisonZeroAndNegativePreviousTotalsAreExplicit() {
        assertNull(build(emptyList(), covered = (1L..35L).toSet()).comparison)
        assertNull(build(emptyList(), today = 30).comparison)
        assertNull(build(listOf(fact("a", 31, 1000))).comparison!!.percent)
        assertNull(build(listOf(fact("r", 1, -500, "REFUNDS"))).comparison!!.percent)
    }
    @Test fun allMerchantsRemainAvailableForTheAnalysisDrilldownIncludingSinglePurchases() {
        val result = build((1..8).map { fact("merchant-$it", 32, it * 100L, merchant = "Shop $it") })
        assertEquals(8, result.merchants.size)
        assertEquals("Shop 8", result.merchants.first().name)
        assertEquals(1, result.merchants.last().ids.size)
        assertEquals(3600L, result.components.first().amount)
    }
    @Test fun merchantNamesAreConservativeAndChangesReferenceBothSides() {
        val result = build(listOf(fact("old", 2, 1000, merchant = " Shop "), fact("now", 32, 2500, merchant = "shop"),
            fact("other", 33, 500, merchant = "Shop branch")))
        assertEquals(2, result.merchants.size)
        val change = result.comparison!!.changes.first { it.group.key == "merchant:shop" }
        assertEquals(listOf("now"), change.group.ids); assertEquals(listOf("old"), change.previousIds)
        assertEquals(1500L, change.delta)
    }
}
