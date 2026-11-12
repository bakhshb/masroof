package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.baraa.masroof.data.room.ExchangeRatePairWrite
import com.baraa.masroof.data.room.entity.FinancialTransactionEntity
import com.baraa.masroof.data.room.entity.FinancialTransactionRawSmsLinkEntity

@Dao
interface FinancialTransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactionIfAbsent(entity: FinancialTransactionEntity): Long

    @Update
    suspend fun updateTransaction(entity: FinancialTransactionEntity): Int

    /** Preserve a complete FX pair while refreshing the remaining stored fields. */
    @Transaction
    suspend fun updatePreservingExchangeRate(entity: FinancialTransactionEntity): Int {
        val existing = getById(entity.id) ?: return 0
        val (rate, source) = ExchangeRatePairWrite.storedOrIncoming(
            storedRate = existing.appliedExchangeRate,
            storedSource = existing.exchangeRateSource,
            incomingRate = entity.appliedExchangeRate,
            incomingSource = entity.exchangeRateSource,
        )
        return updateTransaction(entity.copy(appliedExchangeRate = rate, exchangeRateSource = source))
    }

    @Query(
        """
        UPDATE financial_transaction SET
          appliedExchangeRate = :exchangeRate,
          exchangeRateSource = :source
        WHERE id = :id
          AND (appliedExchangeRate IS NULL OR exchangeRateSource IS NULL)
        """,
    )
    suspend fun updateAppliedExchangeRate(
        id: String,
        exchangeRate: String,
        source: String,
    ): Int

    /**
     * Confirmed correction only. A complete historical-merchant pair is replaced
     * as one write. Rows with any other source, or with a missing half, stay put.
     * Enrichment must keep using [updateAppliedExchangeRate].
     */
    @Query(
        """
        UPDATE financial_transaction SET
          appliedExchangeRate = :exchangeRate,
          exchangeRateSource = :source
        WHERE id = :id
          AND appliedExchangeRate IS NOT NULL
          AND exchangeRateSource = 'HISTORICAL_MERCHANT'
        """,
    )
    suspend fun replaceConfirmedHistoricalMerchantRate(
        id: String,
        exchangeRate: String,
        source: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinkIfAbsent(entity: FinancialTransactionRawSmsLinkEntity): Long

    @Query("SELECT * FROM financial_transaction WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FinancialTransactionEntity?

    @Query("SELECT * FROM financial_transaction ORDER BY occurredAtEpochMillis, id")
    suspend fun listAll(): List<FinancialTransactionEntity>

    @Query(
        """
        SELECT * FROM financial_transaction
        WHERE type IN (:types)
        ORDER BY occurredAtEpochMillis, id
        """,
    )
    suspend fun listByTypes(types: List<String>): List<FinancialTransactionEntity>

    @Query(
        """
        SELECT * FROM financial_transaction
        WHERE type IN (:types)
          AND occurredAtEpochMillis >= :startInclusiveEpochMillis
        ORDER BY occurredAtEpochMillis, id
        """,
    )
    suspend fun listByTypesOccurredSince(
        types: List<String>,
        startInclusiveEpochMillis: Long,
    ): List<FinancialTransactionEntity>

    @Query(
        """
        SELECT * FROM financial_transaction
        WHERE type IN (:types)
          AND occurredAtEpochMillis >= :startInclusiveEpochMillis
          AND occurredAtEpochMillis < :endExclusiveEpochMillis
        ORDER BY occurredAtEpochMillis, id
        """,
    )
    suspend fun listByTypesOccurredBetween(
        types: List<String>,
        startInclusiveEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<FinancialTransactionEntity>

    @Query(
        """
        SELECT * FROM financial_transaction
        WHERE amountCurrency != :primaryCurrency
          AND (appliedExchangeRate IS NULL OR exchangeRateSource IS NULL)
        ORDER BY occurredAtEpochMillis, id
        """,
    )
    suspend fun listAwaitingAppliedExchangeRate(primaryCurrency: String): List<FinancialTransactionEntity>

    @Query(
        """
        SELECT * FROM financial_transaction
        WHERE occurredAtEpochMillis >= :startInclusiveEpochMillis
          AND occurredAtEpochMillis < :endExclusiveEpochMillis
        ORDER BY occurredAtEpochMillis DESC, id DESC
        """,
    )
    suspend fun listOccurredBetween(
        startInclusiveEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<FinancialTransactionEntity>

    @Query(
        """
        SELECT * FROM financial_transaction_raw_sms_link
        WHERE rawSmsId = :rawSmsId
        LIMIT 1
        """,
    )
    suspend fun findLinkByRawSmsId(rawSmsId: String): FinancialTransactionRawSmsLinkEntity?

    @Query(
        """
        SELECT rawSmsId FROM financial_transaction_raw_sms_link
        WHERE transactionId = :transactionId
        ORDER BY rawSmsId
        """,
    )
    suspend fun listRawSmsIdsForTransaction(transactionId: String): List<String>

    /** Callers keep [transactionIds] under [RoomBatch.MAX_BIND_ARGS]. */
    @Query(
        """
        SELECT rawSmsId FROM financial_transaction_raw_sms_link
        WHERE transactionId IN (:transactionIds)
        ORDER BY rawSmsId
        """,
    )
    suspend fun listRawSmsIdsForTransactions(transactionIds: List<String>): List<String>

    /** Callers keep [rawSmsIds] under [RoomBatch.MAX_BIND_ARGS]. */
    @Query(
        """
        SELECT DISTINCT transactionId FROM financial_transaction_raw_sms_link
        WHERE rawSmsId IN (:rawSmsIds)
        ORDER BY transactionId
        """,
    )
    suspend fun listTransactionIdsForRawSmsIds(rawSmsIds: List<String>): List<String>

    /** Callers keep [transactionIds] under [RoomBatch.MAX_BIND_ARGS]. */
    @Query(
        """
        SELECT * FROM financial_transaction_raw_sms_link
        WHERE transactionId IN (:transactionIds)
        ORDER BY transactionId, rawSmsId
        """,
    )
    suspend fun listLinksForTransactions(
        transactionIds: List<String>,
    ): List<FinancialTransactionRawSmsLinkEntity>

    /** Callers keep [ids] under [RoomBatch.MAX_BIND_ARGS]. */
    @Query("SELECT * FROM financial_transaction WHERE id IN (:ids) ORDER BY id")
    suspend fun listByIds(ids: List<String>): List<FinancialTransactionEntity>

    /** Callers keep [rawSmsIds] under [RoomBatch.MAX_BIND_ARGS]. */
    @Query("SELECT rawSmsId FROM financial_transaction_raw_sms_link WHERE rawSmsId IN (:rawSmsIds)")
    suspend fun listLinkedRawSmsIds(rawSmsIds: List<String>): List<String>

    /** Callers keep [rawSmsIds] under [RoomBatch.MAX_BIND_ARGS]. */
    @Query("SELECT * FROM financial_transaction_raw_sms_link WHERE rawSmsId IN (:rawSmsIds)")
    suspend fun listLinksForRawSmsIds(rawSmsIds: List<String>): List<FinancialTransactionRawSmsLinkEntity>

    @Query("SELECT COUNT(*) FROM financial_transaction")
    suspend fun count(): Int

    @Query("DELETE FROM financial_transaction_raw_sms_link WHERE rawSmsId = :rawSmsId")
    suspend fun deleteLinkByRawSmsId(rawSmsId: String): Int

    @Query("DELETE FROM financial_transaction WHERE id = :id")
    suspend fun deleteTransactionById(id: String): Int

    @Transaction
    suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String): Boolean {
        val link = findLinkByRawSmsId(rawSmsId) ?: return false
        if (!isOnlyLink(link.transactionId, rawSmsId)) return false
        deleteLinkByRawSmsId(rawSmsId)
        deleteTransactionById(link.transactionId)
        return true
    }

    /** True when [rawSmsId] is the transaction's only evidence link. */
    private suspend fun isOnlyLink(transactionId: String, rawSmsId: String): Boolean =
        listRawSmsIdsForTransaction(transactionId) == listOf(rawSmsId)

    /** Removes one evidence link; deletes the transaction when it has no links left. */
    @Transaction
    suspend fun unlinkRawSms(rawSmsId: String): Boolean {
        val link = findLinkByRawSmsId(rawSmsId) ?: return false
        deleteLinkByRawSmsId(rawSmsId)
        if (listRawSmsIdsForTransaction(link.transactionId).isEmpty()) {
            deleteTransactionById(link.transactionId)
        }
        return true
    }

    /**
     * Atomically removes exclusive stale external rows blocking [links], then saves
     * [entity]. Rolls back together when pre-checks fail or the write cannot complete.
     */
    @Transaction
    suspend fun replaceExclusiveStaleLinksAtomic(
        entity: FinancialTransactionEntity,
        links: List<FinancialTransactionRawSmsLinkEntity>,
        staleRawSmsIds: List<String>,
    ): SaveAtomicOutcome {
        val staleTransactionIds = mutableSetOf<String>()
        for (rawSmsId in staleRawSmsIds.distinct()) {
            val link = findLinkByRawSmsId(rawSmsId) ?: continue
            if (link.transactionId == entity.id) continue
            if (!isOnlyLink(link.transactionId, rawSmsId)) {
                return SaveAtomicOutcome.Conflict(rawSmsId, link.transactionId)
            }
            staleTransactionIds += link.transactionId
        }

        for (link in links) {
            val existing = findLinkByRawSmsId(link.rawSmsId)
            if (existing != null &&
                existing.transactionId != entity.id &&
                existing.transactionId !in staleTransactionIds
            ) {
                return SaveAtomicOutcome.Conflict(link.rawSmsId, existing.transactionId)
            }
        }

        for (rawSmsId in staleRawSmsIds.distinct()) {
            val link = findLinkByRawSmsId(rawSmsId) ?: continue
            if (link.transactionId == entity.id) continue
            deleteLinkByRawSmsId(rawSmsId)
            deleteTransactionById(link.transactionId)
        }

        return saveAtomic(entity, links)
    }

    @Transaction
    suspend fun saveAtomic(
        entity: FinancialTransactionEntity,
        links: List<FinancialTransactionRawSmsLinkEntity>,
    ): SaveAtomicOutcome {
        for (link in links) {
            val existing = findLinkByRawSmsId(link.rawSmsId)
            if (existing != null && existing.transactionId != entity.id) {
                return SaveAtomicOutcome.Conflict(link.rawSmsId, existing.transactionId)
            }
        }

        val inserted = insertTransactionIfAbsent(entity)
        if (inserted == -1L) {
            // Same id already present — refresh columns (idempotent re-save).
            // A complete exchange-rate pair stays; a partial pair is replaced whole.
            updatePreservingExchangeRate(entity)
        }

        var allLinksAlreadyPresent = inserted == -1L
        for (link in links) {
            val linkInsert = insertLinkIfAbsent(link)
            if (linkInsert != -1L) {
                allLinksAlreadyPresent = false
            }
        }

        return if (allLinksAlreadyPresent && inserted == -1L) {
            SaveAtomicOutcome.AlreadyExists
        } else {
            SaveAtomicOutcome.Saved
        }
    }

    /** Callers keep [transactionIds] under [RoomBatch.MAX_BIND_ARGS]. */
    @Query("SELECT sourceTransactionId FROM commitment WHERE sourceTransactionId IN (:transactionIds)")
    suspend fun listCommittedTransactionIds(transactionIds: List<String>): List<String>

    @Query("UPDATE commitment SET sourceTransactionId = :newId WHERE sourceTransactionId = :oldId")
    suspend fun moveCommitmentSource(oldId: String, newId: String): Int

    @Query("UPDATE review_item SET resolvedTransactionId = :newId WHERE resolvedTransactionId = :oldId AND rawSmsId = :rawSmsId")
    suspend fun moveReviewResolution(oldId: String, newId: String, rawSmsId: String): Int

    @Query("UPDATE review_item SET resolvedTransactionId = :newId WHERE resolvedTransactionId = :oldId")
    suspend fun moveReviewResolutions(oldId: String, newId: String): Int

    /** See `FinancialTransactionRepository.moveTransactionReferences`. */
    @Transaction
    suspend fun moveTransactionReferences(oldId: String, newId: String, newIdByRawSmsId: Map<String, String>) {
        newIdByRawSmsId.forEach { (rawSmsId, id) -> if (id != newId) moveReviewResolution(oldId, id, rawSmsId) }
        moveReviewResolutions(oldId, newId)
        moveCommitmentSource(oldId, newId)
    }

    sealed interface SaveAtomicOutcome {
        data object Saved : SaveAtomicOutcome

        data object AlreadyExists : SaveAtomicOutcome

        data class Conflict(
            val rawSmsId: String,
            val existingTransactionId: String,
        ) : SaveAtomicOutcome
    }
}
