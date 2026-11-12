package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.UserCorrection

/**
 * Append-only persistence for [UserCorrection] overlays, the legacy trace of each `CORRECTION` decision.
 * Processing reads corrections from `user_decision`; only learning reads this store.
 */
interface UserCorrectionRepository {
    suspend fun save(correction: UserCorrection)

    /** Every correction. Each one targets a stored RawSms, which the schema enforces. */
    suspend fun listAll(): List<UserCorrection>
}
