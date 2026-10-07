package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "financial_transaction",
    indices = [
        Index(value = ["occurredAtEpochMillis"]),
    ],
)
data class FinancialTransactionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val amountDecimal: String,
    val amountCurrency: String,
    val occurredAtEpochMillis: Long,
    val sourceContainerId: String?,
    val destinationContainerId: String?,
    val merchant: String?,
    val counterparty: String?,
    val categoryId: String?,
    val appliedExchangeRate: String? = null,
    val exchangeRateSource: String? = null,
    /** IANA zone id captured when [occurredAtEpochMillis] was resolved. Null on legacy rows. */
    val occurredAtZone: String? = null,
)
