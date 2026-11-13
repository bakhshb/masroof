package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.EffectiveUserDecisions
import com.baraa.masroof.domain.model.UserDecision

/** Append-only store of explicit user decisions, and the one place processing reads them from. */
interface UserDecisionRepository {
    /**
     * In one database transaction: reads [before], runs [write] and appends [decision] only when [write] returns
     * a result that differs from [before]. A null result, or one equal to [before] (an idempotent repeat that
     * changed nothing), appends nothing; a throw from any step rolls all of them back.
     */
    suspend fun <R : Any> recordWith(decision: UserDecision, before: suspend () -> R?, write: suspend () -> R?): R?

    /** The decisions in force for each of [rawSmsIds]; an SMS without decisions maps to [EffectiveUserDecisions.NONE]. */
    suspend fun effectiveFor(rawSmsIds: Collection<String>): Map<String, EffectiveUserDecisions>

    /** Every `CORRECTION` by its `sequence`, in append order: what learning reads. */
    suspend fun listCorrections(): Map<Long, UserDecision>

    suspend fun effectiveFor(rawSmsId: String): EffectiveUserDecisions = effectiveFor(listOf(rawSmsId)).getValue(rawSmsId)
}
