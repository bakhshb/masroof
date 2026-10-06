package com.baraa.masroof.parsing.model

import com.baraa.masroof.core.text.ArabicTextFolding

/**
 * Dual representation of an SMS body after generic normalization.
 *
 * [originalBody] is preserved unchanged for traceability.
 * [normalizedBody] is the working text for display-safe extraction.
 * [comparisonBody] is `ArabicTextFolding.foldForComparison(normalizedBody)`: lowercase,
 * alef/yeh-folded, without tatweel/diacritics/format marks. Match on it; never display it.
 * Use [normalizedSlice] to turn a comparison match range into display text.
 */
data class NormalizedSms(
    val originalBody: String,
    val normalizedBody: String,
    val comparisonBody: String,
) {
    private val comparisonToNormalized: IntArray by lazy {
        ArabicTextFolding.comparisonIndexMap(normalizedBody)
    }

    /** [normalizedBody] text covered by a [comparisonBody] index range (inclusive). */
    fun normalizedSlice(comparisonRange: IntRange): String {
        val map = comparisonToNormalized
        if (map.size != comparisonBody.length) {
            return normalizedBody.substring(comparisonRange.first, comparisonRange.last + 1)
        }
        return normalizedBody.substring(map[comparisonRange.first], map[comparisonRange.last] + 1)
    }
}
