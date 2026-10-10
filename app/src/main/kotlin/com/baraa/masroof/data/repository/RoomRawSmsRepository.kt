package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.dao.RawSmsDao
import com.baraa.masroof.data.room.dao.RoomBatch
import com.baraa.masroof.data.room.entity.RawSmsProviderAliasEntity
import com.baraa.masroof.data.room.mapper.RawSmsMapper
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import java.time.Instant

class RoomRawSmsRepository(
    private val dao: RawSmsDao,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : RawSmsRepository {
    /**
     * Duplicate protection is atomic via SQLite unique constraints + IGNORE.
     * Expected duplicates return [RawSmsInsertResult.AlreadyExists] without throwing.
     *
     * The shared `dedupeKey` is sender + receipt instant + body hash. That key
     * still collapses a live row and an inbox row whose clocks agree, and any
     * replay that lacks a distinct provider id. Two non-null provider ids are
     * different messages even at the same instant: the later row is stored
     * under a provider-qualified key so the unique index does not discard it.
     */
    override suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult = accessGate.withAccess {
        val entity = RawSmsMapper.toEntity(rawSms)
        if (dao.insertIfAbsent(entity) != -1L) {
            return@withAccess RawSmsInsertResult.Inserted
        }
        val incomingProviderId = rawSms.deviceMessageId?.takeIf { it.isNotBlank() }
            ?: return@withAccess RawSmsInsertResult.AlreadyExists
        val existing = dao.findByDedupeKey(entity.dedupeKey) ?: return@withAccess RawSmsInsertResult.AlreadyExists
        val storedProviderId = existing.deviceMessageId?.takeIf { it.isNotBlank() }
            ?: dao.findProviderAliasForRawSms(existing.id)
            ?: return@withAccess RawSmsInsertResult.AlreadyExists
        if (storedProviderId == incomingProviderId) {
            return@withAccess RawSmsInsertResult.AlreadyExists
        }
        val distinguished = entity.copy(
            dedupeKey = providerQualifiedDedupeKey(entity.dedupeKey, incomingProviderId),
        )
        return@withAccess if (dao.insertIfAbsent(distinguished) == -1L) {
            RawSmsInsertResult.AlreadyExists
        } else {
            RawSmsInsertResult.Inserted
        }
    }

    override suspend fun getById(id: String): RawSms? = accessGate.withAccess {
        dao.getById(id)?.let(RawSmsMapper::toDomain)
    }

    override suspend fun getByIds(ids: Collection<String>): List<RawSms> = accessGate.withAccess {
        RoomBatch.query(ids) { chunk -> dao.getByIds(chunk) }.map(RawSmsMapper::toDomain)
    }

    override suspend fun existsById(id: String): Boolean = accessGate.withAccess {
        dao.existsById(id)
    }

    override suspend fun findByDeviceMessageId(deviceMessageId: String): RawSms? = accessGate.withAccess {
        dao.findByDeviceMessageId(deviceMessageId)?.let(RawSmsMapper::toDomain)
    }

    override suspend fun findByProviderMessageId(providerMessageId: String): RawSms? = accessGate.withAccess {
        findByDeviceMessageId(providerMessageId)?.let { return@withAccess it }
        val rawSmsId = dao.findRawSmsIdByProviderAlias(providerMessageId) ?: return@withAccess null
        return@withAccess getById(rawSmsId)
    }

    override suspend fun findProviderAliasRawSmsId(providerMessageId: String): String? = accessGate.withAccess {
        dao.findRawSmsIdByProviderAlias(providerMessageId)
    }

    override suspend fun providerAliasRawSmsIds(rawSmsIds: Collection<String>): Set<String> = accessGate.withAccess {
        if (rawSmsIds.isEmpty()) return@withAccess emptySet()
        return@withAccess RoomBatch.query(rawSmsIds) { chunk -> dao.listAliasedRawSmsIds(chunk) }.toSet()
    }

    override suspend fun rememberProviderAlias(providerMessageId: String, rawSmsId: String): Boolean = accessGate.withAccess {
        dao.insertProviderAlias(
            RawSmsProviderAliasEntity(
                providerMessageId = providerMessageId,
                rawSmsId = rawSmsId,
            ),
        ) != -1L
    }

    override suspend fun listIdsByReceivedAt(): List<String> = accessGate.withAccess {
        dao.listIdsByReceivedAt()
    }

    override suspend fun listIdsAwaitingProcessing(): List<String> = accessGate.withAccess {
        dao.listIdsAwaitingProcessing()
    }

    override suspend fun findCrossSourceNearDuplicate(
        sender: String,
        bodyHash: String,
        fromInclusive: Instant,
        toInclusive: Instant,
        lookingForLiveRow: Boolean,
    ): RawSms? = accessGate.withAccess {
        dao.findCrossSourceNearDuplicate(
            sender = sender,
            bodyHash = bodyHash,
            fromMillis = fromInclusive.toEpochMilli(),
            toMillis = toInclusive.toEpochMilli(),
            requireDeviceMessageIdNull = lookingForLiveRow,
        )?.let(RawSmsMapper::toDomain)
    }

    override suspend fun listCrossSourceNearDuplicates(
        sender: String,
        bodyHash: String,
        fromInclusive: Instant,
        toInclusive: Instant,
        lookingForLiveRow: Boolean,
    ): List<RawSms> = accessGate.withAccess {
        dao.listCrossSourceNearDuplicates(
            sender = sender,
            bodyHash = bodyHash,
            fromMillis = fromInclusive.toEpochMilli(),
            toMillis = toInclusive.toEpochMilli(),
            requireDeviceMessageIdNull = lookingForLiveRow,
        ).map(RawSmsMapper::toDomain)
    }

    private fun providerQualifiedDedupeKey(baseKey: String, providerMessageId: String): String =
        "$baseKey|$providerMessageId"
}
