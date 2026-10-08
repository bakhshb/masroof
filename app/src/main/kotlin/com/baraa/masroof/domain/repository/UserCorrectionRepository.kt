package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.UserCorrection

/**
 * Append-only persistence for [UserCorrection] overlays.
 */
interface UserCorrectionRepository {
    suspend fun save(correction: UserCorrection)

    suspend fun latestForRawSmsId(rawSmsId: String): UserCorrection?

    suspend fun listForRawSmsId(rawSmsId: String): List<UserCorrection>

    /**
     * Corrections for [rawSmsIds], ordered by target, [UserCorrection.createdAt], then id.
     * Ids with no corrections are omitted.
     */
    suspend fun listForRawSmsIds(rawSmsIds: Collection<String>): List<UserCorrection>
}
