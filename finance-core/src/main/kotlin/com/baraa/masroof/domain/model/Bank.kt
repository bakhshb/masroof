package com.baraa.masroof.domain.model

/**
 * Stable identifier for a financial institution.
 *
 * Not a closed enum of every Saudi bank. Known constants cover the current
 * product need ([BANK_ALJAZIRA]) and unresolved detection ([UNKNOWN]). Other
 * institutions are expressible as `Bank("D360")` (etc.) so cross-bank owned
 * transfers can be modeled without multi-bank parsing or expanding a fixed list.
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
