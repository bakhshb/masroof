package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "commitment",
    indices = [
        Index(value = ["sourceTransactionId"], unique = true),
        Index(value = ["active"]),
    ],
)
data class CommitmentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val amountDecimal: String,
    val amountCurrency: String,
    val transactionDateIso: String,
    val recurrence: String?,
    val dueDateIso: String?,
    val active: Boolean,
    val pauseIntervalsJson: String = "[]",
    val sourceTransactionId: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
