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

    override suspend fun markRequired(rawSmsIds: List<String>, createdAt: Instant) {
        if (rawSmsIds.isEmpty()) return
        val createdAtEpochMillis = createdAt.toEpochMilli()
        dao.upsertAllAtomic(
            rawSmsIds.distinct().map { rawSmsId ->
                ProcessingRetryEntity(
                    rawSmsId = rawSmsId,
                    createdAtEpochMillis = createdAtEpochMillis,
                )
            },
        )
    }

    override suspend fun clear(rawSmsId: String) {
        dao.delete(rawSmsId)
    }

    override suspend fun clear(rawSmsIds: List<String>) {
        if (rawSmsIds.isEmpty()) return
        dao.deleteAllAtomic(rawSmsIds)
    }

    override suspend fun listRetryableRawSmsIds(): List<String> = dao.listRetryableRawSmsIds()

    override suspend fun listReviewedRetryableRawSmsIds(): List<String> =
        dao.listReviewedRetryableRawSmsIds()

    override suspend fun listUnreviewedRetryableRawSmsIds(): List<String> =
        dao.listUnreviewedRetryableRawSmsIds()
}
