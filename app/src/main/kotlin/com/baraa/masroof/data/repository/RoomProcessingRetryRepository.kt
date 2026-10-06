package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.dao.ProcessingRetryDao
import com.baraa.masroof.data.room.entity.ProcessingRetryEntity
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import java.time.Instant

class RoomProcessingRetryRepository(
    private val dao: ProcessingRetryDao,
) : ProcessingRetryRepository {
    override suspend fun markRequired(rawSmsId: String, createdAt: Instant, mode: ProcessingRetryMode) {
        dao.upsert(entity(rawSmsId, createdAt, mode))
    }

    override suspend fun markRequired(
        rawSmsIds: List<String>,
        createdAt: Instant,
        mode: ProcessingRetryMode,
    ) {
        if (rawSmsIds.isEmpty()) return
        dao.upsertAllAtomic(rawSmsIds.distinct().map { entity(it, createdAt, mode) })
    }

    override suspend fun clear(rawSmsId: String) {
        dao.delete(rawSmsId)
    }

    override suspend fun clear(rawSmsIds: List<String>) {
        if (rawSmsIds.isEmpty()) return
        dao.deleteAllAtomic(rawSmsIds)
    }

    override suspend fun listRetryableRawSmsIds(): List<String> = dao.listRetryableRawSmsIds()

    override suspend fun listRetryableRawSmsIds(mode: ProcessingRetryMode): List<String> =
        dao.listRetryableRawSmsIdsByMode(mode.name)

    private fun entity(rawSmsId: String, createdAt: Instant, mode: ProcessingRetryMode) =
        ProcessingRetryEntity(
            rawSmsId = rawSmsId,
            createdAtEpochMillis = createdAt.toEpochMilli(),
            mode = mode.name,
        )
}
