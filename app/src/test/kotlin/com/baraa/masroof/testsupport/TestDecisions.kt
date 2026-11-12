package com.baraa.masroof.testsupport

import androidx.room.withTransaction
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.repository.appendDecision
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.UserDecisionBackfill
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.domain.repository.UserCorrectionRepository

/**
 * Fills `user_decision` from review resolutions, selections and corrections a test wrote straight into the legacy
 * stores, as the v20 upgrade (`UserDecisionBackfill`) does for an old install.
 */
internal fun MasroofDatabase.backfillDecisions() = UserDecisionBackfill.run(openHelper.writableDatabase)

/** Appends [decision] as its production writer does, for tests that seed the legacy store by hand. */
internal suspend fun MasroofDatabase.recordDecision(decision: UserDecision) = withTransaction { appendDecision(decision) }

/** The `CORRECTION` decision `ReviewWorkflowService.applyCorrection` appends with [correction]. */
internal fun correctionDecision(correction: UserCorrection) = UserDecision(
    rawSmsId = correction.targetRawSmsId,
    kind = UserDecisionKind.CORRECTION,
    decidedAt = correction.createdAt,
    messageFamily = correction.correctedType,
    amount = correction.correctedAmount,
    merchant = correction.correctedMerchant,
    counterparty = correction.correctedCounterparty,
)

/** Seeds [correction] as production writes it: the `user_correction` row and its decision, in one transaction. */
internal suspend fun MasroofDatabase.saveCorrection(correction: UserCorrection) = withTransaction {
    RoomUserCorrectionRepository(userCorrectionDao()).save(correction)
    appendDecision(correctionDecision(correction))
}

/**
 * For tests that seed corrections directly: [save] also appends the decision, like `applyCorrection`.
 * Never hand it to a `ReviewWorkflowService`, which appends the decision itself.
 */
internal class DecidedCorrectionRepository(private val db: MasroofDatabase) :
    UserCorrectionRepository by RoomUserCorrectionRepository(db.userCorrectionDao()) {
    override suspend fun save(correction: UserCorrection) = db.saveCorrection(correction)
}
