package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A later inbox provider id that names an already stored live [RawSmsEntity].
 *
 * The original row stays immutable. This alias is only the cross-source identity
 * so a second, different provider id is not collapsed into that live evidence.
 */
@Entity(
    tableName = "raw_sms_provider_alias",
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
        Index(value = ["rawSmsId"]),
    ],
)
data class RawSmsProviderAliasEntity(
    @PrimaryKey val providerMessageId: String,
    val rawSmsId: String,
)
