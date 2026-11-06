package com.baraa.masroof.domain.model

import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.TransactionIdFactory
import java.math.BigDecimal
import java.time.Instant

/**
 * Reconciled real-world financial result after ownership resolution and
 * related-event matching when required.
 *
 * Multiple [ParsedEvent]s may link to one transaction via [linkedParsedEventIds].
 * Container ids are bank-qualified account, card or loan identifiers.
 *
 * [categoryId] is an opaque identifier until categorization is modeled in a
 * later phase.
 *
 * There is no transaction status field yet; it is deferred rather than inventing provisional vocabulary.
 */
data class FinancialTransaction(
    val id: String,
    val type: FinancialTransactionType,
    val amount: Money,
    val occurredAt: Instant,
    val sourceContainerId: String?,
    val destinationContainerId: String?,
    val merchant: String?,
    val counterparty: String?,
    val categoryId: String?,
    val linkedParsedEventIds: List<String>,
    /** Foreign→SAR rate used for dashboard SAR totals when [amount] is foreign currency. */
    val appliedExchangeRate: BigDecimal? = null,
    val exchangeRateSource: ExchangeRateSource? = null,
    /**
     * Zone id used to interpret the SMS wall clock for [occurredAt].
     * A bank with a fixed civil zone overwrites this on the next assembly.
     * Null is a legacy row whose instant is already stored.
     */
    val occurredAtZone: String? = null,
) {
    companion object {
        /**
         * Constructs one already-authorized resolution. Callers retain eligibility,
         * ownership, type and endpoint decisions; this function never grants authority.
         */
        fun fromSingleEvent(
            event: ParsedEvent,
            type: FinancialTransactionType,
            occurredAt: Instant,
            sourceContainerId: String?,
            destinationContainerId: String?,
            occurredAtZone: String,
        ): FinancialTransaction = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf(event.rawSmsId)),
            type = type,
            amount = requireNotNull(event.amount),
            occurredAt = occurredAt,
            sourceContainerId = sourceContainerId,
            destinationContainerId = destinationContainerId,
            merchant = event.merchant,
            counterparty = event.counterparty,
            categoryId = null,
            linkedParsedEventIds = listOf(event.id),
            occurredAtZone = occurredAtZone,
        )
    }
}
