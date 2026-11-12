package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "review_item",
    foreignKeys = [
        ForeignKey(
            entity = RawSmsEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawSmsId"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["rawSmsId"], unique = true),
        Index(value = ["status"]),
    ],
)
data class ReviewItemEntity(
    @PrimaryKey val id: String,
    val rawSmsId: String,
    val kind: String,
    val status: String,
    /** Reason codes joined with [REASON_SEPARATOR]. */
    val reasons: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val resolvedAtEpochMillis: Long?,
    val resolutionKind: String?,
    val resolvedTransactionId: String?,
) {
    companion object {
        const val REASON_SEPARATOR: Char = '\u001e'
    }
}
