package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.value.Money
import java.time.*

data class BetaCommitmentEditorDraft(val name: String,val amount: Money,val transactionDate: LocalDate,
    val recurrence: BetaCommitmentRecurrence?,val dueDate: LocalDate?)
enum class BetaCommitmentRecordEvent { CREATED, PAUSED, RESUMED }
data class BetaCommitmentHistoryEntry(val id: String,val name: String,val event: BetaCommitmentRecordEvent,val at: Instant)
interface BetaCommitmentHistorySource { suspend fun commitmentHistory(): List<BetaCommitmentHistoryEntry> }

/** Source settings lifecycle. Revision checks retain concurrent changes; no ledger posting occurs. */
class BetaCommitmentsWorkflow(private val controls: BetaDashboardControls,private val clock: Clock=Clock.systemUTC()) {
    suspend fun read()=controls.read()
    suspend fun history(): List<BetaCommitmentHistoryEntry> =
        checkNotNull(controls as? BetaCommitmentHistorySource) { "Commitment history is unavailable" }.commitmentHistory()
    suspend fun edit(id: String,revision: Long,draft: BetaCommitmentEditorDraft): BetaDashboardConfigurationWrite = change(id,revision) {
        it.copy(label=draft.name.trim(),amount=draft.amount,transactionDate=draft.transactionDate,
            starts=YearMonth.from(draft.transactionDate),dueDay=draft.transactionDate.dayOfMonth,recurrence=draft.recurrence,dueDate=draft.dueDate)
    }
    suspend fun toggle(id: String,revision: Long): BetaDashboardConfigurationWrite = change(id,revision) {row ->
        val now=clock.instant()
        if(row.active) row.copy(active=false,pauseIntervals=row.pauseIntervals+BetaCommitmentPause(now))
        else row.copy(active=true,pauseIntervals=row.pauseIntervals.let {pauses ->
            if(pauses.lastOrNull()?.resumedAt==null && pauses.isNotEmpty()) pauses.dropLast(1)+pauses.last().copy(resumedAt=now) else pauses
        })
    }
    suspend fun delete(id: String,revision: Long): BetaDashboardConfigurationWrite {
        val current=controls.read()
        if(current.revision!=revision) return BetaDashboardConfigurationWrite.Conflict(current)
        if(current.draft.commitments.none {it.id==id}) return BetaDashboardConfigurationWrite.Invalid("Commitment no longer exists")
        return controls.save(revision,current.draft.copy(commitments=current.draft.commitments.filterNot {it.id==id}))
    }
    private suspend fun change(id: String,revision: Long,edit: (BetaRecurringCommitment)->BetaRecurringCommitment): BetaDashboardConfigurationWrite {
        val current=controls.read()
        if(current.revision!=revision) return BetaDashboardConfigurationWrite.Conflict(current)
        val row=current.draft.commitments.singleOrNull {it.id==id} ?: return BetaDashboardConfigurationWrite.Invalid("Commitment no longer exists")
        return controls.save(revision,current.draft.copy(commitments=current.draft.commitments.map {if(it.id==id) edit(row) else it}))
    }
}
