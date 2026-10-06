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

    /**
     * Saves [transaction] after removing exclusive stale rows that block the same
     * [rawSmsIds]. Production Room implementation is atomic; the default falls back
     * to delete-then-save for in-memory test doubles.
     */
    suspend fun replaceExclusiveStaleLinks(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
        staleRawSmsIds: Collection<String>,
    ): FinancialTransactionSaveResult {
        val ids = rawSmsIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        require(ids.isNotEmpty()) { "rawSmsIds required" }
        when (val first = save(transaction, ids)) {
            FinancialTransactionSaveResult.Saved,
            FinancialTransactionSaveResult.AlreadyExists,
            -> return first
            is FinancialTransactionSaveResult.Conflict -> Unit
        }
        for (rawSmsId in staleRawSmsIds.distinct()) {
            if (!deleteIfExclusiveRawSmsLink(rawSmsId)) {
                return FinancialTransactionSaveResult.Conflict(
                    rawSmsId = rawSmsId,
                    existingTransactionId = findByRawSmsId(rawSmsId)?.id ?: "stale_delete_failed",
                )
            }
        }
        return save(transaction, ids)
    }

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

    suspend fun listRawSmsIds(transactionId: String): List<String>

    /** RawSms evidence ids linked to any of [transactionIds], in one batch lookup. */
    suspend fun listRawSmsIdsForTransactions(transactionIds: Collection<String>): Set<String> =
        transactionIds.distinct().flatMapTo(linkedSetOf()) { listRawSmsIds(it) }

    suspend fun update(transaction: FinancialTransaction): Boolean

    suspend fun updateAppliedExchangeRate(
        id: String,
        exchangeRate: java.math.BigDecimal,
        source: com.baraa.masroof.domain.model.ExchangeRateSource,
    ): Boolean

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
     * Attaches an additional RawSms evidence row to an existing transaction when the
     * same transfer was assembled twice from separate bank messages.
     */
    suspend fun linkRawSmsIfAbsent(transactionId: String, rawSmsId: String): Boolean
}

sealed interface FinancialTransactionSaveResult {
    data object Saved : FinancialTransactionSaveResult

    data object AlreadyExists : FinancialTransactionSaveResult

    data class Conflict(
        val rawSmsId: String,
        val existingTransactionId: String,
    ) : FinancialTransactionSaveResult
}
