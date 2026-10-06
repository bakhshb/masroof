package com.baraa.masroof.core.text

/**
 * Comparison-only Arabic typography equivalence.
 *
 * Folds harmless variation so keyword matching survives it:
 * - alef variants (أ إ آ ٱ) → ا, alef maqsura (ى) → ي
 * - tatweel, Arabic diacritics, and bidi / zero-width format marks are dropped
 * - colon variants → `:`, Arabic decimal / thousands separators → `.` / `,`
 *
 * Never use folded text for display: it is a matching view only. Keywords and
 * regex patterns matched against folded text must be folded with [foldArabic] too.
 */
object ArabicTextFolding {
    /** Arabic-only folding (no case change); safe for regex pattern strings. */
    fun foldArabic(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            if (isIgnorable(c)) continue
            out.append(foldChar(c))
        }
        return out.toString()
    }

    /**
     * Full comparison form: [foldArabic] plus per-character lowercase.
     *
     * Per-character lowercasing keeps a one-to-one mapping between kept characters,
     * so [comparisonIndexMap] can map comparison offsets back to [text].
     */
    fun foldForComparison(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            if (isIgnorable(c)) continue
            out.append(foldChar(c).lowercaseChar())
        }
        return out.toString()
    }

    /** For each index of [foldForComparison] output, the index of its source char in [text]. */
    fun comparisonIndexMap(text: String): IntArray {
        val map = IntArray(text.length)
        var size = 0
        for ((index, c) in text.withIndex()) {
            if (isIgnorable(c)) continue
            map[size++] = index
        }
        return map.copyOf(size)
    }

    fun isIgnorable(c: Char): Boolean =
        c == TATWEEL ||
            c in ARABIC_DIACRITICS ||
            c == SUPERSCRIPT_ALEF ||
            c in QURANIC_MARKS ||
            c in FORMAT_MARKS

    private fun foldChar(c: Char): Char =
        when (c) {
            'أ', 'إ', 'آ', 'ٱ' -> 'ا'
            'ى' -> 'ي'
            '\uFF1A', '\uFE55', '\uFE13', '\u2236' -> ':'
            '\u066B' -> '.'
            '\u066C' -> ','
            else -> c
        }

    private const val TATWEEL = '\u0640'
    private const val SUPERSCRIPT_ALEF = '\u0670'
    private val ARABIC_DIACRITICS = '\u064B'..'\u065F'
    private val QURANIC_MARKS = '\u06D6'..'\u06ED'
    private val FORMAT_MARKS = setOf(
        '\u061C', // Arabic letter mark
        '\u200B', '\u200C', '\u200D', '\u200E', '\u200F',
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
        '\uFEFF',
    )
}
