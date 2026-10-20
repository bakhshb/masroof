package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.value.Money
import com.baraa.masroof.lab.finance.value.Currency
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the same lock-retaining facade used by settings, including a cold reopen. */
class BetaCommitmentLifecyclePersistenceTest {
    @Test fun lifecycleHistoryAndOnceRecurrenceSurviveRealContainerReopen()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        BetaContainer.resetOffline(context)
        try {
            BetaContainer(context).use {beta ->
                val controls=beta.dashboardControls()
                val date=LocalDate.of(2026,10,5)
                val row=BetaRecurringCommitment("rent","Synthetic rent",Money.of("100",Currency.SAR),5,YearMonth.from(date),
                    recurrence=null,transactionDate=date,dueDate=date.plusDays(3),sourceTransactionId="original-source")
                val initial=controls.save(0,BetaDashboardConfigurationDraft(commitments=listOf(row))) as BetaDashboardConfigurationWrite.Saved
                val workflow=BetaCommitmentsWorkflow(controls,Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"),ZoneOffset.UTC))
                val paused=workflow.toggle(row.id,initial.configuration.revision) as BetaDashboardConfigurationWrite.Saved
                val resumed=BetaCommitmentsWorkflow(controls,Clock.fixed(Instant.parse("2100-01-01T00:00:00.100Z"),ZoneOffset.UTC))
                    .toggle(row.id,paused.configuration.revision) as BetaDashboardConfigurationWrite.Saved
                val edited=workflow.edit(row.id,resumed.configuration.revision,
                    BetaCommitmentEditorDraft("Updated rent",Money.of("120",Currency.SAR),date,null,date.plusDays(4))) as BetaDashboardConfigurationWrite.Saved
                assertEquals(row.sourceTransactionId,edited.configuration.draft.commitments.single().sourceTransactionId)
                assertTrue(workflow.toggle(row.id,initial.configuration.revision) is BetaDashboardConfigurationWrite.Conflict)
                assertEquals(listOf(BetaCommitmentRecordEvent.RESUMED,BetaCommitmentRecordEvent.PAUSED,BetaCommitmentRecordEvent.CREATED),workflow.history().map {it.event})
            }
            BetaContainer(context).use {beta ->
                val workflow=BetaCommitmentsWorkflow(beta.dashboardControls())
                val config=workflow.read();val row=config.draft.commitments.single()
                assertEquals("Updated rent",row.label);assertEquals(Money.of("120",Currency.SAR),row.amount)
                assertNull(row.recurrence);assertTrue(row.active);assertEquals("original-source",row.sourceTransactionId)
                assertEquals(LocalDate.of(2026,10,9),row.dueDate)
                assertEquals(listOf(BetaCommitmentPause(Instant.parse("2100-01-01T00:00:00Z"),Instant.parse("2100-01-01T00:00:00.100Z"))),row.pauseIntervals)
                assertEquals(3,workflow.history().size)
                assertTrue(workflow.delete(row.id,config.revision) is BetaDashboardConfigurationWrite.Saved)
                assertTrue(workflow.read().draft.commitments.isEmpty());assertTrue(workflow.history().isEmpty())
            }
        } finally {BetaContainer.resetOffline(context)}
    }

    @Test fun historySortsParsedInstantsRatherThanFractionalTimestampText()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,BetaDatabase::class.java).build()
        try {
            val dao=db.dashboardConfigurationDao()
            dao.saveCommitments(listOf(BetaCommitmentRecord("rent","Synthetic", "10","SAR",5,"2026-10",null,true,"[]","[]",null)))
            dao.appendAudit(BetaAuditRecord("later-fraction","COMMITMENT_LIFECYCLE",targetId="rent",transition=2,operation="RESUMED",createdAt="2026-10-05T00:00:00.100Z"))
            dao.appendAudit(BetaAuditRecord("earlier-second","COMMITMENT_LIFECYCLE",targetId="rent",transition=1,operation="PAUSED",createdAt="2026-10-05T00:00:00Z"))
            assertEquals(listOf(BetaCommitmentRecordEvent.RESUMED,BetaCommitmentRecordEvent.PAUSED),RoomBetaDashboardConfiguration(db).commitmentHistory().map {it.event})
        } finally {db.close()}
    }

    @Test fun legacyMetadataEditPreservesExplicitOnceRecurrence() {
        val original=BetaCommitmentRecord("once","Synthetic", "10","SAR",5,"2026-10",null,true,"[]","[]","source",null,"2026-10-05","2026-10-09")
        val legacy=org.json.JSONObject("""{"id":"once","label":"Renamed","amount":"11","currency":"SAR","day":5,"starts":"2026-10","ends":null,"paid":[]}""")
        val result=BetaCommitmentCodec.record(legacy,original)
        assertNull(result.recurrence);assertEquals(original.transactionDate,result.transactionDate)
        assertEquals(original.dueDate,result.dueDate);assertEquals(original.sourceTransactionId,result.sourceTransactionId)
    }
}
