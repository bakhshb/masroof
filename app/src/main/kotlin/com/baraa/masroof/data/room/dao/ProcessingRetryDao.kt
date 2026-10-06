package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.baraa.masroof.data.room.entity.ProcessingRetryEntity

@Dao
interface ProcessingRetryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProcessingRetryEntity)

    @Query("DELETE FROM processing_retry WHERE rawSmsId = :rawSmsId")
    suspend fun delete(rawSmsId: String)

    /**
     * Oldest receipt first. A [com.baraa.masroof.domain.model.ReviewResolutionKind.USER_NON_FINANCIAL]
     * review stays closed and is not returned.
     */
    @Query(
        """
        SELECT p.rawSmsId FROM processing_retry p
        INNER JOIN raw_sms r ON r.id = p.rawSmsId
        LEFT JOIN review_item v ON v.rawSmsId = p.rawSmsId
        WHERE v.resolutionKind IS NULL OR v.resolutionKind != 'USER_NON_FINANCIAL'
        ORDER BY r.receivedAtEpochMillis ASC, r.id ASC
        """,
    )
    suspend fun listRetryableRawSmsIds(): List<String>
}
