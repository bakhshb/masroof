package com.baraa.masroof.sms.receiver

import java.time.DateTimeException
import java.time.Instant

/**
 * Canonical receipt time for a live SMS.
 *
 * Prefer the provider/SMSC timestamp Android supplies on each PDU. That is the
 * same clock the inbox `Telephony.Sms.DATE` column stores, so a later historical
 * copy can share one `receivedAt`. A missing or non-positive timestamp, or one
 * that cannot be represented as an [Instant], falls back to the injected device
 * clock.
 *
 * Multipart messages use the earliest valid part timestamp. The choice does not
 * depend on part order when the service-center times differ.
 */
object LiveReceiptTimestamp {
    fun resolve(providerTimestampsMillis: List<Long>, deviceNow: Instant): Instant {
        val valid = providerTimestampsMillis.mapNotNull(::validInstant)
        return valid.minOrNull() ?: deviceNow
    }

    private fun validInstant(millis: Long): Instant? {
        if (millis <= 0L) return null
        return try {
            Instant.ofEpochMilli(millis)
        } catch (_: DateTimeException) {
            null
        }
    }
}
