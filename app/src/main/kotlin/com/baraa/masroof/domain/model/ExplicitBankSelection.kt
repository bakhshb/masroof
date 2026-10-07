package com.baraa.masroof.domain.model

/**
 * Per-RawSms bank choice recorded on the review row.
 *
 * The choice is one reason, `user_selected_bank:<bankId>`. Queue refresh replaces
 * the other reasons; [mergePreservedSelection] copies this prefix forward so a
 * later reprocess still parses with the adapter the user named.
 */
object ExplicitBankSelection {
    const val REASON_PREFIX: String = "user_selected_bank:"

    /** Persisted ingestion reason. Matches [com.baraa.masroof.bank.BankRoutingResult]. */
    const val REASON_AMBIGUOUS_BANK_ROUTE: String = "ambiguous_bank_route"

    /**
     * Forward-compatible with suspected-bank quarantine. The reason is recognized
     * here so a review created by that phase can offer the same action.
     */
    const val REASON_SUSPECTED_BANK: String = "suspected_bank_sender"

    val ROUTE_REASONS_OFFERING_CHOICE: Set<String> = setOf(
        REASON_AMBIGUOUS_BANK_ROUTE,
        REASON_SUSPECTED_BANK,
    )

    fun isSelection(reason: String): Boolean = reason.startsWith(REASON_PREFIX)

    fun offersBankChoice(reasons: List<String>): Boolean =
        reasons.any { it in ROUTE_REASONS_OFFERING_CHOICE }

    fun reasonFor(bank: Bank): String = REASON_PREFIX + bank.id

    fun selectedBankId(reasons: List<String>): String? {
        val ids = reasons.mapNotNull { reason ->
            if (!isSelection(reason)) return@mapNotNull null
            reason.removePrefix(REASON_PREFIX).takeIf { it.isNotBlank() }
        }
        return ids.singleOrNull()
    }

    /** Replaces any previous selection with [bank]. Other reasons stay. */
    fun withSelection(reasons: List<String>, bank: Bank): List<String> =
        (reasons.filterNot(::isSelection) + reasonFor(bank)).distinct().sorted()

    /**
     * Incoming queue reasons win, except a selection already stored on the row
     * is kept when the incoming list does not name a new one.
     */
    fun mergePreservedSelection(existing: List<String>, incoming: List<String>): List<String> {
        val incomingSelection = incoming.filter(::isSelection)
        val preserved = if (incomingSelection.isNotEmpty()) {
            listOf(incomingSelection.last())
        } else {
            existing.filter(::isSelection).takeLast(1)
        }
        return (incoming.filterNot(::isSelection) + preserved).distinct().sorted()
    }
}
