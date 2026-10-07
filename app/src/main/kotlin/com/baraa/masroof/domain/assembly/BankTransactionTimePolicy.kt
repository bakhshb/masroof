package com.baraa.masroof.domain.assembly

import com.baraa.masroof.domain.model.Bank
import java.time.ZoneId

/**
 * How an offset-less SMS wall clock becomes an [java.time.Instant].
 *
 * Bank AlJazira prints civil time with no offset. Saudi Arabia's only civil
 * zone is [ALJAZIRA] (`Asia/Riyadh`, UTC+3, no daylight-saving time). The
 * handset zone is not that clock: using it would move a late-evening purchase
 * onto the next salary-period day after a timezone change.
 *
 * A bank without a fixed zone keeps the zone stored on its first transaction.
 * [fallback] is only for the first resolution of such a bank.
 */
object BankTransactionTimePolicy {
    val ALJAZIRA: ZoneId = ZoneId.of("Asia/Riyadh")

    fun fixedZone(bank: Bank): ZoneId? =
        if (bank == Bank.BANK_ALJAZIRA) ALJAZIRA else null

    fun resolve(bank: Bank, persistedZoneId: String?, fallback: ZoneId): ZoneId {
        fixedZone(bank)?.let { return it }
        if (!persistedZoneId.isNullOrBlank()) {
            runCatching { ZoneId.of(persistedZoneId) }.getOrNull()?.let { return it }
        }
        return fallback
    }
}
