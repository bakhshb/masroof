package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "credit_facility",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["bankId"]),
    ],
)
data class CreditFacilityEntity(
    val id: String,
    val bankId: String,
    val primaryLast4: String,
    val displayName: String? = null,
)
