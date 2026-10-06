package com.baraa.masroof.bank.aljazira.extraction

import com.baraa.masroof.parsing.model.NormalizedSms
import com.baraa.masroof.parsing.normalizer.comparisonRegex
import java.util.Locale

/**
 * Extracts the financing product label from installment SMS (لـ: تمويل شخصي).
 */
class LoanLabelExtractor {
    fun extract(sms: NormalizedSms): String? {
        val match = LOAN_LABEL.find(sms.comparisonBody) ?: return null
        val range = match.groups[1]?.range ?: return null
        return sms.normalizedSlice(range).lowercase(Locale.ROOT).trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private val LOAN_LABEL =
            comparisonRegex("""(?:^|\n)\s*لـ\s*:\s*(.+?)(?:\n|$)""", RegexOption.MULTILINE)
    }
}
