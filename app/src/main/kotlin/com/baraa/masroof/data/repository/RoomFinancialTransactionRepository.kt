package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.data.room.dao.FinancialTransactionDao
import com.baraa.masroof.data.room.dao.ParsedEventDao
import com.baraa.masroof.data.room.dao.RoomBatch
import com.baraa.masroof.data.room.entity.FinancialTransactionRawSmsLinkEntity
import com.baraa.masroof.data.room.mapper.FinancialTransactionMapper
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import java.math.BigDecimal
import java.time.Instant

class RoomFinancialTransactionRepository(
    private val dao: FinancialTransactionDao,
    private val parsedEventDao: ParsedEventDao,
    private val batchChunkSize: Int = RoomBatch.MAX_BIND_ARGS,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : FinancialTransactionRepository {
    override suspend fun save(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
    ): FinancialTransactionSaveResult = accessGate.withAccess {
        persist(transaction, rawSmsIds, dao::saveAtomic)
    }

    override suspend fun replaceExclusiveStaleLinks(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
        staleRawSmsIds: Collection<String>,
    ): FinancialTransactionSaveResult = accessGate.withAccess {
        persist(
            transaction = transaction,
            rawSmsIds = rawSmsIds,
            save = { entity, links ->
                dao.replaceExclusiveStaleLinksAtomic(
                    entity = entity,
                    links = links,
                    staleRawSmsIds = staleRawSmsIds.map { it.trim() }.filter { it.isNotEmpty() },
                )
            },
        )
    }

    private suspend fun persist(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
        save: suspend (
            entity: com.baraa.masroof.data.room.entity.FinancialTransactionEntity,
            links: List<FinancialTransactionRawSmsLinkEntity>,
        ) -> FinancialTransactionDao.SaveAtomicOutcome,
    ): FinancialTransactionSaveResult {
        val ids = rawSmsIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        require(ids.isNotEmpty()) { "rawSmsIds required" }

        val entity = FinancialTransactionMapper.toEntity(transaction)
        val links = ids.map { FinancialTransactionRawSmsLinkEntity(it, transaction.id) }

        return when (val outcome = save(entity, links)) {
            FinancialTransactionDao.SaveAtomicOutcome.Saved ->
                FinancialTransactionSaveResult.Saved

            FinancialTransactionDao.SaveAtomicOutcome.AlreadyExists ->
                FinancialTransactionSaveResult.AlreadyExists

            is FinancialTransactionDao.SaveAtomicOutcome.Conflict ->
                FinancialTransactionSaveResult.Conflict(
                    rawSmsId = outcome.rawSmsId,
                    existingTransactionId = outcome.existingTransactionId,
                )
        }
    }

    override suspend fun getById(id: String): FinancialTransaction? = accessGate.withAccess {
        val entity = dao.getById(id) ?: return@withAccess null
        return@withAccess reconstructBatch(listOf(entity)).single()
    }

    override suspend fun findByRawSmsId(rawSmsId: String): FinancialTransaction? = accessGate.withAccess {
        val link = dao.findLinkByRawSmsId(rawSmsId) ?: return@withAccess null
        return@withAccess getById(link.transactionId)
    }

    override suspend fun listAll(): List<FinancialTransaction> = accessGate.withAccess {
        reconstructBatch(dao.listAll())
    }

    override suspend fun listByTypes(types: Collection<FinancialTransactionType>): List<FinancialTransaction> = accessGate.withAccess {
        if (types.isEmpty()) return@withAccess emptyList()
        return@withAccess reconstructBatch(dao.listByTypes(types.map { it.name }))
    }

    override suspend fun listByTypesOccurredSince(
        types: Collection<FinancialTransactionType>,
        startInclusive: Instant,
    ): List<FinancialTransaction> = accessGate.withAccess {
        if (types.isEmpty()) return@withAccess emptyList()
        return@withAccess reconstructBatch(
            dao.listByTypesOccurredSince(
                types = types.map { it.name },
                startInclusiveEpochMillis = startInclusive.toEpochMilli(),
            ),
        )
    }

    override suspend fun listByTypesOccurredBetween(
        types: Collection<FinancialTransactionType>,
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<FinancialTransaction> = accessGate.withAccess {
        if (types.isEmpty() || !startInclusive.isBefore(endExclusive)) return@withAccess emptyList()
        return@withAccess reconstructBatch(
            dao.listByTypesOccurredBetween(
                types = types.map { it.name },
                startInclusiveEpochMillis = startInclusive.toEpochMilli(),
                endExclusiveEpochMillis = endExclusive.toEpochMilli(),
            ),
        )
    }

    override suspend fun listAwaitingAppliedExchangeRate(primaryCurrency: Currency): List<FinancialTransaction> = accessGate.withAccess {
        reconstructBatch(dao.listAwaitingAppliedExchangeRate(primaryCurrency.name))
    }

    override suspend fun listOccurredBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<FinancialTransaction> = accessGate.withAccess {
        reconstructBatch(
            dao.listOccurredBetween(
                startInclusiveEpochMillis = startInclusive.toEpochMilli(),
                endExclusiveEpochMillis = endExclusive.toEpochMilli(),
            ),
        )
    }

    override suspend fun isRawSmsLinked(rawSmsId: String): Boolean = accessGate.withAccess {
        dao.findLinkByRawSmsId(rawSmsId) != null
    }

    override suspend fun getByIds(ids: Collection<String>): List<FinancialTransaction> = accessGate.withAccess {
        reconstructBatch(RoomBatch.query(ids.distinct(), batchChunkSize) { chunk -> dao.listByIds(chunk) })
    }

    override suspend fun findByRawSmsIds(rawSmsIds: Collection<String>): Map<String, FinancialTransaction> = accessGate.withAccess {
        val links = RoomBatch.query(rawSmsIds.distinct(), batchChunkSize) { chunk -> dao.listLinksForRawSmsIds(chunk) }
        val byId = getByIds(links.map { it.transactionId }).associateBy { it.id }
        links.mapNotNull { link -> byId[link.transactionId]?.let { link.rawSmsId to it } }.toMap()
    }

    override suspend fun listLinkedRawSmsIds(rawSmsIds: Collection<String>): Set<String> = accessGate.withAccess {
        RoomBatch.query(rawSmsIds.distinct(), batchChunkSize) { chunk -> dao.listLinkedRawSmsIds(chunk) }.toSet()
    }

    override suspend fun listRawSmsIds(transactionId: String): List<String> = accessGate.withAccess {
        dao.listRawSmsIdsForTransaction(transactionId)
    }

    override suspend fun listRawSmsIdsForTransactions(transactionIds: Collection<String>): Set<String> = accessGate.withAccess {
        RoomBatch.query(transactionIds) { chunk -> dao.listRawSmsIdsForTransactions(chunk) }.toSortedSet()
    }

    override suspend fun listTransactionIdsForRawSmsIds(rawSmsIds: Collection<String>): Set<String> = accessGate.withAccess {
        RoomBatch.query(rawSmsIds) { chunk -> dao.listTransactionIdsForRawSmsIds(chunk) }.toSortedSet()
    }

    override suspend fun update(transaction: FinancialTransaction): Boolean = accessGate.withAccess {
        dao.updatePreservingExchangeRate(FinancialTransactionMapper.toEntity(transaction)) > 0
    }

    override suspend fun updateAppliedExchangeRate(
        id: String,
        exchangeRate: BigDecimal,
        source: ExchangeRateSource,
    ): Boolean = accessGate.withAccess {
        dao.updateAppliedExchangeRate(
            id = id,
            exchangeRate = exchangeRate.toPlainString(),
            source = source.name,
        ) > 0
    }

    override suspend fun replaceConfirmedHistoricalMerchantRate(
        id: String,
        exchangeRate: BigDecimal,
        source: ExchangeRateSource,
    ): Boolean = accessGate.withAccess {
        dao.replaceConfirmedHistoricalMerchantRate(
            id = id,
            exchangeRate = exchangeRate.toPlainString(),
            source = source.name,
        ) > 0
    }

    override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String): Boolean = accessGate.withAccess {
        dao.deleteIfExclusiveRawSmsLink(rawSmsId)
    }

    override suspend fun unlinkRawSms(rawSmsId: String): Boolean = accessGate.withAccess {
        dao.unlinkRawSms(rawSmsId)
    }

    override suspend fun linkRawSmsIfAbsent(transactionId: String, rawSmsId: String): Boolean = accessGate.withAccess {
        if (dao.findLinkByRawSmsId(rawSmsId) != null) return@withAccess false
        return@withAccess dao.insertLinkIfAbsent(
            FinancialTransactionRawSmsLinkEntity(rawSmsId = rawSmsId, transactionId = transactionId),
        ) != -1L
    }

    /**
     * One link query and one parsed-event query per bind-sized chunk, then each
     * entity is mapped with its sorted linked event ids. A missing parsed event
     * is omitted, matching the previous per-link lookup.
     */
    private suspend fun reconstructBatch(
        entities: List<com.baraa.masroof.data.room.entity.FinancialTransactionEntity>,
    ): List<FinancialTransaction> {
        if (entities.isEmpty()) return emptyList()
        val links = RoomBatch.query(entities.map { it.id }, batchChunkSize) { chunk ->
            dao.listLinksForTransactions(chunk)
        }
        val eventIdByRawSmsId = RoomBatch.query(links.map { it.rawSmsId }, batchChunkSize) { chunk ->
            parsedEventDao.listEventIdsByRawSmsIds(chunk)
        }.associate { it.rawSmsId to it.id }
        val linkedEventIdsByTransaction = links.groupBy(
            keySelector = { it.transactionId },
            valueTransform = { it.rawSmsId },
        ).mapValues { (_, rawSmsIds) ->
            rawSmsIds.mapNotNull(eventIdByRawSmsId::get).sorted()
        }
        return entities.map { entity ->
            FinancialTransactionMapper.toDomain(
                entity,
                linkedEventIdsByTransaction[entity.id].orEmpty(),
            )
        }
    }
}
