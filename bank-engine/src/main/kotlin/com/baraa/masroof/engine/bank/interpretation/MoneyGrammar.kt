package com.baraa.masroof.engine.bank.interpretation

import com.baraa.masroof.finance.value.Currency
import com.baraa.masroof.finance.value.Money
import java.util.Locale

/** One bounded grammar for principal and secondary money. Never rounds unsupported precision. */
internal object MoneyGrammar {
    private const val NUMBER = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?"
    private const val CURRENCY = "(?:sar|sr|usd|eur|gbp|ريال سعودي|ريال|ر\\.س\\.?)"
    // The currency may touch the number (SAR100 / 100SAR); the lookahead still rejects 100SARX.
    const val VALUE = "(?:(?<c1>$CURRENCY)[ \\t]*(?<n1>$NUMBER)|(?<n2>$NUMBER)[ \\t]*(?<c2>$CURRENCY))(?![\\p{L}\\p{N}_.,+\\-−])"
    private val entire = CompiledPatterns.of(VALUE)

    fun parse(value: String): Money? = entire.matchEntire(value)?.let(::read)

    /** Only explicitly labelled principal currency may complete a bare positive magnitude. */
    fun withCurrency(magnitude: String, currency: String): Money? =
        magnitude.takeIf { it.matches(CompiledPatterns.of(NUMBER)) }?.let { parse("${currency.lowercase(Locale.ROOT)} $it") }

    fun read(match: MatchResult): Money? {
        val number = (match.groups["n1"] ?: match.groups["n2"])?.value ?: return null
        if (number.length > 18) return null
        val currency = (match.groups["c1"] ?: match.groups["c2"])?.value ?: return null
        val code = when (currency) {
            "ريال", "ريال سعودي", "ر.س", "ر.س.", "sr" -> "SAR"
            else -> currency.uppercase(Locale.ROOT)
        }
        return runCatching { Money.of(number.replace(",", ""), Currency.valueOf(code)) }.getOrNull()
    }
}
