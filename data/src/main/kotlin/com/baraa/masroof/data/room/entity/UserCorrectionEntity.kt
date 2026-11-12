package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "user_correction",
    foreignKeys = [
        ForeignKey(
            entity = RawSmsEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetRawSmsId"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["targetRawSmsId", "createdAtEpochMillis", "id"]),
    ],
)
data class UserCorrectionEntity(
    @PrimaryKey val id: String,
    val targetRawSmsId: String,
    val correctedMessageFamily: String?,
    val correctedAmountDecimal: String?,
    val correctedAmountCurrency: String?,
    val correctedMerchant: String?,
    val correctedCounterparty: String?,
    val createdAtEpochMillis: Long,
)
