package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.dao.ProcessingRetryDao
import com.baraa.masroof.data.room.entity.ProcessingRetryEntity
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import java.time.Instant

class RoomProcessingRetryRepository(
    private val dao: ProcessingRetryDao,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : ProcessingRetryRepository {
    override suspend fun markRequired(rawSmsId: String, createdAt: Instant, mode: ProcessingRetryMode): Unit = accessGate.withAccess<Unit> {
        dao.upsert(entity(rawSmsId, createdAt, mode))
    }

    override suspend fun markRequired(
        rawSmsIds: List<String>,
        createdAt: Instant,
        mode: ProcessingRetryMode,
    ): Unit = accessGate.withAccess<Unit> {
        if (rawSmsIds.isEmpty()) return@withAccess
        dao.upsertAllAtomic(rawSmsIds.distinct().map { entity(it, createdAt, mode) })
    }

    override suspend fun clear(rawSmsId: String): Unit = accessGate.withAccess<Unit> {
        dao.delete(rawSmsId)
    }

    override suspend fun clear(rawSmsIds: List<String>): Unit = accessGate.withAccess<Unit> {
        if (rawSmsIds.isEmpty()) return@withAccess
        dao.deleteAllAtomic(rawSmsIds)
    }

    override suspend fun listRetryableRawSmsIds(): List<String> = accessGate.withAccess {
        dao.listRetryableRawSmsIds()
    }

    override suspend fun listRetryableRawSmsIds(mode: ProcessingRetryMode): List<String> = accessGate.withAccess {
        dao.listRetryableRawSmsIdsByMode(mode.name)
    }

    private fun entity(rawSmsId: String, createdAt: Instant, mode: ProcessingRetryMode) =
        ProcessingRetryEntity(
            rawSmsId = rawSmsId,
            createdAtEpochMillis = createdAt.toEpochMilli(),
            mode = mode.name,
        )
}
