package com.baraa.masroof.application.commitment

import com.baraa.masroof.testsupport.sampleCommitment
import com.baraa.masroof.domain.model.CommitmentPauseInterval
import com.baraa.masroof.domain.model.CommitmentRecurrence
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CommitmentPauseTransitionsTest {
    private val zone = ZoneId.of("Asia/Riyadh")

    @Test
    fun pause_appendsOpenInterval() {
        val commitment = sampleCommitment(active = true)
        val now = Instant.parse("2026-04-01T12:00:00Z")

        val paused = CommitmentPauseTransitions.pause(commitment, now)

        assertFalse(paused.active)
        assertEquals(1, paused.pauseIntervals.size)
        assertEquals(now, paused.pauseIntervals.single().pausedAt)
        assertEquals(null, paused.pauseIntervals.single().resumedAt)
    }

    @Test
    fun resume_closesLastInterval() {
        val pausedAt = Instant.parse("2026-04-01T12:00:00Z")
        val resumedAt = Instant.parse("2026-09-01T12:00:00Z")
        val commitment = sampleCommitment(active = false).copy(
            pauseIntervals = listOf(CommitmentPauseInterval(pausedAt = pausedAt, resumedAt = null)),
        )

        val resumed = CommitmentPauseTransitions.resume(commitment, resumedAt)

        assertTrue(resumed.active)
        assertEquals(resumedAt, resumed.pauseIntervals.single().resumedAt)
    }

    @Test
    fun resume_withoutOpenInterval_isNoOp() {
        val commitment = sampleCommitment(active = false)

        val resumed = CommitmentPauseTransitions.resume(
            commitment,
            Instant.parse("2026-09-01T12:00:00Z"),
        )

        assertEquals(commitment, resumed)
    }

    @Test
    fun janToMar_active_visibleInMarkedAndFollowingMonths() {
        val commitment = sampleCommitment(
            transactionDate = LocalDate.parse("2026-01-15"),
            recurrence = CommitmentRecurrence.MONTHLY,
            active = true,
        )
        val janPeriod = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-01-15"))
        val febPeriod = FinancialPeriodPolicy.next(janPeriod)
        val marPeriod = FinancialPeriodPolicy.next(febPeriod)

        assertTrue(CommitmentPauseTransitions.isVisibleInSalaryPeriod(commitment, janPeriod, zone))
        assertTrue(CommitmentPauseTransitions.isVisibleInSalaryPeriod(commitment, febPeriod, zone))
        assertTrue(CommitmentPauseTransitions.isVisibleInSalaryPeriod(commitment, marPeriod, zone))
    }

    @Test
    fun aprToJul_paused_hiddenOnlyInPausedMonths() {
        val pausedAt = Instant.parse("2026-04-01T00:00:00Z")
        val resumedAt = Instant.parse("2026-09-01T00:00:00Z")
        val commitment = sampleCommitment(
            transactionDate = LocalDate.parse("2026-01-15"),
            recurrence = CommitmentRecurrence.MONTHLY,
            active = false,
        ).copy(
            pauseIntervals = listOf(
                CommitmentPauseInterval(pausedAt = pausedAt, resumedAt = resumedAt),
            ),
        )

        val marPeriod = FinancialPeriodPolicy.previous(
            FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-04-01")),
        )
        val aprPeriod = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-04-01"))
        val julPeriod = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-07-01"))
        val augPeriod = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-01"))
        val sepPeriod = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-09-01"))

        assertTrue(CommitmentPauseTransitions.isVisibleInSalaryPeriod(commitment, marPeriod, zone))
        assertFalse(CommitmentPauseTransitions.isVisibleInSalaryPeriod(commitment, aprPeriod, zone))
        assertFalse(CommitmentPauseTransitions.isVisibleInSalaryPeriod(commitment, julPeriod, zone))
        assertFalse(CommitmentPauseTransitions.isVisibleInSalaryPeriod(commitment, augPeriod, zone))
        assertTrue(CommitmentPauseTransitions.isVisibleInSalaryPeriod(commitment, sepPeriod, zone))
    }

    @Test
    fun pausedCommitment_hiddenDuringOpenPauseInterval() {
        val pausedAt = Instant.parse("2026-08-15T00:00:00Z")
        val commitment = sampleCommitment(
            transactionDate = LocalDate.parse("2026-07-01"),
            recurrence = CommitmentRecurrence.MONTHLY,
            active = false,
        ).copy(
            pauseIntervals = listOf(CommitmentPauseInterval(pausedAt = pausedAt, resumedAt = null)),
        )
        val previousPeriod = FinancialPeriodPolicy.previous(
            FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-15")),
        )
        val disabledPeriod = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-15"))

        assertTrue(CommitmentPauseTransitions.isVisibleInSalaryPeriod(commitment, previousPeriod, zone))
        assertFalse(CommitmentPauseTransitions.isVisibleInSalaryPeriod(commitment, disabledPeriod, zone))
    }
}
