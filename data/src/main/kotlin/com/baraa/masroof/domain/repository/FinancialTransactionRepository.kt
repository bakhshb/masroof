package com.baraa.masroof.domain.repository

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import java.time.Instant

/**
 * Persistence for reconciled [FinancialTransaction]s linked by stable RawSms ids.
 */
interface FinancialTransactionRepository {
    /**
     * Atomically persist [transaction] and its source [rawSmsIds].
     *
     * Idempotent when the same deterministic transaction already owns those links.
     * Conflicts when a rawSmsId is already linked to a different transaction.
     */
    suspend fun save(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
    ): FinancialTransactionSaveResult

    suspend fun getById(id: String): FinancialTransaction?

    suspend fun findByRawSmsId(rawSmsId: String): FinancialTransaction?

    suspend fun listAll(): List<FinancialTransaction>

    /** Subset lookup for incremental reconciliation without scanning the full ledger. */
    suspend fun listByTypes(types: Collection<FinancialTransactionType>): List<FinancialTransaction> =
        if (types.isEmpty()) {
            emptyList()
        } else {
            listAll().filter { it.type in types }
        }

    /** [listByTypes] restricted to occurredAt at or after [startInclusive], same order. */
    suspend fun listByTypesOccurredSince(
        types: Collection<FinancialTransactionType>,
        startInclusive: Instant,
    ): List<FinancialTransaction> =
        listByTypes(types).filter { !it.occurredAt.isBefore(startInclusive) }

    /**
     * Transactions not in [primaryCurrency] that have no persisted applied exchange rate
     * (rate or source missing), oldest first.
     */
    suspend fun listAwaitingAppliedExchangeRate(primaryCurrency: Currency): List<FinancialTransaction> =
        listAll().filter {
            it.amount.currency != primaryCurrency &&
                (it.appliedExchangeRate == null || it.exchangeRateSource == null)
        }

    /**
     * Transactions with occurredAt in `[startInclusive, endExclusive)`, newest first.
     */
    suspend fun listOccurredBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<FinancialTransaction>

    suspend fun isRawSmsLinked(rawSmsId: String): Boolean

    /** Stored transactions among [ids], in one batch lookup. Missing ids are omitted. */
    suspend fun getByIds(ids: Collection<String>): List<FinancialTransaction> =
        ids.distinct().mapNotNull { getById(it) }

    /** The transaction linked to each of [rawSmsIds], in one batch lookup. Unlinked ids are omitted. */
    suspend fun findByRawSmsIds(rawSmsIds: Collection<String>): Map<String, FinancialTransaction> =
        rawSmsIds.distinct().mapNotNull { id -> findByRawSmsId(id)?.let { id to it } }.toMap()

    /** The subset of [rawSmsIds] that is linked to a transaction, in one batch lookup. */
    suspend fun listLinkedRawSmsIds(rawSmsIds: Collection<String>): Set<String> =
        rawSmsIds.filterTo(linkedSetOf()) { isRawSmsLinked(it) }

    suspend fun listRawSmsIds(transactionId: String): List<String>

    /** Ids of the transactions linked to any of [rawSmsIds], in one batch lookup. */
    suspend fun listTransactionIdsForRawSmsIds(rawSmsIds: Collection<String>): Set<String> =
        rawSmsIds.mapNotNull { findByRawSmsId(it)?.id }.toSortedSet()

    /** RawSms evidence ids linked to any of [transactionIds], in one batch lookup. */
    suspend fun listRawSmsIdsForTransactions(transactionIds: Collection<String>): Set<String> =
        transactionIds.distinct().flatMapTo(linkedSetOf()) { listRawSmsIds(it) }

    /** The raw SMS each of [transactionIds] links, by transaction id; a transaction without links is absent. */
    suspend fun listRawSmsIdsByTransaction(transactionIds: Collection<String>): Map<String, List<String>> =
        transactionIds.distinct().associateWith { listRawSmsIds(it) }.filterValues { it.isNotEmpty() }

    /**
     * Updates posting fields. A complete stored exchange-rate pair is left unchanged.
     * When either half is missing, [FinancialTransaction.appliedExchangeRate] and
     * [FinancialTransaction.exchangeRateSource] are written together from [transaction].
     */
    suspend fun update(transaction: FinancialTransaction): Boolean

    /**
     * Writes [exchangeRate] and [source] as one pair. Returns false when both are already
     * stored. A row with only one half is replaced entirely, so the orphan is not kept.
     */
    suspend fun updateAppliedExchangeRate(
        id: String,
        exchangeRate: java.math.BigDecimal,
        source: com.baraa.masroof.domain.model.ExchangeRateSource,
    ): Boolean

    /**
     * Replaces a complete [com.baraa.masroof.domain.model.ExchangeRateSource.HISTORICAL_MERCHANT]
     * pair after an explicit user confirmation.
     *
     * Returns false when the row is missing, either half is missing, or the stored
     * source is not historical-merchant. Enrichment must keep using
     * [updateAppliedExchangeRate], which does not call this method. The default
     * refuses the write so test doubles stay immutable until a Room implementation
     * opts in.
     */
    suspend fun replaceConfirmedHistoricalMerchantRate(
        id: String,
        exchangeRate: java.math.BigDecimal,
        source: com.baraa.masroof.domain.model.ExchangeRateSource,
    ): Boolean = false

    /**
     * Removes a transaction when [rawSmsId] is its only linked SMS evidence.
     * Used after reparse when a message is reclassified as non-financial (e.g. OTP).
     */
    suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String): Boolean

    /**
     * Drops one RawSms link from its transaction. Deletes the transaction when no
     * evidence links remain (e.g. releasing a leg wrongly merged onto another date).
     */
    suspend fun unlinkRawSms(rawSmsId: String): Boolean

    /**
     * Moves what names the removed row [oldId] to the row that replaces it: a review resolved to it whose raw SMS
     * [newIdByRawSmsId] places in another row goes to that row, every other review and the commitment go to
     * [newId]. The derived ledger writer calls it inside its write transaction (docs/ARCHITECTURE.md, "Ledger
     * writer"). The default is for test doubles without commitments or reviews.
     */
    suspend fun moveTransactionReferences(oldId: String, newId: String, newIdByRawSmsId: Map<String, String>) = Unit

    /** The ids among [transactionIds] that a commitment names as its source. */
    suspend fun listCommittedTransactionIds(transactionIds: Collection<String>): Set<String> = emptySet()
}

sealed interface FinancialTransactionSaveResult {
    data object Saved : FinancialTransactionSaveResult

    data object AlreadyExists : FinancialTransactionSaveResult

    data class Conflict(
        val rawSmsId: String,
        val existingTransactionId: String,
    ) : FinancialTransactionSaveResult
}
