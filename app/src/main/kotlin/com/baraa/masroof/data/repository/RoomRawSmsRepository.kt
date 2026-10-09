package com.baraa.masroof.data.repository

import android.database.sqlite.SQLiteException
import com.baraa.masroof.data.room.dao.RawSmsDao
import com.baraa.masroof.data.room.dao.RoomBatch
import com.baraa.masroof.data.room.mapper.RawSmsMapper
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import java.time.Instant

class RoomRawSmsRepository(
    private val dao: RawSmsDao,
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
    override suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult {
        val entity = RawSmsMapper.toEntity(rawSms)
        if (dao.insertIfAbsent(entity) != -1L) {
            return RawSmsInsertResult.Inserted
        }
        val incomingProviderId = rawSms.deviceMessageId?.takeIf { it.isNotBlank() }
            ?: return RawSmsInsertResult.AlreadyExists
        val existing = dao.findByDedupeKey(entity.dedupeKey) ?: return RawSmsInsertResult.AlreadyExists
        val storedProviderId = existing.deviceMessageId?.takeIf { it.isNotBlank() }
            ?: return RawSmsInsertResult.AlreadyExists
        if (storedProviderId == incomingProviderId) {
            return RawSmsInsertResult.AlreadyExists
        }
        val distinguished = entity.copy(
            dedupeKey = providerQualifiedDedupeKey(entity.dedupeKey, incomingProviderId),
        )
        return if (dao.insertIfAbsent(distinguished) == -1L) {
            RawSmsInsertResult.AlreadyExists
        } else {
            RawSmsInsertResult.Inserted
        }
    }

    override suspend fun getById(id: String): RawSms? =
        dao.getById(id)?.let(RawSmsMapper::toDomain)

    override suspend fun getByIds(ids: Collection<String>): List<RawSms> =
        RoomBatch.query(ids) { chunk -> dao.getByIds(chunk) }.map(RawSmsMapper::toDomain)

    override suspend fun existsById(id: String): Boolean = dao.existsById(id)

    override suspend fun findByDeviceMessageId(deviceMessageId: String): RawSms? =
        dao.findByDeviceMessageId(deviceMessageId)?.let(RawSmsMapper::toDomain)

    override suspend fun adoptDeviceMessageIdIfAbsent(id: String, deviceMessageId: String): Boolean =
        try {
            dao.adoptDeviceMessageIdIfAbsent(id, deviceMessageId) == 1
        } catch (error: SQLiteException) {
            false
        }

    override suspend fun listIdsByReceivedAt(): List<String> = dao.listIdsByReceivedAt()

    override suspend fun listIdsAwaitingProcessing(): List<String> = dao.listIdsAwaitingProcessing()

    override suspend fun findCrossSourceNearDuplicate(
        sender: String,
        bodyHash: String,
        fromInclusive: Instant,
        toInclusive: Instant,
        lookingForLiveRow: Boolean,
    ): RawSms? =
        dao.findCrossSourceNearDuplicate(
            sender = sender,
            bodyHash = bodyHash,
            fromMillis = fromInclusive.toEpochMilli(),
            toMillis = toInclusive.toEpochMilli(),
            requireDeviceMessageIdNull = lookingForLiveRow,
        )?.let(RawSmsMapper::toDomain)

    override suspend fun listCrossSourceNearDuplicates(
        sender: String,
        bodyHash: String,
        fromInclusive: Instant,
        toInclusive: Instant,
        lookingForLiveRow: Boolean,
    ): List<RawSms> =
        dao.listCrossSourceNearDuplicates(
            sender = sender,
            bodyHash = bodyHash,
            fromMillis = fromInclusive.toEpochMilli(),
            toMillis = toInclusive.toEpochMilli(),
            requireDeviceMessageIdNull = lookingForLiveRow,
        ).map(RawSmsMapper::toDomain)

    private fun providerQualifiedDedupeKey(baseKey: String, providerMessageId: String): String =
        "$baseKey|$providerMessageId"
}
