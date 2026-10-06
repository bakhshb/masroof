package com.baraa.masroof.bank.aljazira.extraction

import com.baraa.masroof.parsing.normalizer.comparisonRegex
import com.baraa.masroof.parsing.model.NormalizedSms
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Payment due date from credit-card statement SMS (`تاريخ الاستحقاق: DD/MM/YYYY`).
 */
class DueDateExtractor {
    fun extract(sms: NormalizedSms): LocalDate? = extractFromText(sms.comparisonBody)

    fun extractFromText(text: String): LocalDate? {
        val match = DUE_DATE_PATTERN.find(text.replace('\n', ' ')) ?: return null
        return runCatching {
            LocalDate.parse(match.groupValues[1], DMY)
        }.getOrNull()
    }

    companion object {
        private val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val DUE_DATE_PATTERN = comparisonRegex(
            """تاريخ\s*الاستحقاق\s*:\s*(\d{2}/\d{2}/\d{4})""",
            RegexOption.IGNORE_CASE,
        )
    }
}
