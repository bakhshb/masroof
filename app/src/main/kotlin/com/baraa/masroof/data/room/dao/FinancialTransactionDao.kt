package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.baraa.masroof.data.room.ExchangeRatePairWrite
import com.baraa.masroof.data.room.entity.FinancialTransactionEntity
import com.baraa.masroof.data.room.entity.FinancialTransactionRawSmsLinkEntity

@Dao
interface FinancialTransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactionIfAbsent(entity: FinancialTransactionEntity): Long

    @Query(
        """
        UPDATE financial_transaction SET
          type = :type,
          amountDecimal = :amountDecimal,
          amountCurrency = :amountCurrency,
          occurredAtEpochMillis = :occurredAtEpochMillis,
          sourceContainerId = :sourceContainerId,
          destinationContainerId = :destinationContainerId,
          merchant = :merchant,
          counterparty = :counterparty,
          categoryId = :categoryId,
          appliedExchangeRate = :appliedExchangeRate,
          exchangeRateSource = :exchangeRateSource,
          occurredAtZone = :occurredAtZone
        WHERE id = :id
        """,
    )
    suspend fun updateTransaction(
        id: String,
        type: String,
        amountDecimal: String,
        amountCurrency: String,
        occurredAtEpochMillis: Long,
        sourceContainerId: String?,
        destinationContainerId: String?,
        merchant: String?,
        counterparty: String?,
        categoryId: String?,
        appliedExchangeRate: String?,
        exchangeRateSource: String?,
        occurredAtZone: String?,
    ): Int

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

    @Query("SELECT COUNT(*) FROM financial_transaction")
    suspend fun count(): Int

    @Query("DELETE FROM financial_transaction_raw_sms_link WHERE rawSmsId = :rawSmsId")
    suspend fun deleteLinkByRawSmsId(rawSmsId: String): Int

    @Query("DELETE FROM financial_transaction WHERE id = :id")
    suspend fun deleteTransactionById(id: String): Int

    @Transaction
    suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String): Boolean {
        val link = findLinkByRawSmsId(rawSmsId) ?: return false
        val linkedRawSmsIds = listRawSmsIdsForTransaction(link.transactionId)
        if (linkedRawSmsIds.size != 1 || linkedRawSmsIds.single() != rawSmsId) {
            return false
        }
        deleteLinkByRawSmsId(rawSmsId)
        deleteTransactionById(link.transactionId)
        return true
    }

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
            val linkedRawSmsIds = listRawSmsIdsForTransaction(link.transactionId)
            if (linkedRawSmsIds.size != 1 || linkedRawSmsIds.single() != rawSmsId) {
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
            val existing = getById(entity.id)
            val (rate, source) = ExchangeRatePairWrite.storedOrIncoming(
                storedRate = existing?.appliedExchangeRate,
                storedSource = existing?.exchangeRateSource,
                incomingRate = entity.appliedExchangeRate,
                incomingSource = entity.exchangeRateSource,
            )
            updateTransaction(
                id = entity.id,
                type = entity.type,
                amountDecimal = entity.amountDecimal,
                amountCurrency = entity.amountCurrency,
                occurredAtEpochMillis = entity.occurredAtEpochMillis,
                sourceContainerId = entity.sourceContainerId,
                destinationContainerId = entity.destinationContainerId,
                merchant = entity.merchant,
                counterparty = entity.counterparty,
                categoryId = entity.categoryId,
                appliedExchangeRate = rate,
                exchangeRateSource = source,
                occurredAtZone = entity.occurredAtZone,
            )
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

    sealed interface SaveAtomicOutcome {
        data object Saved : SaveAtomicOutcome

        data object AlreadyExists : SaveAtomicOutcome

        data class Conflict(
            val rawSmsId: String,
            val existingTransactionId: String,
        ) : SaveAtomicOutcome
    }
}
