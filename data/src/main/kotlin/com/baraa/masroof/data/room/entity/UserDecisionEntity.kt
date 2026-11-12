package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only user decisions; the columns are explained in docs/ARCHITECTURE.md. A `RECURRING` row (plan 5.2) names no
 * raw SMS: it carries [subjectKey] (the series key), [answer] and [payload]; every other row names [rawSmsId] and leaves
 * those three null ([com.baraa.masroof.data.room.dao.UserDecisionDao.insert] enforces both).
 */
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
    val rawSmsId: String?,
    val pairedRawSmsId: String? = null,
    val kind: String,
    val decidedAtEpochMillis: Long,
    val transactionType: String? = null,
    val sourceContainerId: String? = null,
    val destinationContainerId: String? = null,
    val occurredAtEpochMillis: Long? = null,
    val occurredAtZone: String? = null,
    val messageFamily: String? = null,
    val amountDecimal: String? = null,
    val amountCurrency: String? = null,
    val merchant: String? = null,
    val counterparty: String? = null,
    val bankId: String? = null,
    val subjectKey: String? = null,
    val answer: String? = null,
    val payload: String? = null,
)
