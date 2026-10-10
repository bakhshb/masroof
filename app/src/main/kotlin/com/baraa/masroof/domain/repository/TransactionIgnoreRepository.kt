package com.baraa.masroof.domain.repository

import java.time.Instant

/** Atomic user decision plus deletion of an exclusive single-SMS financial movement. */
fun interface TransactionIgnoreRepository {
    suspend fun ignoreSingle(transactionId: String, resolvedAt: Instant): TransactionIgnoreOutcome
}

sealed interface TransactionIgnoreOutcome {
    data object Ignored : TransactionIgnoreOutcome
    data class Rejected(val reason: String) : TransactionIgnoreOutcome
}
