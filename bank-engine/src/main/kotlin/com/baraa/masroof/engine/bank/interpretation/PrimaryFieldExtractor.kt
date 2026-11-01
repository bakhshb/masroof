package com.baraa.masroof.engine.bank.interpretation

import com.baraa.masroof.engine.bank.*
import com.baraa.masroof.finance.value.Money
import java.time.LocalDateTime

/** Primary role-bound fields and their source evidence; family and execution checks stay in the interpreter. */
internal class PrimaryFieldExtractor(
    private val source: SourceText,
    private val labels: (ExtractionField) -> List<String>,
    private val currency: String?,
    private val secondaryRanges: List<IntRange>,
    private val evidence: MutableList<Evidence>,
    private val problem: (String, Blocker) -> Unit,
    /** Rules declaring `label.suffix` also read `value : label` lines. */
    private val suffixLabels: Boolean = false,
    /** Amount roles also read inside a line (`تم شراء بقيمة100ريال`); a currency is always required. */
    private val inlineAmountRoles: List<String> = emptyList(),
) {
    private data class Hit<T>(val value: T, val range: IntRange)
    private fun overlaps(range: IntRange) = secondaryRanges.any { range.first <= it.last && it.first <= range.last }

    /**
     * [standalone] accepts a bare `100 SAR` line; [titlePrefixes] accept `title بمبلغ 100 SAR` on the first line.
     * Both are opt-in per rule (`amount.standalone` / `amount.title`).
     */
    fun money(field: ExtractionField, standalone: Boolean = false, titlePrefixes: List<String> = emptyList()): Money? {
        val hits = labels(field).flatMap { label ->
            val pattern = CompiledPatterns.of("(?m)^ *" + source.role(label).pattern + "[ \\t]*:?[ \\t]*" + MoneyGrammar.VALUE)
            val reverse = if (suffixLabels) CompiledPatterns.of("(?m)^ *" + MoneyGrammar.VALUE + "[ \\t]*:[ \\t]*" + source.phrase(label).pattern + " *$")
                .findAll(source.text).toList() else emptyList()
            val matches = pattern.findAll(source.text).toList() + reverse
            val separate = if (field == ExtractionField.AMOUNT && currency != null) {
                CompiledPatterns.of("(?m)^ *" + source.role(label).pattern + "[ \\t]*:?[ \\t]*([^\\n\\r]+)$")
                    .findAll(source.text).mapNotNull { row ->
                        MoneyGrammar.withCurrency(row.groupValues[1].trim(), currency)?.let { Hit(it, row.range) }
                    }.filterNot { overlaps(it.range) }.toList()
            } else emptyList()
            CompiledPatterns.of("(?m)^ *" + source.role(label).pattern).findAll(source.text).forEach { mention ->
                val longerRole = labels(field).any { other -> SourceText(other).text.length > SourceText(label).text.length &&
                    CompiledPatterns.of("(?m)^ *" + source.phrase(other).pattern).find(source.text, mention.range.first)?.range?.first == mention.range.first }
                if (!longerRole && (field != ExtractionField.AMOUNT || !overlaps(mention.range)) && matches.none { it.range.first == mention.range.first } && separate.none { it.range.first == mention.range.first })
                    problem("MALFORMED_${field.name}", Blocker.VALIDATION_ERROR)
            }
            separate + matches.mapNotNull { match ->
                if (field == ExtractionField.AMOUNT && overlaps(match.range)) return@mapNotNull null
                val value = MoneyGrammar.read(match)
                if (value == null) { problem("INVALID_MONEY", Blocker.VALIDATION_ERROR); null }
                else Hit(value, match.range)
            }
        } + (if (standalone && field == ExtractionField.AMOUNT) CompiledPatterns.of("(?m)^ *" + MoneyGrammar.VALUE + " *$")
            .findAll(source.text).filterNot { overlaps(it.range) }
            .mapNotNull { match -> MoneyGrammar.read(match)?.let { Hit(it, match.range) } }.toList() else emptyList()) +
            titlePrefixes.flatMap { prefix ->
                CompiledPatterns.of("^ *" + source.phrase(prefix).pattern + " +(?:ب *|بمبلغ *)?" + MoneyGrammar.VALUE)
                    .findAll(source.text).mapNotNull { match -> MoneyGrammar.read(match)?.let { Hit(it, match.range) } }.toList()
            }
        val inline = if (field != ExtractionField.AMOUNT || inlineAmountRoles.isEmpty()) emptyList() else
            CompiledPatterns.of("(?<![\\p{L}\\p{N}_])و?(?:" + inlineAmountRoles.joinToString("|") { Regex.escape(SourceText(it).text) } + ")[ \\t]*" + MoneyGrammar.VALUE)
                .findAll(source.text).filterNot { match -> overlaps(match.range) || hits.any { it.range.last >= match.range.first && match.range.last >= it.range.first } }
                .mapNotNull { match -> MoneyGrammar.read(match)?.let { Hit(it, match.range) } }.toList()
        val allHits = hits + inline
        allHits.forEach { evidence += source.evidence("extract.money", field.name, it.range) }
        if (field == ExtractionField.AMOUNT && currency != null && allHits.any { it.value.currency != MoneyGrammar.withCurrency("1", currency)?.currency })
            problem("CONFLICTING_PRINCIPAL_CURRENCY", Blocker.CONFLICTING_FACTS)
        if (allHits.map { it.value }.distinct().size > 1) { problem("CONFLICTING_${field.name}", Blocker.CONFLICTING_FACTS); return null }
        return allHits.firstOrNull()?.value
    }

    fun identifier(field: ExtractionField): String? {
        val hits = labels(field).flatMap { label ->
            // Masked suffixes only (XX1234, **1234, 1234*, (1234)); a full IBAN or account number is never shortened.
            // A mask that continues after the digits (4567****1234) shows a prefix, not the last four, so it is no match.
            CompiledPatterns.of("(?m)^ *" + source.role(label).pattern + "[ \\t]*:?[ \\t]*\\(?((?:[a-z]{2})?[*x]*[0-9]{4})[*x]*\\)?(?![\\p{L}\\p{N}_*])")
                .findAll(source.text).map { Hit(it.groupValues[1].takeLast(4), it.groups[1]!!.range) }.toList()
        }.filterNot { hit ->
            // A compact amount role (بـ25) must not double as an identifier.
            val span = source.evidence("extract.identifier", field.name, hit.range)
            evidence.any { it.field == ExtractionField.AMOUNT.name && span.start < it.end && it.start < span.end }
        }
        hits.forEach { evidence += source.evidence("extract.identifier", field.name, it.range) }
        if (hits.map { it.value }.distinct().size > 1) { problem("CONFLICTING_${field.name}", Blocker.CONFLICTING_FACTS); return null }
        return hits.firstOrNull()?.value
    }

    fun card(): String? {
        val labeledCard = identifier(ExtractionField.CARD)
        val pattern = CompiledPatterns.of("(?m)^ *(?:بطاقة|card) *: *(?:credit|debit|ائتمانية|مدى)(?: +| *\\n+ *)(?:رقم|number) *: *([*x]*[0-9]{4})(?![\\p{L}\\p{N}_*])")
        val hits = pattern.findAll(source.text).toList()
        val values = (listOfNotNull(labeledCard) + hits.map { it.groupValues[1].takeLast(4) }).distinct()
        if (values.size > 1) problem("CONFLICTING_CARD", Blocker.CONFLICTING_FACTS)
        hits.forEach { evidence += source.evidence("extract.card.number-role", "CARD", it.groups[1]!!.range) }
        return values.singleOrNull()
    }

    fun merchant(): String? {
        val merchants = labels(ExtractionField.MERCHANT).flatMap { label ->
            CompiledPatterns.of("(?m)^ *" + source.phrase(label).pattern + "(?:[ \\t]*:[ \\t]*|[ \\t]+)([^\\n\\r]+)").findAll(source.text).map { match ->
                val range = match.groups[1]!!.range
                // A masked card/account suffix is a reference, not a merchant name.
                if (source.text.substring(range).trim().matches(CompiledPatterns.of("[*x]*[0-9]{4}[*x]*"))) return@map null
                evidence += source.evidence("extract.merchant", "MERCHANT", range)
                source.original(range).trim()
            }.filterNotNull().toList()
        }.distinct()
        if (merchants.size > 1) problem("CONFLICTING_MERCHANT", Blocker.CONFLICTING_FACTS)
        return merchants.singleOrNull()
    }

    /** [ignoreUnbound] is for informational messages, whose dates (reminders, statements) are not transaction times. */
    fun transactionTimes(ignoreUnbound: Boolean = false): List<LocalDateTime> {
        val times = DateGrammar.timestamp.findAll(source.text).mapNotNull { match ->
            val lineStart = source.text.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
            val prefix = source.text.substring(lineStart, match.range.first).trim().removeSuffix(":").trim()
            if (prefix !in TIME_LABELS) {
                if (!ignoreUnbound) problem("UNBOUND_TIME_ROLE", Blocker.VALIDATION_ERROR)
                return@mapNotNull null
            }
            evidence += source.evidence("extract.time", "occurredAtLocal", match.range)
            DateGrammar.time(match.value).also { if (it == null) problem("INVALID_TIME", Blocker.VALIDATION_ERROR) }
        }.distinct().toList()
        if (times.size > 1) problem("CONFLICTING_TIME", Blocker.CONFLICTING_FACTS)
        return times
    }

    private companion object {
        private val TIME_LABELS = setOf("", "في", "on", "date", "time", "transaction time", "تاريخ العملية", "تاريخ", "التاريخ")
    }
}
