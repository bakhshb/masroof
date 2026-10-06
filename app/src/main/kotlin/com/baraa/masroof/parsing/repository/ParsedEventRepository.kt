package com.baraa.masroof.parsing.repository

import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.parsing.model.ParsedEventDetails
import java.time.Instant

/**
 * Parsing-facing persistence for structured parse output.
 *
 * Lives under parsing (not domain) because [ParsedEventDetails] is a parsing-layer
 * type. Implementations live in the data layer.
 *
 * Supports replace-by-rawSmsId for future reprocessing without event history.
 */
interface ParsedEventRepository {
    suspend fun save(event: ParsedEvent, details: ParsedEventDetails = ParsedEventDetails())

    suspend fun getById(id: String): ParsedEventRecord?

    suspend fun findByRawSmsId(rawSmsId: String): ParsedEventRecord?

    /**
     * Removes the parsed result row(s) for [rawSmsId] only.
     * Never deletes the related [com.baraa.masroof.domain.model.RawSms] evidence.
     */
    suspend fun deleteByRawSmsId(rawSmsId: String)

    /** All persisted parse results (for ownership discovery backlog). */
    suspend fun listAll(): List<ParsedEventRecord>

    /**
     * Parse results whose [com.baraa.masroof.domain.model.RawSms.receivedAt] falls in
     * `[startInclusive, endExclusive)` — used for incremental reconciliation windows.
     */
    suspend fun listReceivedBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<ParsedEventRecord>

    /**
     * Transfer parse rows with no financial-transaction link yet — used to bound
     * incremental reconciliation without scanning the full backlog.
     */
    suspend fun listUnlinkedTransfers(): List<ParsedEventRecord> =
        listAll().filter { record ->
            record.event.messageFamily == MessageFamily.TRANSFER_IN ||
                record.event.messageFamily == MessageFamily.TRANSFER_OUT
        }
}

/**
 * Reconstructed parse output: domain [ParsedEvent] plus parse-time [ParsedEventDetails].
 *
 * [userCorrected] is true only on effective projections that overlay at least one
 * explicit user correction; stored rows are always false.
 */
data class ParsedEventRecord(
    val event: ParsedEvent,
    val details: ParsedEventDetails,
    val userCorrected: Boolean = false,
)
