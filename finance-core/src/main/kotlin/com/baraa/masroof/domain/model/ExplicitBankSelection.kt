package com.baraa.masroof.domain.model

/** The routing reasons that offer the user a bank choice. The choice itself is a `BANK_SELECTION` decision. */
object ExplicitBankSelection {
    /** Persisted routing reasons; the single owner of these strings. */
    const val REASON_AMBIGUOUS_BANK_ROUTE: String = "ambiguous_bank_route"

    const val REASON_SUSPECTED_BANK: String = "suspected_bank_sender"

    val ROUTE_REASONS_OFFERING_CHOICE: Set<String> = setOf(
        REASON_AMBIGUOUS_BANK_ROUTE,
        REASON_SUSPECTED_BANK,
    )

    fun offersBankChoice(reasons: List<String>): Boolean =
        reasons.any { it in ROUTE_REASONS_OFFERING_CHOICE }
}
