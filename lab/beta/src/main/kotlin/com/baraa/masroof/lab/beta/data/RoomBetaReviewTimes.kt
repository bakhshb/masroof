package com.baraa.masroof.lab.beta.data

import com.baraa.masroof.lab.finance.corrections.CorrectionSnapshots
import com.baraa.masroof.lab.finance.corrections.FinancialDecision
import java.time.Instant

/** Persisted decision time, never the SMS date, last replay time or a read-time clock. */
internal class RoomBetaReviewTimes(private val database: BetaDatabase) {
    suspend fun ignoredAt(evidenceId: String,correctionOwner: String?,suppressed: Boolean): Instant? {
        if(suppressed && correctionOwner!=null) {
            val current=database.correctionDao().current("BETA",correctionOwner) ?: return null
            val history=CorrectionSnapshots.export(BetaCorrectionCodec.decode(current.payload).overlay).history
            val decision=history.lastOrNull {it.decision==FinancialDecision.SuppressAsNonFinancial} ?: return null
            return database.correctionDao().decisionRecordedAt("BETA",correctionOwner,decision.decisionId)?.let(Instant::parse)
        }
        return database.evidenceDao().decision(evidenceId)?.takeIf {it.decision=="USER_NON_FINANCIAL"}?.updatedAt?.let(Instant::parse)
    }
}
