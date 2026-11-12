package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.UserDecision

/** Append-only store of explicit user decisions. */
interface UserDecisionRepository {
    /**
     * Runs [write] and, when it returns a result, appends [decision], both in one database transaction.
     * A null result appends nothing; a throw from either rolls both back.
     */
    suspend fun <R : Any> recordWith(decision: UserDecision, write: suspend () -> R?): R?
}
