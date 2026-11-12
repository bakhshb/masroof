package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/**
 * Parsed/raw SMS evidence one dashboard projection may read.
 *
 * [parsedRecords] are distinct and ordered by event id — the order dashboard calculators
 * iterate when a rule picks the first/last matching row. [rawSmsById] holds the RawSms of
 * every record.
 */
data class DashboardEvidence(
    val parsedRecords: List<ParsedEventRecord>,
    val rawSmsById: Map<String, RawSms>,
) {
    private val eventIds: Set<String> by lazy { parsedRecords.mapTo(hashSetOf()) { it.event.id } }

    fun covers(transaction: FinancialTransaction): Boolean =
        transaction.linkedParsedEventIds.all { it in eventIds }

    operator fun plus(other: DashboardEvidence): DashboardEvidence {
        if (other.parsedRecords.isEmpty() && other.rawSmsById.isEmpty()) return this
        return DashboardEvidence(
            parsedRecords = (parsedRecords + other.parsedRecords)
                .distinctBy { it.event.id }
                .sortedBy { it.event.id },
            rawSmsById = rawSmsById + other.rawSmsById,
        )
    }

    companion object {
        val EMPTY = DashboardEvidence(emptyList(), emptyMap())
    }
}
