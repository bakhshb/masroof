package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "account_registry",
    primaryKeys = ["bankId", "maskedNumber"],
    indices = [
        Index(value = ["bankId", "maskedNumber"], unique = true),
        Index(value = ["id"], unique = true),
    ],
)
data class AccountRegistryEntity(
    val id: String,
    val bankId: String,
    val maskedNumber: String,
    val ownershipStatus: String,
    val displayName: String? = null,
    val accountType: String = "CURRENT",
    val firstSeenRawSmsId: String?,
    val lastSeenRawSmsId: String?,
)
