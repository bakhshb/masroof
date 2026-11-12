package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stable evidence link: each RawSms belongs to at most one FinancialTransaction.
 * Linked ParsedEvent ids are reconstructed from the current parsed_event row.
 */
@Entity(
    tableName = "financial_transaction_raw_sms_link",
    foreignKeys = [
        ForeignKey(
            entity = RawSmsEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawSmsId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FinancialTransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["transactionId"]),
    ],
)
data class FinancialTransactionRawSmsLinkEntity(
    @PrimaryKey val rawSmsId: String,
    val transactionId: String,
)
