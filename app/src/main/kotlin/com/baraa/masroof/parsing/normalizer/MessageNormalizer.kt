package com.baraa.masroof.parsing.normalizer

import com.baraa.masroof.core.text.ArabicTextFolding
import com.baraa.masroof.parsing.model.NormalizedSms
import java.text.Normalizer

/**
 * Bank-agnostic SMS text normalizer.
 *
 * Produces deterministic [NormalizedSms] without classifying families, extracting
 * fields, or applying bank-specific label rewrites. Aggressive Arabic equivalence
 * ([ArabicTextFolding]) applies to `comparisonBody` only; `normalizedBody` keeps
 * the letters the bank sent so extracted names are not degraded.
 */
class MessageNormalizer {
    fun normalize(originalBody: String): NormalizedSms {
        val unicodeNormalized = Normalizer.normalize(originalBody, Normalizer.Form.NFC)
        val withLatinDigits = mapArabicIndicDigits(unicodeNormalized)
        val withLineEndings = withLatinDigits
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        // Fullwidth colon appears in some Latin/CJK SMS variants; fixture-safe.
        val withColonVariants = withLineEndings.replace('\uFF1A', ':')
        val normalizedLines = withColonVariants
            .lineSequence()
            .map { line -> collapseInternalSpaces(line.trim()) }
            .toList()
        val normalizedBody = normalizedLines.joinToString("\n")
        val comparisonBody = ArabicTextFolding.foldForComparison(normalizedBody)
        return NormalizedSms(
            originalBody = originalBody,
            normalizedBody = normalizedBody,
            comparisonBody = comparisonBody,
        )
    }

    private fun collapseInternalSpaces(line: String): String =
        line.replace(Regex("[ \\t\\u00A0]+"), " ")

    private fun mapArabicIndicDigits(text: String): String {
        val chars = text.toCharArray()
        for (i in chars.indices) {
            when (val c = chars[i]) {
                in '\u0660'..'\u0669' -> chars[i] = '0' + (c - '\u0660') // Arabic-Indic
                in '\u06F0'..'\u06F9' -> chars[i] = '0' + (c - '\u06F0') // Extended Arabic-Indic
            }
        }
        return String(chars)
    }
}
