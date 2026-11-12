package com.baraa.masroof.domain.model

import com.baraa.masroof.core.money.Money
import java.time.Instant

/**
 * User-supplied correction overlay for stable RawSms evidence.
 *
 * Stored separately from [RawSms] and [ParsedEvent]; never mutates either.
 * Targets [targetRawSmsId] so corrections survive ParsedEvent reprocessing.
 *
 * Ownership changes are not represented here — use
 * `OwnershipConfirmationService` (app, `domain.ownership`).
 */
data class UserCorrection(
    val id: String,
    val targetRawSmsId: String,
    val correctedType: MessageFamily?,
    val correctedAmount: Money?,
    val correctedMerchant: String?,
    val correctedCounterparty: String?,
    val createdAt: Instant,
)
