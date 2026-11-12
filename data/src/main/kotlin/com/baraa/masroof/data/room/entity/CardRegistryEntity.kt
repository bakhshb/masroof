package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "card_registry",
    primaryKeys = ["bankId", "last4"],
    indices = [
        Index(value = ["bankId", "last4"], unique = true),
        Index(value = ["id"], unique = true),
    ],
)
data class CardRegistryEntity(
    val id: String,
    val bankId: String,
    val last4: String,
    val ownershipStatus: String,
    val displayName: String? = null,
    val cardNetwork: String? = null,
    val cardType: String? = null,
    val linkedAccountBankId: String? = null,
    val linkedAccountMaskedNumber: String? = null,
    val parentCardLast4: String? = null,
    val cardRole: String? = null,
    val creditFacilityId: String? = null,
    val firstSeenRawSmsId: String?,
    val lastSeenRawSmsId: String?,
)
