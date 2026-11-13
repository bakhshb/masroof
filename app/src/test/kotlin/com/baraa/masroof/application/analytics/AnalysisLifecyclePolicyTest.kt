package com.baraa.masroof.application.analytics

import com.baraa.masroof.data.analytics.*
import com.baraa.masroof.analytics.AnalyticsVersion
import org.junit.Assert.*
import org.junit.Test

class AnalysisLifecyclePolicyTest {
    @Test fun permissionRecoveryRequiresANewerCompleteScanCoveringTheWholeGap() {
        val gap = AnalysisCoverageRow("gap",10,12,"PERMISSION",100)
        fun scan(from: Long=10,through: Long=12,at: Long=101) = AnalysisCoverageRow("scan",from,through,"SCAN_RECEIPT",at,at)
        assertEquals(setOf(10L,11L,12L),AnalysisCoveragePolicy.gapDays(AnalysisCoveragePolicy.recover(listOf(gap),emptySet(),200)))
        assertEquals(setOf(10L),AnalysisCoveragePolicy.gapDays(AnalysisCoveragePolicy.recover(listOf(gap,scan(11)),emptySet(),200)))
        assertEquals(setOf(12L),AnalysisCoveragePolicy.gapDays(AnalysisCoveragePolicy.recover(listOf(gap,scan(through=11)),emptySet(),200)))
        assertNull(AnalysisCoveragePolicy.recover(listOf(gap,scan(at=99)),emptySet(),200).first().repairedAt)
        assertEquals(setOf(11L),AnalysisCoveragePolicy.gapDays(AnalysisCoveragePolicy.recover(listOf(gap,scan()),setOf(11),200)))
        val fixed = AnalysisCoveragePolicy.recover(listOf(gap,scan()),emptySet(),200)
        assertEquals(200L,fixed.first().repairedAt)
        assertTrue(AnalysisCoveragePolicy.gapDays(fixed).isEmpty())
        assertEquals(fixed,AnalysisCoveragePolicy.recover(fixed,emptySet(),300))
    }
    @Test fun aStoredProcessingFailureCanCloseAfterRecoveryButALegacyGapNeedsInboxEvidence() {
        val processing = AnalysisCoverageRow("processing",10,10,"PROCESSING",100)
        val legacy = processing.copy(id="old",reason="LEGACY",fromDay=11,throughDay=11)
        assertNull(AnalysisCoveragePolicy.recover(listOf(processing),setOf(10),200).first().repairedAt)
        assertEquals(setOf(11L),AnalysisCoveragePolicy.gapDays(AnalysisCoveragePolicy.recover(listOf(processing,legacy),emptySet(),200)))
    }
    @Test fun anOpenPermissionGapAsksForAnInboxRereadFromItsFirstDayOnceThatDayIsOver() {
        val gap = AnalysisCoverageRow("gap",10,12,"PERMISSION",100)
        assertEquals(10L,AnalysisCoveragePolicy.rescanFrom(listOf(gap),13))
        assertEquals(8L,AnalysisCoveragePolicy.rescanFrom(listOf(gap,gap.copy(id="old",fromDay=8,throughDay=8,reason="LEGACY")),13))
        // Today cannot be certified yet, a repaired gap needs nothing, and a processing gap closes with its retry.
        assertNull(AnalysisCoveragePolicy.rescanFrom(listOf(gap.copy(fromDay=13,throughDay=13)),13))
        assertNull(AnalysisCoveragePolicy.rescanFrom(listOf(gap.copy(repairedAt=200),gap.copy(id="p",reason="PROCESSING"),
            AnalysisCoverageRow("scan",10,12,"SCAN_RECEIPT",150,150),AnalysisCoverageRow("last-check",12,12,"CHECKPOINT",150)),13))
        // The re-read's receipt is what closes it.
        val scan = AnalysisCoverageRow("scan",10,12,"SCAN_RECEIPT",150,150)
        assertNull(AnalysisCoveragePolicy.rescanFrom(AnalysisCoveragePolicy.recover(listOf(gap,scan),emptySet(),200),13))
    }
    private fun comparison(period: Long, personal: Long=150, baseline: Long=110, actual: Long?=100) =
        ForecastComparisonRow("$period",period,period+30,period+7,"a".repeat(64),AnalyticsVersion.MODEL,
            ForecastPerformancePolicy.VERSION,personal,baseline,actual,if(actual!=null) "b".repeat(64) else null,if(actual!=null) period+30 else null,originDay=period+7)
    @Test fun regressionRequiresThreePeriodsAndRecoveryRequiresThreeNewImprovingPeriods() {
        val worse = listOf(comparison(0),comparison(30),comparison(60))
        assertTrue(ForecastPerformancePolicy.evaluate(worse.take(2)).allowPersonal)
        assertFalse(ForecastPerformancePolicy.evaluate(worse).allowPersonal)
        val better = listOf(comparison(90,101,110),comparison(120,101,110),comparison(150,101,110))
        assertFalse(ForecastPerformancePolicy.evaluate(worse+better.take(2)).allowPersonal)
        assertFalse(ForecastPerformancePolicy.evaluate(worse+listOf(90L,120,150).map { comparison(it,100,100) }).allowPersonal)
        assertTrue(ForecastPerformancePolicy.evaluate(worse+better).allowPersonal)
        assertTrue(ForecastPerformancePolicy.evaluate(worse.map { it.copy(actual=null) }).allowPersonal)
    }
    @Test fun zeroBaselineDuplicatesAndOtherVersionsDoNotCreateFalseEvidence() {
        assertFalse(ForecastPerformancePolicy.evaluate(listOf(0L,30,60).map { comparison(it,101,100) }).allowPersonal)
        val single = comparison(0)
        assertEquals(1,ForecastPerformancePolicy.evaluate(List(8) { single.copy(id="$it",issuedAt=it.toLong()) }).periods)
        assertEquals(0,ForecastPerformancePolicy.evaluate(listOf(single.copy(evaluationVersion="old"))).periods)
        val huge = listOf(0L,30,60).map { comparison(it,Long.MAX_VALUE,0,Long.MIN_VALUE) }
        // Monetary differences are evaluated without overflowing Long arithmetic.
        assertFalse(ForecastPerformancePolicy.evaluate(huge).allowPersonal)
    }
    @Test fun recentComparisonsShowTheFirstSupportedRowOfTheLastThreePeriodsIncludingOpenOnes() {
        val rows = listOf(comparison(0),comparison(30),comparison(60),comparison(90,actual=null),
            comparison(90).copy(id="late",issuedAt=200),comparison(120).copy(evaluationVersion="old"))
        val recent = ForecastPerformancePolicy.evaluate(rows).recent
        assertEquals(listOf(30L,60L,90L),recent.map { it.periodStart })
        assertNull(recent.last().actual)
        assertEquals(150L,recent.last().barScale)
    }
    @Test fun firstValidOriginsIncludeLateDaysButExcludeEarlyAndCompletedPeriods() {
        assertFalse(ForecastPerformancePolicy.eligibleOrigin(100,130,106))
        assertTrue(ForecastPerformancePolicy.eligibleOrigin(100,130,107))
        assertTrue(ForecastPerformancePolicy.eligibleOrigin(100,130,111))
        assertTrue(ForecastPerformancePolicy.eligibleOrigin(100,130,129))
        assertFalse(ForecastPerformancePolicy.eligibleOrigin(100,130,130))
    }
    @Test fun legacyPairsKeepPauseHistoryAndDifferentOriginsStillCountOnlyOneVotePerPeriod() {
        val legacy = listOf(0L,30,60).map { comparison(it).copy(evaluationVersion=ForecastPerformancePolicy.LEGACY_VERSION,originDay=null) }
        assertFalse(ForecastPerformancePolicy.evaluate(legacy).allowPersonal)
        assertTrue(ForecastPerformancePolicy.hasComparison(legacy,30))
        val duplicate = comparison(60,100,110).copy(id="new-version",originDay=80,issuedAt=90)
        assertEquals(ForecastPerformancePolicy.evaluate(legacy),ForecastPerformancePolicy.evaluate(legacy+duplicate))
        val recovered = listOf(90L,120,150).map { comparison(it,101,110).copy(originDay=it+12) }
        assertTrue(ForecastPerformancePolicy.evaluate(legacy+recovered).allowPersonal)
        assertEquals(0,ForecastPerformancePolicy.evaluate(listOf(comparison(0).copy(originDay=null))).periods)
    }

}
