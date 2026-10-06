package com.baraa.masroof.bank.aljazira.extraction

import com.baraa.masroof.parsing.normalizer.comparisonRegex
import com.baraa.masroof.parsing.model.NormalizedSms

/**
 * Commercial purchase merchant only — not counterparty or biller.
 */
class MerchantExtractor {
    fun extract(sms: NormalizedSms): String? {
        val comparison = sms.comparisonBody
        for ((pattern, group) in PATTERNS) {
            val match = pattern.find(comparison) ?: continue
            val range = match.groups[group]?.range ?: continue
            return sms.normalizedSlice(range).trim().trimStart(':').trim()
                .takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() } }
        }
        return null
    }

    companion object {
        private val VALUE = """([^\n]+?)(?=\s*(?:\n|$|بمبلغ|مبلغ|amount|of\s*:|on\s*:|date\s*:|available|due|الرصيد|إجمالي|في\s*:|خصمت))"""

        private val PATTERNS: List<Pair<Regex, Int>> = listOf(
            comparisonRegex("""لدى\s*:\s*$VALUE""", RegexOption.IGNORE_CASE) to 1,
            comparisonRegex("""من\s*:\s*$VALUE""", RegexOption.IGNORE_CASE) to 1,
            comparisonRegex("""(?<![\p{L}])from\s*:\s*$VALUE""", RegexOption.IGNORE_CASE) to 1,
            comparisonRegex("""(?<![\p{L}])at\s*:\s*$VALUE""", RegexOption.IGNORE_CASE) to 1,
            comparisonRegex("""(?<![\p{L}])at\s+$VALUE""", RegexOption.IGNORE_CASE) to 1,
        )
    }
}
