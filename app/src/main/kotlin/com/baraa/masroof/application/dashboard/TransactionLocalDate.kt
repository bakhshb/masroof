package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.assembly.BankTransactionTimePolicy
import com.baraa.masroof.domain.model.FinancialTransaction
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * A transaction's zone and civil date from its stored [FinancialTransaction.occurredAtZone],
 * so a late-evening purchase stays on its own day. [fallback] only fills a missing or invalid zone.
 * Each distinct zone string is parsed once.
 */
object TransactionLocalDate {
    // Invalid ids are cached too (as Optional.empty), so a bad stored zone is not re-parsed for every row.
    private val parsed = ConcurrentHashMap<String, java.util.Optional<ZoneId>>()

    fun storedZoneOrNull(tx: FinancialTransaction): ZoneId? {
        val id = tx.occurredAtZone ?: return null
        return parsed.getOrPut(id) { java.util.Optional.ofNullable(BankTransactionTimePolicy.storedZoneOrNull(id)) }.orElse(null)
    }

    fun zoneOf(tx: FinancialTransaction, fallback: ZoneId): ZoneId = storedZoneOrNull(tx) ?: fallback

    fun of(tx: FinancialTransaction, fallback: ZoneId): LocalDate = tx.occurredAt.atZone(zoneOf(tx, fallback)).toLocalDate()
}
