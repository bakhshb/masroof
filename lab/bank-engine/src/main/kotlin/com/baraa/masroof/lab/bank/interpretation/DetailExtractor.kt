package com.baraa.masroof.lab.bank.interpretation

import com.baraa.masroof.lab.bank.Blocker
import com.baraa.masroof.lab.bank.Evidence
import com.baraa.masroof.lab.finance.value.Currency
import com.baraa.masroof.lab.finance.value.Money
import java.math.BigDecimal
import java.time.LocalDate

internal data class RoleDetails(
    val due: Money?, val foreign: Money?, val fee: Money?, val rate: BigDecimal?,
    val dueDate: LocalDate?, val counterparty: String?, val reference: String?,
    val biller: String?, val billerCode: String?, val reserved: List<IntRange>,
)

/** Explicit role lines only; quoted names and unlabeled money never acquire financial roles. */
internal class DetailExtractor(
    private val source: SourceText,
    private val configured: Map<String, List<String>>,
    private val evidence: MutableList<Evidence>,
    private val problem: (String, Blocker) -> Unit,
    knownLabels: List<String> = emptyList(),
    private val implicitSarFeeLabels: List<String> = emptyList(),
) {
    private val reserved = mutableListOf<IntRange>()
    private val reader = RoleLineReader(source, configured + mapOf("internationalFee" to FEE_LABELS, "exchangeRate" to RATE_LABELS), evidence, knownLabels)
    private fun entries(field: String, reserve: Boolean = false): List<RoleLine> = reader.read(field).also { rows ->
        if (reserve) reserved += rows.map { it.range }
    }

    private fun <T : Any> one(field: String, values: List<T?>): T? {
        if (values.any { it == null }) problem("INVALID_$field", Blocker.VALIDATION_ERROR)
        val known = values.filterNotNull().distinct()
        if (known.size > 1) { problem("CONFLICTING_$field", Blocker.CONFLICTING_FACTS); return null }
        return if (values.any { it == null }) null else known.singleOrNull()
    }

    private fun money(field: String): Money? {
        val sarRanges=if(field=="internationalFee") RoleLineReader(source,mapOf(field to implicitSarFeeLabels),mutableListOf()).read(field).map {it.range}.toSet() else emptySet()
        return one(field, entries(field, reserve = true).map { row ->
            MoneyGrammar.parse(row.normalized) ?: if(row.range in sarRanges) MoneyGrammar.parse("sar "+row.normalized) else null
        })
    }

    private fun text(field: String, identifier: Boolean = false): String? =
        one(field, entries(field).filter { entry ->
            val accountRole = field == "counterparty" && entry.normalized.matches(Regex("[*x]*[0-9]{4}"))
            if (accountRole) { val span = source.evidence("scope", field, entry.range); evidence.removeAll { it.field == field && it.start == span.start && it.end == span.end } }
            !accountRole
        }.map { entry ->
            when {
                entry.normalized.isBlank() || entry.original.length > 160 -> null
                identifier && !entry.normalized.matches(Regex("[a-z0-9][a-z0-9_/-]{0,63}")) -> null
                // Normalize numeric codes (including Arabic digits) without changing opaque ID casing.
                else -> if (field == "billerCode" && entry.normalized.all(Char::isDigit)) entry.normalized else entry.original
            }
        })

    fun read(): RoleDetails {
        val due = money("dueAmount")
        val foreign = money("foreignAmount")
        if (foreign?.currency == Currency.SAR) problem("FOREIGN_ROLE_HAS_LOCAL_CURRENCY", Blocker.CONFLICTING_FACTS)
        // Stable generic grammar, versioned with the engine. Not configurable executable rules.
        val fee = money("internationalFee")
        val rate = one("exchangeRate", entries("exchangeRate", reserve = true).map {
            if (!it.normalized.matches(Regex("[0-9]{1,10}(?:\\.[0-9]{1,10})?"))) null
            else it.normalized.toBigDecimalOrNull()?.takeIf { value -> value.signum() > 0 }?.stripTrailingZeros()
        })
        val date = one("paymentDueDate", entries("paymentDueDate").map {
            DateGrammar.date(it.normalized)
        })
        return RoleDetails(due, foreign, fee, rate, date, text("counterparty"), text("transactionReference", true),
            text("biller"), text("billerCode", true), reserved.toList())
    }

    companion object {
        private val FEE_LABELS = listOf("international fee", "fee amount", "رسوم العمليات الدولية", "مبلغ الرسوم", "رسوم:")
        private val RATE_LABELS = listOf("exchange rate", "سعر الصرف")
    }
}
