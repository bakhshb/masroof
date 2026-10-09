package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.baraa.masroof.data.room.entity.RawSmsEntity

@Dao
interface RawSmsDao {
    /**
     * Atomic insert gated by unique constraints (id, deviceMessageId, dedupeKey).
     * Returns the row id, or **-1** when ignored due to conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: RawSmsEntity): Long

    @Query("SELECT * FROM raw_sms WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RawSmsEntity?

    /** Callers keep [ids] under [RoomBatch.MAX_BIND_ARGS]. */
    @Query("SELECT * FROM raw_sms WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<RawSmsEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM raw_sms WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Query("SELECT * FROM raw_sms WHERE deviceMessageId = :deviceMessageId LIMIT 1")
    suspend fun findByDeviceMessageId(deviceMessageId: String): RawSmsEntity?

    @Query("SELECT id FROM raw_sms ORDER BY receivedAtEpochMillis ASC, id ASC")
    suspend fun listIdsByReceivedAt(): List<String>

    @Query(
        """
        SELECT r.id FROM raw_sms r
        WHERE NOT EXISTS (SELECT 1 FROM parsed_event p WHERE p.rawSmsId = r.id)
          AND NOT EXISTS (SELECT 1 FROM review_item v WHERE v.rawSmsId = r.id)
        ORDER BY r.receivedAtEpochMillis ASC, r.id ASC
        """,
    )
    suspend fun listIdsAwaitingProcessing(): List<String>

    @Query("SELECT * FROM raw_sms WHERE dedupeKey = :dedupeKey LIMIT 1")
    suspend fun findByDedupeKey(dedupeKey: String): RawSmsEntity?

    /**
     * Fills a missing provider id on a live row. Returns 1 when this row was updated.
     * A row that already has an id, or a provider id owned by another row, is left unchanged.
     */
    @Query(
        """
        UPDATE raw_sms
        SET deviceMessageId = :deviceMessageId
        WHERE id = :id AND deviceMessageId IS NULL
        """,
    )
    suspend fun adoptDeviceMessageIdIfAbsent(id: String, deviceMessageId: String): Int

    /**
     * Cross-source live↔historical near-duplicate lookup.
     *
     * When [requireDeviceMessageIdNull] is true, finds live rows
     * (deviceMessageId IS NULL). When false, finds historical rows
     * (deviceMessageId IS NOT NULL).
     */
    @Query(
        """
        SELECT * FROM raw_sms
        WHERE sender = :sender
          AND bodyHash = :bodyHash
          AND receivedAtEpochMillis BETWEEN :fromMillis AND :toMillis
          AND (
            (:requireDeviceMessageIdNull = 1 AND deviceMessageId IS NULL)
            OR
            (:requireDeviceMessageIdNull = 0 AND deviceMessageId IS NOT NULL)
          )
        LIMIT 1
        """,
    )
    suspend fun findCrossSourceNearDuplicate(
        sender: String,
        bodyHash: String,
        fromMillis: Long,
        toMillis: Long,
        requireDeviceMessageIdNull: Boolean,
    ): RawSmsEntity?

    @Query(
        """
        SELECT * FROM raw_sms
        WHERE sender = :sender
          AND bodyHash = :bodyHash
          AND receivedAtEpochMillis BETWEEN :fromMillis AND :toMillis
          AND (
            (:requireDeviceMessageIdNull = 1 AND deviceMessageId IS NULL)
            OR
            (:requireDeviceMessageIdNull = 0 AND deviceMessageId IS NOT NULL)
          )
        ORDER BY receivedAtEpochMillis ASC
        LIMIT 2
        """,
    )
    suspend fun listCrossSourceNearDuplicates(
        sender: String,
        bodyHash: String,
        fromMillis: Long,
        toMillis: Long,
        requireDeviceMessageIdNull: Boolean,
    ): List<RawSmsEntity>

    @Query("SELECT COUNT(*) FROM raw_sms")
    suspend fun count(): Int
}