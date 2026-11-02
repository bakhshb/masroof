package com.baraa.masroof.engine.bank.interpretation

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Explicit source wall clocks only, never receipt time or guessed year/timezone. */
internal object DateGrammar {
    private val dates = listOf("uuuu-MM-dd", "dd/MM/uuuu", "dd-MM-uuuu").map {
        DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT)
    }
    private const val CLOCK = "[0-9]{2}:[0-9]{2}(?::[0-9]{2})?"
    private const val DAY_FIRST = "[0-9]{2}[-/][0-9]{2}[-/][0-9]{4}"
    val timestamp = CompiledPatterns.of("(?<![0-9])(?:[0-9]{4}-[0-9]{2}-[0-9]{2}[t ]$CLOCK|$DAY_FIRST +$CLOCK|$CLOCK +$DAY_FIRST)(?![0-9:])")
    fun date(value: String): LocalDate? = dates.firstNotNullOfOrNull { runCatching { LocalDate.parse(value, it) }.getOrNull() }
    fun time(value: String): LocalDateTime? = runCatching {
        val parts = value.trim().split(CompiledPatterns.of(" +"))
        when {
            value[2] == ':' -> LocalDateTime.of(requireNotNull(date(parts[1])), java.time.LocalTime.parse(parts[0]))
            value[2] in "-/" -> LocalDateTime.of(requireNotNull(date(parts[0])), java.time.LocalTime.parse(parts[1]))
            else -> LocalDateTime.parse(value.replace(' ', 'T').replace('t', 'T'))
        }
    }.getOrNull()
}
