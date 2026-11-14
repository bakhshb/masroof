package com.baraa.masroof.domain.assembly

import com.baraa.masroof.domain.matching.TransferMatchCandidate
import com.baraa.masroof.domain.model.ParsedEvent
import java.time.Instant
import java.time.LocalDateTime

/**
 * Resolves the instant used for [com.baraa.masroof.domain.model.FinancialTransaction.occurredAt].
 *
 * SMS body local time ([ParsedEventDetails.occurredAtLocal]) is preferred over inbox
 * [receivedAt] so salary-period filtering matches the bank-stated transaction time.
 * The wall clock is read in [BankTransactionTimePolicy.RIYADH].
 */
object TransactionTiming {
    fun effectiveOccurredAt(
        event: ParsedEvent,
        occurredAtLocal: LocalDateTime?,
        receivedAt: Instant,
    ): Instant =
        event.occurredAt
            ?: occurredAtLocal?.let(::bankLocalInstant)
            ?: receivedAt

    fun effectiveOccurredAt(candidate: TransferMatchCandidate): Instant =
        effectiveOccurredAt(candidate.event, candidate.occurredAtLocal, candidate.receivedAt)

    /** Instant of an offset-less SMS wall clock. */
    fun bankLocalInstant(occurredAtLocal: LocalDateTime): Instant =
        occurredAtLocal.atZone(BankTransactionTimePolicy.RIYADH).toInstant()
}
