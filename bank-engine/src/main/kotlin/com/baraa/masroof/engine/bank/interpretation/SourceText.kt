package com.baraa.masroof.engine.bank.interpretation

import com.baraa.masroof.engine.bank.Evidence
import java.text.Normalizer
import java.util.Locale

/** Comparison text with a lossless map to the caller's original UTF-16 offsets. */
internal class SourceText(private val original: String) {
    private val starts = mutableListOf<Int>()
    private val ends = mutableListOf<Int>()
    var text: String = buildString {
        var offset = 0
        while (offset < original.length) {
            val codePoint = original.codePointAt(offset)
            val end = offset + Character.charCount(codePoint)
            val source = String(Character.toChars(codePoint))
            Normalizer.normalize(source, Normalizer.Form.NFKD).lowercase(Locale.ROOT).forEach { c ->
                val mapped = when {
                    Character.getType(c) == Character.NON_SPACING_MARK.toInt() || Character.getType(c) == Character.FORMAT.toInt() || c == '\u0640' -> null
                    c in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                    c in '\u06f0'..'\u06f9' -> '0' + (c - '\u06f0')
                    c == '\u066b' -> '.'
                    c == '\u066c' -> ','
                    c == '\u0649' -> '\u064a'
                    c == '\r' -> '\n'
                    c == '\u00a0' || c == '\t' -> ' '
                    else -> c
                }
                if (mapped != null) { append(mapped); starts += offset; ends += end }
            }
            offset = end
        }
    }
        private set

    /** Segment explicit English and Arabic purchase clauses without changing any original evidence offset. */
    fun segmentInlinePurchase(): SourceText {
        val english = CompiledPatterns.of("^ *(?:online|internet) purchase(?: apple pay)? +(?:credit card|debit card|card) *:?").containsMatchIn(text)
        val arabic = CompiledPatterns.of("^ *شراء عبر (?:نقاط البيع|الانترنت) *\\n+ *باستخدام بطاقة[^\\n]+(?:لدي|بمبلغ)").containsMatchIn(text)
        if (!english && !arabic) return this
        val terms = if (arabic) listOf("لدى", "بمبلغ", "في") else
            listOf("available balance is", "available balance", "credit card", "debit card", "due amount", "of", "at", "on")
        val pattern = CompiledPatterns.of(terms.joinToString("|") { phrase(it).pattern })
        val boundaries = pattern.findAll(text).map { it.range.first }.filter { it > 0 && text[it - 1] != '\n' }.toSet()
        val oldText = text; val oldStarts = starts.toList(); val oldEnds = ends.toList()
        starts.clear(); ends.clear()
        text = buildString { oldText.forEachIndexed { index, char ->
            if (index in boundaries) { append('\n'); starts += oldStarts[index]; ends += oldStarts[index] }
            append(char); starts += oldStarts[index]; ends += oldEnds[index]
        } }
        return this
    }

    /** Depends on [value] only, never on this message, so each label is normalized and compiled once. */
    fun phrase(value: String): Regex = PHRASES[value] ?: CompiledPatterns.of("(?<![\\p{L}\\p{N}_])" +
        SourceText(value).text.split(CompiledPatterns.of("[ \\t]+" )).joinToString("[ \\t]+") { Regex.escape(it) } + "(?![\\p{L}\\p{N}_])")
        .also { if (PHRASES.size < MAX_PHRASES) PHRASES[value] = it }

    /** A role label may touch the digits of its value (`بمبلغ120`), but never a longer word. */
    fun role(value: String): Regex = CompiledPatterns.of(phrase(value).pattern.removeSuffix("(?![\\p{L}\\p{N}_])") + "(?![\\p{L}_])")

    fun evidence(rule: String, field: String, range: IntRange): Evidence =
        Evidence(rule, field, starts[range.first], ends[range.last])

    fun original(range: IntRange): String = original.substring(starts[range.first], ends[range.last])

    private companion object {
        const val MAX_PHRASES = 4_096
        val PHRASES = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    }
}

/** Comparison key for an entire role value, shared by observations and bank aliases. */
internal fun roleValueKey(value: String): String =
    SourceText(value).text.trim().replace(CompiledPatterns.of("[ \\t]+"), " ")
