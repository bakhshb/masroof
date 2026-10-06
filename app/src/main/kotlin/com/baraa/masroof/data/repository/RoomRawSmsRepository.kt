package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.dao.RawSmsDao
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
     */
    override suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult {
        val rowId = dao.insertIfAbsent(RawSmsMapper.toEntity(rawSms))
        return if (rowId == -1L) {
            RawSmsInsertResult.AlreadyExists
        } else {
            RawSmsInsertResult.Inserted
        }
    }

    override suspend fun getById(id: String): RawSms? =
        dao.getById(id)?.let(RawSmsMapper::toDomain)

    override suspend fun existsById(id: String): Boolean = dao.existsById(id)

    override suspend fun findByDeviceMessageId(deviceMessageId: String): RawSms? =
        dao.findByDeviceMessageId(deviceMessageId)?.let(RawSmsMapper::toDomain)

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
}
