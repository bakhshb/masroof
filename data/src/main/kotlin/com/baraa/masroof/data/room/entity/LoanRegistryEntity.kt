package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "loan_registry",
    primaryKeys = ["bankId", "loanType"],
    indices = [
        Index(value = ["bankId", "loanType"], unique = true),
        Index(value = ["id"], unique = true),
    ],
)
data class LoanRegistryEntity(
    val id: String,
    val bankId: String,
    val loanType: String,
    val ownershipStatus: String,
    val displayName: String? = null,
    val firstSeenRawSmsId: String?,
    val lastSeenRawSmsId: String?,
)
