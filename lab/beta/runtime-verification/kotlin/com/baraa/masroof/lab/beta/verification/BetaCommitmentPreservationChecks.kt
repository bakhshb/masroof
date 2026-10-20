package com.baraa.masroof.lab.beta.verification

import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.data.*
import com.baraa.masroof.lab.finance.value.*
import org.junit.Assert.*
import java.time.YearMonth

/** Same normalized-storage regression on Robolectric and actual device SQLite. */
internal object BetaCommitmentPreservationChecks {
    suspend fun editsPreserveRegistryFacts(db: BetaDatabase): BetaCommitmentRecord {
        val controls=RoomBetaDashboardConfiguration(db)
        val draft=BetaDashboardConfigurationDraft(BetaSalaryCycle(27),commitments=listOf(
            BetaRecurringCommitment("rent","Synthetic rent",Money.of("100",Currency.SAR),5,YearMonth.of(2026,1),paidMonths=setOf(YearMonth.of(2026,10)))))
        assertTrue(controls.save(0,draft) is BetaDashboardConfigurationWrite.Saved)
        val original=db.dashboardConfigurationDao().commitments().single().copy(active=false,
            pauseIntervals="[{\"pausedAt\":\"2026-10-01T12:00:00Z\",\"resumedAt\":null}]",sourceTransactionId="synthetic-posted-source")
        db.dashboardConfigurationDao().clearCommitments()
        db.dashboardConfigurationDao().saveCommitments(listOf(original))
        val current=controls.read()
        val changed=controls.save(current.revision,current.draft.copy(salary=BetaSalaryCycle(26))) as BetaDashboardConfigurationWrite.Saved
        assertEquals(original,db.dashboardConfigurationDao().commitments().single())
        val row=changed.configuration.draft.commitments.single()
        val edited=BetaCommitmentsWorkflow(controls).edit(row.id,changed.configuration.revision,
            BetaCommitmentEditorDraft("Renamed",row.amount,row.anchorDate,row.recurrence,row.dueDate))
        assertTrue(edited is BetaDashboardConfigurationWrite.Saved)
        val expected=original.copy(label="Renamed",transactionDate=row.anchorDate.toString())
        assertEquals(expected,db.dashboardConfigurationDao().commitments().single())
        return expected
    }
}
