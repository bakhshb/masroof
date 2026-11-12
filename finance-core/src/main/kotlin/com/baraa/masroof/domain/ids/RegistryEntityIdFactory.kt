package com.baraa.masroof.domain.ids

import java.security.MessageDigest
import java.util.UUID

/**
 * Opaque, stable registry entity ids. Bank-scoped identifiers (masked number, last4)
 * are lookup keys — not canonical identity.
 *
 * New rows use random ids ([newAccountId]/[newCardId]). Migrations assign stable ids
 * for existing rows only. Card uniqueness remains (bankId, last4) in the database.
 */
object RegistryEntityIdFactory {
    fun newAccountId(): String = "areg_${randomToken()}"

    fun newCardId(): String = "creg_${randomToken()}"

    fun newCommitmentId(): String = "cmt_${randomToken()}"

    fun stableAccountId(bankId: String, maskedNumber: String): String =
        stable("areg", bankId, maskedNumber.trim())

    fun stableCardId(bankId: String, last4: String): String =
        stable("creg", bankId, last4.trim())

    fun stableCreditFacilityId(bankId: String, primaryLast4: String): String =
        stable("freg", bankId, primaryLast4.trim())

    fun stableLoanId(bankId: String, loanType: String): String =
        stable("lreg", bankId, loanType.trim())

    private fun stable(prefix: String, vararg parts: String): String {
        val canonical = parts.joinToString(separator = "\u001e")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        val hex = digest.toHexString(endIndex = 16)
        return "${prefix}_$hex"
    }

    private fun randomToken(): String =
        UUID.randomUUID().toString().replace("-", "")
}
