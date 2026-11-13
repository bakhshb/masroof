package com.baraa.masroof.testsupport

import androidx.room.withTransaction
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.appendDecision
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.UserDecisionBackfill
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.domain.repository.UserDecisionRepository
import java.time.Instant

/** Fills `user_decision` from review resolutions and selections a test wrote straight into `review_item` (`UserDecisionBackfill`). */
internal fun MasroofDatabase.backfillDecisions() = UserDecisionBackfill.run(openHelper.writableDatabase)

/** [rawSmsId]'s corrections, oldest first. */
internal suspend fun UserDecisionRepository.correctionsFor(rawSmsId: String) = effectiveFor(rawSmsId).corrections

/** Appends [decision] as its production writer does, for tests that seed a decision by hand. */
internal suspend fun MasroofDatabase.recordDecision(decision: UserDecision) = withTransaction { appendDecision(decision) }

/** The `CORRECTION` decision `ReviewWorkflowService.applyCorrection` appends. */
internal fun correction(
    rawSmsId: String,
    family: MessageFamily? = null,
    amount: Money? = null,
    merchant: String? = null,
    counterparty: String? = null,
    at: Instant,
) = UserDecision(rawSmsId, UserDecisionKind.CORRECTION, at, messageFamily = family, amount = amount, merchant = merchant, counterparty = counterparty)

/** [rawSmsId]'s latest correction. */
internal suspend fun UserDecisionRepository.latestCorrectionFor(rawSmsId: String) = correctionsFor(rawSmsId).lastOrNull()

/** Seeds [correction] as `applyCorrection` writes it. */
internal suspend fun MasroofDatabase.saveCorrection(correction: UserDecision) = recordDecision(correction)
