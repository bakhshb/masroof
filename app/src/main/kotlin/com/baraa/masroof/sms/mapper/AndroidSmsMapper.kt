package com.baraa.masroof.sms.mapper

import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.sms.model.ProviderSmsRecord
import java.time.Instant

/**
 * Maps Android/provider SMS evidence into immutable [RawSms].
 *
 * ID strategy:
 * - Provider/device message ID present → `android-sms:<messageId>`
 *   (stable across historical re-scans of the same inbox row)
 * - Live / no provider ID → `android-sms-live:<sender>|<epochMillis>|<bodyHash>`
 *   (deterministic; converges with historical via P5 dedupeKey on
 *   sender + timestamp + bodyHash)
 *
 * Never random UUID. Never hashes normalized body.
 */
object AndroidSmsMapper {
    private const val PROVIDER_ID_PREFIX = "android-sms:"
    private const val LIVE_ID_PREFIX = "android-sms-live:"

    fun toRawSms(record: ProviderSmsRecord): RawSms {
        require(record.sender.isNotBlank()) { "SMS sender must not be blank" }
        require(record.body.isNotEmpty()) { "SMS body must not be empty" }
        val bodyHash = SmsBodyHasher.sha256Hex(record.body)
        val id = rawSmsId(
            providerMessageId = record.providerMessageId,
            sender = record.sender,
            receivedAt = record.receivedAt,
            bodyHash = bodyHash,
        )
        return RawSms(
            id = id,
            sender = record.sender,
            body = record.body,
            receivedAt = record.receivedAt,
            deviceMessageId = record.providerMessageId,
            bodyHash = bodyHash,
        )
    }

    fun rawSmsId(
        providerMessageId: String?,
        sender: String,
        receivedAt: Instant,
        bodyHash: String,
    ): String {
        val deviceId = providerMessageId?.takeIf { it.isNotBlank() }
        return if (deviceId != null) {
            "$PROVIDER_ID_PREFIX$deviceId"
        } else {
            "$LIVE_ID_PREFIX$sender|${receivedAt.toEpochMilli()}|$bodyHash"
        }
    }
}
