package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.beta.application.dashboard.BetaCommitmentOccurrenceCalculator
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.value.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class BetaCommitmentLifecycleTest {
    private fun row(date: String="2026-09-24",recurrence: BetaCommitmentRecurrence?=BetaCommitmentRecurrence.WEEKLY)=
        LocalDate.parse(date).let {BetaRecurringCommitment("one","Name",Money.of("100",Currency.SAR),it.dayOfMonth,YearMonth.from(it),recurrence=recurrence,transactionDate=it,sourceTransactionId="posted-source")}
    private fun dates(row: BetaRecurringCommitment,from: String,until: String)=BetaCommitmentOccurrenceCalculator.dates(row,LocalDate.parse(from),LocalDate.parse(until))
    @Test fun weeklyYearlyLeapDayAndOneOffUseSourceHalfOpenDates() {
        assertEquals(listOf("2026-10-01","2026-10-08","2026-10-15","2026-10-22"),dates(row(),"2026-10-01","2026-10-27").map {it.toString()})
        assertEquals(listOf(LocalDate.parse("2025-02-28")),dates(row("2024-02-29",BetaCommitmentRecurrence.YEARLY),"2025-02-27","2025-03-27"))
        assertEquals(listOf(LocalDate.parse("2024-02-29")),dates(row("2024-02-29",null),"2024-02-01","2024-03-01"))
        assertTrue(dates(row("2026-11-01"),"2026-10-01","2026-10-27").isEmpty())
    }
    @Test fun emptyCommitmentsDoNotConstrainIndependentDashboardEvidenceWindows() {
        val zone=ZoneOffset.UTC
        listOf(Instant.EPOCH.plusSeconds(1),Instant.parse("2026-10-01T00:00:00Z")).forEach {end ->
            assertTrue(BetaCommitmentOccurrenceCalculator.rows(BetaDashboardConfigurationDraft(),
                DisplayPeriod(Instant.EPOCH,end,zone,PeriodKind.SALARY,null)).isEmpty())
        }
    }
    @Test fun monthlyClampingReturnsToAnchorDayAfterFebruary() {
        val row=row("2026-01-31",BetaCommitmentRecurrence.MONTHLY)
        assertEquals(listOf("2026-02-28","2026-03-31"),dates(row,"2026-02-01","2026-04-01").map {it.toString()})
    }
    private fun period(from: String,until: String)=ZoneId.of("Asia/Riyadh").let {zone -> DisplayPeriod(LocalDate.parse(from).atStartOfDay(zone).toInstant(),LocalDate.parse(until).atStartOfDay(zone).toInstant(),zone,PeriodKind.SALARY,null)}
    @Test fun pauseHidesOnlyPausedSalaryCyclesAndResumeRestoresLaterCycles() {
        val row=row("2026-01-05",BetaCommitmentRecurrence.MONTHLY).copy(pauseIntervals=listOf(BetaCommitmentPause(Instant.parse("2026-10-05T12:00:00Z"),Instant.parse("2026-10-29T12:00:00Z"))))
        val draft=BetaDashboardConfigurationDraft(BetaSalaryCycle(27),commitments=listOf(row))
        assertTrue(BetaConfiguredPeriods.occurrences(draft,period("2026-09-27","2026-10-27")).isEmpty())
        assertEquals(1,BetaConfiguredPeriods.occurrences(draft,period("2026-10-27","2026-11-26")).size)
        assertEquals(1,BetaConfiguredPeriods.occurrences(draft,period("2026-08-27","2026-09-27")).size)
        assertTrue(BetaConfiguredPeriods.occurrences(draft.copy(commitments=listOf(row.copy(active=false,pauseIntervals=emptyList()))),period("2026-10-27","2026-11-26")).isEmpty())
    }
    private class Store(row: BetaRecurringCommitment): BetaDashboardControls {
        var value=BetaDashboardConfiguration(1,BetaDashboardConfigurationDraft(commitments=listOf(row)))
        override suspend fun read()=value
        override suspend fun save(expectedRevision: Long,draft: BetaDashboardConfigurationDraft): BetaDashboardConfigurationWrite {
            if(expectedRevision!=value.revision) return BetaDashboardConfigurationWrite.Conflict(value)
            value=BetaDashboardConfiguration(value.revision+1,draft);return BetaDashboardConfigurationWrite.Saved(value)
        }
    }
    @Test fun missingHistoryProviderIsAnExplicitFailureRatherThanAnEmptyRecord()=runBlocking {
        try {
            BetaCommitmentsWorkflow(Store(row())).history()
            fail("Unavailable history must not look empty")
        } catch(expected: IllegalStateException) {
            assertEquals("Commitment history is unavailable",expected.message)
        }
    }
    @Test fun editAndToggleRetainSourceIdentityAndConcurrentWritesAreRejected()=runBlocking {
        val original=row();val store=Store(original)
        val flow=BetaCommitmentsWorkflow(store,Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"),ZoneOffset.UTC))
        assertTrue(flow.toggle("one",1) is BetaDashboardConfigurationWrite.Saved)
        assertFalse(store.value.draft.commitments.single().active)
        assertTrue(flow.delete("one",1) is BetaDashboardConfigurationWrite.Conflict)
        val draft=BetaCommitmentEditorDraft("Renamed",original.amount,original.anchorDate,BetaCommitmentRecurrence.YEARLY,LocalDate.parse("2026-10-15"))
        assertTrue(flow.edit("one",2,draft) is BetaDashboardConfigurationWrite.Saved)
        val changed=store.value.draft.commitments.single()
        assertEquals("posted-source",changed.sourceTransactionId);assertEquals(original.id,changed.id)
        assertFalse(changed.active);assertEquals(1,changed.pauseIntervals.size)
        assertTrue(flow.toggle("one",3) is BetaDashboardConfigurationWrite.Saved)
        assertTrue(store.value.draft.commitments.single().active)
        assertNotNull(store.value.draft.commitments.single().pauseIntervals.single().resumedAt)
        assertTrue(flow.delete("one",4) is BetaDashboardConfigurationWrite.Saved)
        assertTrue(store.value.draft.commitments.isEmpty())
    }
}
