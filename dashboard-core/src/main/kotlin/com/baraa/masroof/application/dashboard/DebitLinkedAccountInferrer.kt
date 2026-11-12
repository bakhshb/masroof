package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/**
 * Infers a debit (Mada) card's linked account from persisted parse facts when registry link is missing.
 */
object DebitLinkedAccountInferrer {
    fun inferAccountLast4(
        bank: Bank,
        cardLast4: String,
        parsedRecords: List<ParsedEventRecord>,
    ): String? {
        for (record in parsedRecords) {
            val event = record.event
            if (event.bank != bank) continue
            if (event.cardRef?.last4 != cardLast4) continue
            record.details.debitSourceAccountLast4?.let { return it }
            event.sourceAccountRef?.maskedNumber?.let { masked ->
                return masked.takeLast(4).takeIf { it.length == 4 }
            }
        }
        return null
    }
}
