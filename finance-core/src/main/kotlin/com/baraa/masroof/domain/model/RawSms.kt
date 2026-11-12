package com.baraa.masroof.domain.model

import java.time.Instant

/**
 * Immutable original SMS evidence from the device.
 *
 * Never overwritten by parser output or user corrections. Corrections are stored
 * separately as [UserCorrection].
 */
data class RawSms(
    val id: String,
    val sender: String,
    val body: String,
    val receivedAt: Instant,
    val deviceMessageId: String?,
    val bodyHash: String,
)
