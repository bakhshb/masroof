package com.baraa.masroof.domain.assembly

import com.baraa.masroof.domain.matching.TransferMatchCandidate
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.ParsedEvent
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Resolves the instant used for [com.baraa.masroof.domain.model.FinancialTransaction.occurredAt].
 *
 * SMS body local time ([ParsedEventDetails.occurredAtLocal]) is preferred over inbox
 * [receivedAt] so salary-period filtering matches the bank-stated transaction time.
 */
object TransactionTiming {
    fun zoneFor(
        bank: Bank,
        persistedZoneId: String? = null,
        fallback: ZoneId = ZoneId.systemDefault(),
    ): ZoneId = BankTransactionTimePolicy.resolve(bank, persistedZoneId, fallback)

    fun effectiveOccurredAt(
        event: ParsedEvent,
        occurredAtLocal: LocalDateTime?,
        receivedAt: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
        persistedZoneId: String? = null,
    ): Instant {
        val zone = zoneFor(event.bank, persistedZoneId, zoneId)
        return event.occurredAt
            ?: occurredAtLocal?.atZone(zone)?.toInstant()
            ?: receivedAt
    }

    fun effectiveOccurredAt(
        candidate: TransferMatchCandidate,
        zoneId: ZoneId,
        persistedZoneId: String? = null,
    ): Instant =
        effectiveOccurredAt(
            event = candidate.event,
            occurredAtLocal = candidate.occurredAtLocal,
            receivedAt = candidate.receivedAt,
            zoneId = zoneId,
            persistedZoneId = persistedZoneId,
        )

    fun earliestEffectiveOccurredAt(
        candidates: List<TransferMatchCandidate>,
        zoneId: ZoneId,
    ): Instant? =
        candidates.map { effectiveOccurredAt(it, zoneId) }.minOrNull()

    /**
     * Instant of an offset-less SMS wall clock.
     *
     * [BankTransactionTimePolicy] supplies a bank with a fixed zone. Any other bank
     * uses the zone already stored for it. Returns null when neither is known, so
     * the caller does not reinterpret the wall clock with the handset zone.
     */
    fun bankLocalInstant(
        bank: Bank,
        occurredAtLocal: LocalDateTime,
        persistedZoneId: String? = null,
    ): Instant? {
        val zone = BankTransactionTimePolicy.fixedZone(bank)
            ?: persistedZoneId?.takeIf { it.isNotBlank() }?.let { id ->
                runCatching { ZoneId.of(id) }.getOrNull()
            }
            ?: return null
        return occurredAtLocal.atZone(zone).toInstant()
    }
}
