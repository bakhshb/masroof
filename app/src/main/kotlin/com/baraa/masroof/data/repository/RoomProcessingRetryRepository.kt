package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.dao.ProcessingRetryDao
import com.baraa.masroof.data.room.entity.ProcessingRetryEntity
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import java.time.Instant

class RoomProcessingRetryRepository(
    private val dao: ProcessingRetryDao,
) : ProcessingRetryRepository {
    override suspend fun markRequired(rawSmsId: String, createdAt: Instant) {
        dao.upsert(
            ProcessingRetryEntity(
                rawSmsId = rawSmsId,
                createdAtEpochMillis = createdAt.toEpochMilli(),
            ),
        )
    }

    override suspend fun clear(rawSmsId: String) {
        dao.delete(rawSmsId)
    }

    override suspend fun listRetryableRawSmsIds(): List<String> = dao.listRetryableRawSmsIds()
}
