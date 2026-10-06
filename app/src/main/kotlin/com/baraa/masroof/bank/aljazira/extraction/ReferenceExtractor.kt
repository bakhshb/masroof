package com.baraa.masroof.bank.aljazira.extraction

import com.baraa.masroof.parsing.normalizer.comparisonRegex
import com.baraa.masroof.parsing.model.NormalizedSms

class ReferenceExtractor {
    fun extract(sms: NormalizedSms): String? {
        val match = PATTERN.find(sms.comparisonBody) ?: return null
        val range = match.groups[1]?.range ?: return null
        return sms.normalizedSlice(range).trim().takeIf { it.isNotBlank() }
    }

    companion object {
        private val PATTERN = comparisonRegex("""رقم\s*المعاملة\s*:\s*([^\n]+)""")
    }
}
