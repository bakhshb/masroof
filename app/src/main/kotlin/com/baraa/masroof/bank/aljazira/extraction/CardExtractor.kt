package com.baraa.masroof.bank.aljazira.extraction

import com.baraa.masroof.parsing.normalizer.comparisonRegex
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.parsing.model.NormalizedSms

/**
 * Fixture-proven card last4 labels only.
 */
class CardExtractor {
    fun extract(sms: NormalizedSms, bank: Bank): CardReference? =
        extractFromText(sms.comparisonBody, bank)

    companion object {
        val PATTERNS = listOf(
            comparisonRegex("""بطاقة\s*ائتمانية\s*:\s*(\d{4})"""),
            comparisonRegex("""بطاقة\s*إئتمانية\s*:\s*(\d{4})"""),
            comparisonRegex("""بطاقة\s*مدى\s*:\s*(\d{4})"""),
            // Internal ATM withdrawal: "بطاقة 8219:مدى"
            comparisonRegex("""بطاقة\s*(\d{4})\s*:\s*مدى"""),
            comparisonRegex("""رقم\s*:\s*(\d{4})"""),
            comparisonRegex("""(?<![\p{L}])number\s*:\s*(\d{4})""", RegexOption.IGNORE_CASE),
            comparisonRegex("""بطاقة\s*:\s*(\d{4})"""),
            comparisonRegex("""credit\s*card\s*:\s*(\d{4})""", RegexOption.IGNORE_CASE),
            comparisonRegex("""(?<![\p{L}])card\s*:\s*(\d{4})""", RegexOption.IGNORE_CASE),
        )

        fun extractFromText(text: String, bank: Bank): CardReference? {
            for (pattern in PATTERNS) {
                val match = pattern.find(text) ?: continue
                val last4 = match.groupValues[1]
                return CardReference(bank = bank, last4 = last4)
            }
            return null
        }
    }
}
