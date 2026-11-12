package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only user decisions (schema v19); the columns are explained in docs/ARCHITECTURE.md. */
@Entity(
    tableName = "user_decision",
    foreignKeys = [
        ForeignKey(
            entity = RawSmsEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawSmsId"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = RawSmsEntity::class,
            parentColumns = ["id"],
            childColumns = ["pairedRawSmsId"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["rawSmsId", "sequence"]),
        Index(value = ["pairedRawSmsId"]),
    ],
)
data class UserDecisionEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val rawSmsId: String,
    val pairedRawSmsId: String?,
    val kind: String,
    val decidedAtEpochMillis: Long,
    val transactionType: String?,
    val sourceContainerId: String?,
    val destinationContainerId: String?,
    val messageFamily: String?,
    val amountDecimal: String?,
    val amountCurrency: String?,
    val merchant: String?,
    val counterparty: String?,
    val bankId: String?,
)
