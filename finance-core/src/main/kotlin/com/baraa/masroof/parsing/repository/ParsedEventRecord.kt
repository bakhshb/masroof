package com.baraa.masroof.parsing.repository

import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.ParsedEventDetails

/**
 * Reconstructed parse output: domain [ParsedEvent] plus parse-time [ParsedEventDetails].
 *
 * [userCorrected] is true on effective projections that overlay at least one
 * explicit user correction; stored rows are always false.
 *
 * [automationConfirmed] is narrower: it is true only when a correction explicitly
 * changes financial interpretation (message family or amount). Merchant/counterparty-only
 * edits never lift the ParseStatus automation gate.
 */
data class ParsedEventRecord(
    val event: ParsedEvent,
    val details: ParsedEventDetails,
    val userCorrected: Boolean = false,
    val automationConfirmed: Boolean = false,
)
