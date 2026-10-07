package com.baraa.masroof.data.repository

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
) : FinancialTransactionRepository {
    override suspend fun save(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
    ): FinancialTransactionSaveResult =
        persist(transaction, rawSmsIds, dao::saveAtomic)

    override suspend fun replaceExclusiveStaleLinks(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
        staleRawSmsIds: Collection<String>,
    ): FinancialTransactionSaveResult =
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

    override suspend fun getById(id: String): FinancialTransaction? {
        val entity = dao.getById(id) ?: return null
        return reconstruct(entity)
    }

    override suspend fun findByRawSmsId(rawSmsId: String): FinancialTransaction? {
        val link = dao.findLinkByRawSmsId(rawSmsId) ?: return null
        return getById(link.transactionId)
    }

    override suspend fun listAll(): List<FinancialTransaction> =
        dao.listAll().map { reconstruct(it) }

    override suspend fun listByTypes(types: Collection<FinancialTransactionType>): List<FinancialTransaction> {
        if (types.isEmpty()) return emptyList()
        return dao.listByTypes(types.map { it.name }).map { reconstruct(it) }
    }

    override suspend fun listByTypesOccurredSince(
        types: Collection<FinancialTransactionType>,
        startInclusive: Instant,
    ): List<FinancialTransaction> {
        if (types.isEmpty()) return emptyList()
        return dao.listByTypesOccurredSince(
            types = types.map { it.name },
            startInclusiveEpochMillis = startInclusive.toEpochMilli(),
        ).map { reconstruct(it) }
    }

    override suspend fun listAwaitingAppliedExchangeRate(primaryCurrency: Currency): List<FinancialTransaction> =
        dao.listAwaitingAppliedExchangeRate(primaryCurrency.name).map { reconstruct(it) }

    override suspend fun listOccurredBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<FinancialTransaction> =
        dao.listOccurredBetween(
            startInclusiveEpochMillis = startInclusive.toEpochMilli(),
            endExclusiveEpochMillis = endExclusive.toEpochMilli(),
        ).map { reconstruct(it) }

    override suspend fun isRawSmsLinked(rawSmsId: String): Boolean =
        dao.findLinkByRawSmsId(rawSmsId) != null

    override suspend fun listRawSmsIds(transactionId: String): List<String> =
        dao.listRawSmsIdsForTransaction(transactionId)

    override suspend fun listRawSmsIdsForTransactions(transactionIds: Collection<String>): Set<String> =
        RoomBatch.query(transactionIds) { chunk -> dao.listRawSmsIdsForTransactions(chunk) }.toSortedSet()

    override suspend fun update(transaction: FinancialTransaction): Boolean {
        val entity = FinancialTransactionMapper.toEntity(transaction)
        return dao.updateTransaction(
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
            appliedExchangeRate = entity.appliedExchangeRate,
            exchangeRateSource = entity.exchangeRateSource,
            occurredAtZone = entity.occurredAtZone,
        ) > 0
    }

    override suspend fun updateAppliedExchangeRate(
        id: String,
        exchangeRate: BigDecimal,
        source: ExchangeRateSource,
    ): Boolean =
        dao.updateAppliedExchangeRate(
            id = id,
            exchangeRate = exchangeRate.toPlainString(),
            source = source.name,
        ) > 0

    override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String): Boolean =
        dao.deleteIfExclusiveRawSmsLink(rawSmsId)

    override suspend fun unlinkRawSms(rawSmsId: String): Boolean =
        dao.unlinkRawSms(rawSmsId)

    override suspend fun linkRawSmsIfAbsent(transactionId: String, rawSmsId: String): Boolean {
        if (dao.findLinkByRawSmsId(rawSmsId) != null) return false
        return dao.insertLinkIfAbsent(
            FinancialTransactionRawSmsLinkEntity(rawSmsId = rawSmsId, transactionId = transactionId),
        ) != -1L
    }

    private suspend fun reconstruct(
        entity: com.baraa.masroof.data.room.entity.FinancialTransactionEntity,
    ): FinancialTransaction {
        val rawSmsIds = dao.listRawSmsIdsForTransaction(entity.id)
        val linkedEventIds = rawSmsIds.mapNotNull { rawId ->
            parsedEventDao.findByRawSmsId(rawId)?.id
        }.sorted()
        return FinancialTransactionMapper.toDomain(entity, linkedEventIds)
    }
}
