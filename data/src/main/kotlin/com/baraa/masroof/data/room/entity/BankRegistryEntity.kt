package com.baraa.masroof.data.room.entity

import androidx.room.Entity

@Entity(
    tableName = "bank_registry",
    primaryKeys = ["bankId"],
)
data class BankRegistryEntity(
    val bankId: String,
    val displayName: String? = null,
)
