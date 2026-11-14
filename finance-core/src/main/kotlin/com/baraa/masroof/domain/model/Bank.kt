package com.baraa.masroof.domain.model

/**
 * Stable identifier for a financial institution.
 *
 * Not a closed enum: every bank is the id of its pattern file in
 * `app/src/main/assets/bank-patterns/` (or a bank the user added), e.g. `Bank("BANK_SNB")`,
 * and production code treats every bank the same. [UNKNOWN] marks unresolved detection;
 * [BANK_ALJAZIRA] is only a shorthand for the id most test fixtures use.
 */
@JvmInline
value class Bank(val id: String) {
    init {
        require(id.isNotBlank()) { "Bank id must not be blank" }
    }

    companion object {
        val BANK_ALJAZIRA: Bank = Bank("BANK_ALJAZIRA")
        val UNKNOWN: Bank = Bank("UNKNOWN")

        // Value-class equality is by id, so the known constants need no special case.
        fun fromId(id: String): Bank = Bank(id)
    }
}
