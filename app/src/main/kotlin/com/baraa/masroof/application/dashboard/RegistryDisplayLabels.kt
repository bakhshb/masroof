package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardRole
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.model.LoanType

object RegistryDisplayLabels {
    fun accountLabel(entry: AccountRegistryEntry): String {
        val custom = entry.displayName?.trim().orEmpty()
        if (custom.isNotEmpty()) return custom
        return "Account ••${accountLast4(entry.maskedNumber)}"
    }

    fun cardLabel(entry: CardRegistryEntry): String {
        val custom = entry.displayName?.trim().orEmpty()
        if (custom.isNotEmpty()) return custom
        return when (entry.cardRole) {
            CardRole.PRIMARY -> "Primary ••${entry.last4}"
            CardRole.SUPPLEMENTARY -> "Additional ••${entry.last4}"
            CardRole.STANDALONE, null -> when (entry.cardType) {
                CardType.DEBIT -> "Debit ••${entry.last4}"
                CardType.CREDIT -> "Credit ••${entry.last4}"
                null -> "Card ••${entry.last4}"
            }
        }
    }

    fun cardSubtitle(entry: CardRegistryEntry): String =
        buildString {
            entry.cardNetwork?.name?.let { append(it) }
            if (entry.cardRole == CardRole.SUPPLEMENTARY && entry.parentCardLast4 != null) {
                if (isNotEmpty()) append(" · ")
                append("→ ••${entry.parentCardLast4}")
            }
        }.ifEmpty { "••${entry.last4}" }

    fun loanLabel(entry: LoanRegistryEntry): String {
        val custom = entry.displayName?.trim().orEmpty()
        if (custom.isNotEmpty()) return custom
        return when (entry.loanType) {
            LoanType.PERSONAL -> "Personal loan"
            LoanType.AUTO -> "Auto loan"
            LoanType.MORTGAGE -> "Mortgage"
        }
    }

    internal fun accountLast4(maskedNumber: String): String {
        val trimmed = maskedNumber.trim()
        return if (trimmed.length <= 4) trimmed else trimmed.takeLast(4)
    }
}
