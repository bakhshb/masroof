package com.baraa.masroof.sms.receiver

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant

/**
 * Canonical receipt time for a live SMS.
 *
 * Prefer the provider/SMSC timestamp Android supplies on each PDU. That is the
 * same clock the inbox `Telephony.Sms.DATE_SENT` column stores (`DATE` is the
 * phone's arrival time), so a later historical copy can share one `receivedAt`. A missing or non-positive timestamp, or one
 * that cannot be represented as an [Instant], or one more than 7 days from the
 * device clock, falls back to the injected device clock.
 *
 * Multipart messages use the earliest valid part timestamp. The choice does not
 * depend on part order when the service-center times differ.
 */
object LiveReceiptTimestamp {
    fun resolve(providerTimestampsMillis: List<Long>, deviceNow: Instant): Instant {
        // A carrier value days away from the phone's clock is broken (zero era, seconds, wrong year), not a delay.
        val valid = providerTimestampsMillis.mapNotNull(::validInstant)
            .filter { Duration.between(it, deviceNow).abs() <= MAX_CARRIER_SKEW }
        return valid.minOrNull() ?: deviceNow
    }

    /** Furthest a stored receipt time may be from the phone's arrival time. */
    val MAX_CARRIER_SKEW: Duration = Duration.ofDays(7)

    private fun validInstant(millis: Long): Instant? {
        if (millis <= 0L) return null
        return try {
            Instant.ofEpochMilli(millis)
        } catch (_: DateTimeException) {
            null
        }
    }
}
