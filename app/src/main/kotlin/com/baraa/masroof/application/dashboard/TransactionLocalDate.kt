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
    private val parsed = ConcurrentHashMap<String, ZoneId>()

    fun storedZoneOrNull(tx: FinancialTransaction): ZoneId? {
        val id = tx.occurredAtZone ?: return null
        return parsed[id] ?: BankTransactionTimePolicy.storedZoneOrNull(id)?.also { parsed[id] = it }
    }

    fun zoneOf(tx: FinancialTransaction, fallback: ZoneId): ZoneId = storedZoneOrNull(tx) ?: fallback

    fun of(tx: FinancialTransaction, fallback: ZoneId): LocalDate = tx.occurredAt.atZone(zoneOf(tx, fallback)).toLocalDate()
}
