package com.baraa.masroof.engine.bank.patterns

import com.baraa.masroof.engine.bank.interpretation.MoneyGrammar
import com.baraa.masroof.engine.bank.interpretation.SourceText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private const val DECIMAL_PATTERN = MoneyGrammar.NUMBER

/** Primitive shapes for raw text spans. No ownership, transaction time or financial authority. */
enum class FieldKind(internal val dateFormat: String? = null, private val dateOnly: Boolean = false) {
    MONEY, DECIMAL, TEXT, DIGITS,
    DATE_DMY_SHORT("dd/MM/uu HH:mm"), DATE_DMY_LONG("dd-MM-uuuu HH:mm"), DATE_YMD("uuuu-MM-dd HH:mm[:ss]"),
    TIME_DMY_LONG("HH:mm[:ss] dd-MM-uuuu"), TIME_YMD("HH:mm[:ss] uuuu-MM-dd"),
    DATE_DMY_ONLY("dd/MM/uuuu", true), DATE_YMD_ONLY("uuuu-MM-dd", true);

    fun pattern(example: String): String = when (this) {
        MONEY -> "(?:$DECIMAL_PATTERN *${MoneyGrammar.CURRENCY}|${MoneyGrammar.CURRENCY} *$DECIMAL_PATTERN)"
        DECIMAL -> DECIMAL_PATTERN
        TEXT -> "[^\\n]{1,160}"
        DIGITS -> "[0-9]{${example.length}}"
        DATE_DMY_SHORT -> "[0-9]{2}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}"
        DATE_DMY_LONG -> "[0-9]{2}-[0-9]{2}-[0-9]{4} [0-9]{2}:[0-9]{2}"
        DATE_YMD -> "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}(?::[0-9]{2})?"
        TIME_DMY_LONG -> "[0-9]{2}:[0-9]{2}(?::[0-9]{2})? [0-9]{2}-[0-9]{2}-[0-9]{4}"
        TIME_YMD -> "[0-9]{2}:[0-9]{2}(?::[0-9]{2})? [0-9]{4}-[0-9]{2}-[0-9]{2}"
        DATE_DMY_ONLY -> "[0-9]{2}/[0-9]{2}/[0-9]{4}"
        DATE_YMD_ONLY -> "[0-9]{4}-[0-9]{2}-[0-9]{2}"
    }

    fun accepts(value: String): Boolean = when {
        this == DECIMAL -> runCatching { value.replace(",", "").toBigDecimal().signum() >= 0 }.getOrDefault(false)
        this == MONEY -> runCatching {
            Regex("[0-9][0-9.,]*").find(value)!!.value.replace(",", "").toBigDecimal().signum() > 0
        }.getOrDefault(false)
        dateFormat != null -> runCatching {
            // Explicit preview convention: reduced years use 2000–2099. Never produces a transaction time/zone.
            val formatter = DateTimeFormatter.ofPattern(dateFormat).withResolverStyle(ResolverStyle.STRICT)
            if (dateOnly) LocalDate.parse(value, formatter) else LocalDateTime.parse(value, formatter)
        }.isSuccess
        else -> value.isNotBlank()
    }
}
internal data class Selection(val field: String, val kind: FieldKind, val start: Int, val end: Int, val allowEmpty: Boolean = false)
data class Preview(val label: String, val fields: Map<String, TextSpan>)

internal class ExampleTemplate private constructor(
    private val sender: String,
    private val label: String,
    private val selections: List<Selection>,
    private val pattern: Regex,
) {
    fun preview(sender: String, body: String): Preview? {
        if (sender != this.sender || body.length !in 1..4096) return null
        val source = SourceText(body)
        val match = pattern.matchEntire(source.text) ?: return null
        val fields = selections.mapIndexedNotNull { index, selection ->
            val group = requireNotNull(match.groups[index + 1])
            if (selection.allowEmpty && group.value.isBlank()) return@mapIndexedNotNull null
            if (group.value.length > 160 || !selection.kind.accepts(group.value)) return null
            val evidence = source.evidence("example-preview", selection.field, group.range)
            selection.field to TextSpan(source.original(group.range), evidence.start, evidence.end)
        }.toMap()
        return Preview(label, fields)
    }

    companion object {
        fun learn(sender: String, label: String, body: String, selections: List<Selection>): ExampleTemplate {
            require(sender.isNotBlank() && label.isNotBlank() && body.length in 1..4096)
            require(selections.size in 1..16 && selections.map { it.field }.distinct().size == selections.size)
            val ordered = selections.sortedBy { it.start }.toList()
            ordered.forEach { require(it.start in 0 until it.end && it.end <= body.length) }
            ordered.zipWithNext().forEach { (left, right) ->
                require(left.end <= right.start)
                val gap = body.substring(left.end, right.start)
                require('\n' in gap || gap.isNotBlank()) { "Fields need literal separating evidence" }
            }
            // One free-text slot per line keeps adjacent text splits conservative; numeric/time slots may share a line.
            require(ordered.filter { it.kind == FieldKind.TEXT }.groupBy { selection ->
                body.substring(0, selection.start).count { it == '\n' }
            }.values.all { it.size <= 1 }) { "Multiple text fields on one line need more evidence" }
            require(body.substring(0, ordered.first().start).any(Char::isLetter)) { "A literal anchor must precede fields" }
            val source = SourceText(body)
            var cursor = 0
            val expression = buildString {
                append("\\A[ \\n]*")
                ordered.forEach { selection ->
                    require(!selection.allowEmpty || selection.kind == FieldKind.TEXT)
                    require(selection.field.matches(Regex("[a-z][a-z0-9_]{0,47}")))
                    require(listOf(selection.start, selection.end).none { offset ->
                        offset in 1 until body.length && body[offset].isLowSurrogate() && body[offset - 1].isHighSurrogate()
                    }) { "Selection splits a Unicode character" }
                    val start = SourceText(body.substring(0, selection.start)).text.length
                    val end = SourceText(body.substring(0, selection.end)).text.length
                    require(start >= cursor && end > start) { "Selections overlap or normalize to empty" }
                    val value = source.text.substring(start, end)
                    require('\n' !in value && value.length <= 160)
                    val fieldPattern = selection.kind.pattern(value)
                    require(Regex(fieldPattern).matches(value) && selection.kind.accepts(value)) { "Invalid teaching field" }
                    val preceding = source.text.substring(cursor, start)
                    // Keep the label/newline anchors when an optional value and its preceding spaces are absent.
                    append(literal(if (selection.allowEmpty) preceding.trimEnd(' ') else preceding))
                    if (selection.allowEmpty && preceding.endsWith(' ')) append(" *")
                    append('(').append(if (selection.allowEmpty) "[^\\n]{0,160}" else fieldPattern).append(')')
                    cursor = end
                }
                append(literal(source.text.substring(cursor)))
                append("[ \\n]*\\z")
            }
            return ExampleTemplate(sender, label, ordered, Regex(expression))
        }

        private fun literal(value: String): String = buildString {
            var index = 0
            while (index < value.length) {
                when (value[index]) {
                    ':' -> {
                        append(": *")
                        index++
                        while (index < value.length && value[index] == ' ') index++
                    }
                    ' ', '\n' -> {
                        val char = value[index]
                        append(if (char == ' ') " +" else "\\n+")
                        while (index < value.length && value[index] == char) index++
                    }
                    else -> {
                        val start = index++
                        while (index < value.length && value[index] !in " :\n") index++
                        append(Regex.escape(value.substring(start, index)))
                    }
                }
            }
        }
    }
}
