package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Durable retry marker for recognized-bank evidence whose derived processing
 * did not finish. Independent of [ReviewItemEntity] so a resolved financial
 * review can stay resolved while the SMS remains recoverable.
 */
@Entity(
    tableName = "processing_retry",
    foreignKeys = [
        ForeignKey(
            entity = RawSmsEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawSmsId"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class ProcessingRetryEntity(
    @PrimaryKey val rawSmsId: String,
    val createdAtEpochMillis: Long,
)
