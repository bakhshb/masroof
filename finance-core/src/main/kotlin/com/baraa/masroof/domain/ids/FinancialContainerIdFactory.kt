package com.baraa.masroof.domain.ids

import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import java.security.MessageDigest

/**
 * Deterministic, bank-scoped FinancialContainer ids.
 *
 * Account and card namespaces are separate so account 7271 and card 7271 never collide.
 *
 * [Bank.UNKNOWN] is not a durable financial-container identity: direct factory
 * methods reject it; reference helpers return null.
 */
object FinancialContainerIdFactory {
    fun accountId(bank: Bank, maskedNumber: String): String {
        require(bank != Bank.UNKNOWN) {
            "Bank.UNKNOWN is not a durable financial-container identity"
        }
        return "account:${bank.id}:${maskedNumber.trim()}"
    }

    fun accountId(reference: AccountReference): String? {
        if (reference.bank == Bank.UNKNOWN) return null
        val masked = reference.maskedNumber?.trim().orEmpty()
        if (masked.isEmpty()) return null
        return accountId(reference.bank, masked)
    }

    fun cardId(bank: Bank, last4: String): String {
        require(bank != Bank.UNKNOWN) {
            "Bank.UNKNOWN is not a durable financial-container identity"
        }
        return "card:${bank.id}:${last4.trim()}"
    }

    fun cardId(reference: CardReference): String? {
        if (reference.bank == Bank.UNKNOWN) return null
        val last4 = reference.last4?.trim().orEmpty()
        if (last4.isEmpty()) return null
        return cardId(reference.bank, last4)
    }

    fun loanId(bank: Bank, loanType: com.baraa.masroof.domain.model.LoanType): String {
        require(bank != Bank.UNKNOWN) {
            "Bank.UNKNOWN is not a durable financial-container identity"
        }
        return "loan:${bank.id}:${loanType.name}"
    }

    fun loanId(reference: com.baraa.masroof.domain.model.LoanReference): String? {
        if (reference.bank == Bank.UNKNOWN) return null
        return loanId(reference.bank, reference.loanType)
    }
}

/**
 * Deterministic FinancialTransaction ids derived from sorted RawSms evidence ids.
 */
object TransactionIdFactory {
    fun fromRawSmsIds(rawSmsIds: Collection<String>): String {
        require(rawSmsIds.isNotEmpty()) { "rawSmsIds must not be empty" }
        val canonical = rawSmsIds.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
            .joinToString(separator = "\u001e")
        require(canonical.isNotEmpty()) { "rawSmsIds must contain at least one non-blank id" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        val hex = digest.toHexString()
        return "transaction:$hex"
    }
}
