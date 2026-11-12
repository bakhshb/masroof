package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence model for [com.baraa.masroof.domain.model.RawSms].
 *
 * [dedupeKey] = sender + receivedAtEpochMillis + bodyHash — safe uniqueness that
 * keeps same-body / different-timestamp messages distinct.
 *
 * [deviceMessageId] has a unique index; SQLite allows multiple NULLs, so nullable
 * device IDs do not collapse unrelated rows.
 */
@Entity(
    tableName = "raw_sms",
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["deviceMessageId"], unique = true),
        Index(value = ["receivedAtEpochMillis"]),
    ],
)
data class RawSmsEntity(
    @PrimaryKey val id: String,
    val sender: String,
    val body: String,
    val receivedAtEpochMillis: Long,
    val deviceMessageId: String?,
    val bodyHash: String,
    val dedupeKey: String,
)
