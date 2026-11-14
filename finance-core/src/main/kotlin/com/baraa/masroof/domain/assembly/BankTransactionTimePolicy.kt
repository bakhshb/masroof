package com.baraa.masroof.domain.assembly

import java.time.ZoneId

/**
 * How an offset-less SMS wall clock becomes an [java.time.Instant].
 *
 * This object is the only bank-to-zone mapping. Adapters do not carry a second copy.
 *
 * Every supported bank is Saudi and prints civil time with no offset, so every bank,
 * [com.baraa.masroof.domain.model.Bank.UNKNOWN] included, reads its wall clock in [RIYADH]
 * (`Asia/Riyadh`, UTC+3, no daylight-saving time). The handset zone is not that clock:
 * using it would move a late-evening purchase onto the next salary-period day after a
 * timezone change. A bank in another zone would make this a per-bank map again.
 *
 * Opt-in statement reconciliation uses this same zone for offset-less CSV `bookedAt`
 * values. Do not add a second bank-to-zone table for statements.
 */
object BankTransactionTimePolicy {
    val RIYADH: ZoneId = ZoneId.of("Asia/Riyadh")
}
