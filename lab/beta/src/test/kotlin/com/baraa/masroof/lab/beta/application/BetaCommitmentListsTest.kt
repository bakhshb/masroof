package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.value.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class BetaCommitmentListsTest {
    private val zone=ZoneId.of("Asia/Riyadh")
    private fun row(id: String,name: String,active: Boolean=true,pause: String?=null)=
        BetaRecurringCommitment(id,name,Money.of("1",Currency.SAR),5,YearMonth.of(2026,10),active=active,
            pauseIntervals=pause?.let {listOf(BetaCommitmentPause(Instant.parse(it)))}.orEmpty())
    @Test fun activeNamesUseSourceNoCaseAndDisabledRowsUseLocalPauseDayWithNameTies() {
        val a=row("a","alpha");val z=row("z","Zulu");val accented=row("b","Á")
        assertEquals(listOf(a,z,accented),BetaCommitmentLists.rows(listOf(accented,z,a),true,zone))
        val older=row("old","Old",false,"2026-10-01T23:00:00Z")
        val lateA=row("late-a","Alpha",false,"2026-10-03T00:00:00Z")
        val lateZ=row("late-z","Zulu",false,"2026-10-03T20:00:00Z")
        assertEquals(listOf(lateA,lateZ,older),BetaCommitmentLists.rows(listOf(older,lateZ,lateA,a),false,zone))
    }
    @Test fun historyUsesSourceNameAndEventTieBreaksAtTheSameInstant() {
        val at=Instant.parse("2026-10-05T00:00:00Z")
        val paused=BetaCommitmentHistoryEntry("a","Alpha",BetaCommitmentRecordEvent.PAUSED,at)
        val created=paused.copy(event=BetaCommitmentRecordEvent.CREATED)
        val z=paused.copy(id="z",name="Zulu")
        assertEquals(listOf(created,paused,z),BetaCommitmentLists.history(listOf(z,paused,created)))
    }
}
