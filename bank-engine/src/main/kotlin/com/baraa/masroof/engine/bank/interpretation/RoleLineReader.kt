package com.baraa.masroof.engine.bank.interpretation

import com.baraa.masroof.engine.bank.Evidence

internal data class RoleLine(val normalized: String, val original: String, val range: IntRange)

/** Shared literal role grammar for details and bank identities, with original source ranges. */
internal class RoleLineReader(
    private val source: SourceText,
    private val bindings: Map<String, List<String>>,
    private val evidence: MutableList<Evidence>,
    knownLabels: List<String> = emptyList(),
    /** Only rules that declare `label.suffix` read `value : label` lines (e.g. EmiratesNBD). */
    private val suffixLabels: Boolean = false,
) {
    private val prefixes = (bindings.values.flatten() + knownLabels).distinct().map {
        SourceText(it).text.length to CompiledPatterns.of("^ *" + source.phrase(it).pattern.removeSuffix("(?![\\p{L}\\p{N}_])") + "(?=[ \\t:0-9]|$)")
    }

    fun read(field: String): List<RoleLine> = bindings[field].orEmpty().distinct().flatMap { label ->
        val pattern = CompiledPatterns.of("(?m)^ *" + source.role(label).pattern + "(?<tail>[^\\n\\r]*)$")
        val prefix = pattern.findAll(source.text).mapNotNull { match ->
            val longest = prefixes.filter { it.second.containsMatchIn(match.value) }.maxOfOrNull { it.first } ?: 0
            if (longest > SourceText(label).text.length) return@mapNotNull null
            evidence += source.evidence("extract.role", field, match.range)
            val tail = match.groups["tail"]!!
            var first = tail.value.indexOfFirst { !it.isWhitespace() }
            val last = tail.value.indexOfLast { !it.isWhitespace() }
            if (first < 0 || !(tail.value.first().isWhitespace() || tail.value.first() == ':' || tail.value.first().isDigit())) RoleLine("", "", match.range)
            else {
                if (tail.value[first] == ':') first++
                while (first <= last && tail.value[first].isWhitespace()) first++
                if (first > last) RoleLine("", "", match.range)
                else {
                    val range = (tail.range.first + first)..(tail.range.first + last)
                    RoleLine(source.text.substring(range), source.original(range), match.range)
                }
            }
        }.toList()
        val suffix = if (!suffixLabels) emptyList() else CompiledPatterns.of("(?m)^ *(?<value>[^\\n\\r:]+?) *: *" + source.phrase(label).pattern + " *$")
            .findAll(source.text).map { match ->
                val value = match.groups["value"]!!
                evidence += source.evidence("extract.role", field, match.range)
                RoleLine(value.value.trim(), source.original(value.range).trim(), match.range)
            }.toList()
        prefix + suffix
    }
}
