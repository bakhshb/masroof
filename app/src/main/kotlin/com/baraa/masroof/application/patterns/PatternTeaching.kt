package com.baraa.masroof.application.patterns

import com.baraa.masroof.engine.bank.patterns.FieldChoice
import com.baraa.masroof.engine.bank.patterns.SavedMessagePattern
import com.baraa.masroof.engine.bank.patterns.TextSpan

/** Teaching roles are explicit user choices, not posting authority or ownership decisions. */
enum class PatternOperation(val fields: List<String>) {
    PURCHASE(listOf("amount", "merchant", "card", "occurred_at", "available_balance")),
    TRANSFER_IN(listOf("amount", "counterparty", "account_reference", "funding_reference", "source_account", "destination_account", "counterparty_bank", "occurred_at", "reference")),
    TRANSFER_OUT(listOf("amount", "counterparty", "account_reference", "source_account", "destination_account", "counterparty_bank", "occurred_at", "reference")),
    BILL_PAYMENT(listOf("amount", "biller", "source_account", "occurred_at", "reference")),
    CARD_PAYMENT(listOf("amount", "card", "source_account", "occurred_at")),
    REFUND(listOf("amount", "merchant", "card", "occurred_at")),
    WITHDRAWAL(listOf("amount", "source_account", "card", "occurred_at")),
    FEE(listOf("amount", "source_account", "occurred_at", "reference")),
    FINANCING_INSTALLMENT(listOf("amount", "source_account", "counterparty", "occurred_at", "reference")),
}

/** In-memory source only: never saved in navigation state or written back to RawSms. */
data class PatternTeachingSource(val bankId: String?, val sender: String, val body: String, val reviewId: String? = null)

data class PatternBinding(val field: String, val start: Int, val end: Int, val ignored: Boolean = false)
/** A value needs fixed words before it: the opening words say which message this is, so teaching rejects a value there. */
fun opensMessage(body: String, start: Int) = body.take(start).none(Char::isLetter)
/** [kind] is a lexical hint name from candidate discovery (MONEY, DATE_TIME…); null for extracted fields. */
data class PatternText(val text: String, val start: Int, val end: Int, val kind: String? = null)
data class PatternMatch(val patternId: String, val revision: Int, val operation: String, val fields: Map<String, PatternText>)

/** Immutable preview token retains the exact compiled definition reviewed before save. */
class PatternTeachingPreview internal constructor(
    val generation: Long,
    internal val pattern: SavedMessagePattern,
    val fields: Map<String, PatternText>,
    /** Variable-looking values the template could not ignore automatically; they stay literal text. */
    val fixedValues: List<PatternText> = emptyList(),
    /** The teaching message, kept in memory so an edit can re-run the prior approval on it. */
    internal val body: String = "",
) {
    val bankId get() = pattern.bankId
    val operation get() = pattern.label
    val patternId get() = pattern.id
}

internal fun Map<String, TextSpan>.displayFields() = mapValues { (_, span) -> PatternText(span.raw, span.start, span.end) }
internal fun List<PatternBinding>.choices() = map { FieldChoice(it.field, it.start, it.end, it.ignored) }
