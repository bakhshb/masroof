package com.baraa.masroof.bank.aljazira.extraction

import com.baraa.masroof.parsing.normalizer.comparisonRegex
import com.baraa.masroof.parsing.model.NormalizedSms

/**
 * Transfer party name — not merchant, not biller.
 */
class CounterpartyExtractor {
    fun extract(sms: NormalizedSms): String? {
        val comparison = sms.comparisonBody
        for ((pattern, group) in PATTERNS) {
            val match = pattern.find(comparison) ?: continue
            val range = match.groups[group]?.range ?: continue
            val value = sms.normalizedSlice(range).trim()
                .trimStart(':').trim()
            if (value.isBlank()) continue
            if (value.all { it.isDigit() }) continue
            return value
        }
        return null
    }

    companion object {
        private val NAME =
            """([^\n\[]+?)(?=\s*(?:\n|$|مبلغ|بمبلغ|البنك|في\s*:|رقم|المعرف|\[|amount))"""

        private val PATTERNS: List<Pair<Regex, Int>> = listOf(
            comparisonRegex("""اسم\s*المرسل\s*:\s*$NAME""") to 1,
            comparisonRegex("""(?<![\p{L}])من\s*:\s*$NAME""") to 1,
            // Non-digit destination party (account last4 uses digit-only patterns).
            comparisonRegex("""الى\s*:\s*(?!\d)([^\n\[]+?)(?=\s*(?:\n|$|مبلغ|بمبلغ|البنك|في\s*:|رقم|المعرف|\[))""") to 1,
        )
    }
}
