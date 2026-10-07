package com.baraa.masroof.data.room.mapper

import com.baraa.masroof.data.room.entity.FinancialTransactionEntity
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import java.math.BigDecimal
import java.time.Instant

object FinancialTransactionMapper {
    fun toEntity(transaction: FinancialTransaction): FinancialTransactionEntity {
        val (decimal, currency) = MoneyPersistence.toColumns(transaction.amount)
        require(decimal != null && currency != null) { "FinancialTransaction amount required" }
        return FinancialTransactionEntity(
            id = transaction.id,
            type = transaction.type.name,
            amountDecimal = decimal,
            amountCurrency = currency,
            occurredAtEpochMillis = transaction.occurredAt.toEpochMilli(),
            sourceContainerId = transaction.sourceContainerId,
            destinationContainerId = transaction.destinationContainerId,
            merchant = transaction.merchant,
            counterparty = transaction.counterparty,
            categoryId = transaction.categoryId,
            appliedExchangeRate = transaction.appliedExchangeRate?.toPlainString(),
            exchangeRateSource = transaction.exchangeRateSource?.name,
            occurredAtZone = transaction.occurredAtZone,
        )
    }

    fun toDomain(
        entity: FinancialTransactionEntity,
        linkedParsedEventIds: List<String>,
    ): FinancialTransaction =
        FinancialTransaction(
            id = entity.id,
            type = FinancialTransactionType.valueOf(entity.type),
            amount = requireNotNull(
                MoneyPersistence.fromColumns(entity.amountDecimal, entity.amountCurrency),
            ),
            occurredAt = Instant.ofEpochMilli(entity.occurredAtEpochMillis),
            sourceContainerId = entity.sourceContainerId,
            destinationContainerId = entity.destinationContainerId,
            merchant = entity.merchant,
            counterparty = entity.counterparty,
            categoryId = entity.categoryId,
            linkedParsedEventIds = linkedParsedEventIds.sorted(),
            appliedExchangeRate = entity.appliedExchangeRate?.let { BigDecimal(it) },
            exchangeRateSource = entity.exchangeRateSource?.let { ExchangeRateSource.valueOf(it) },
            occurredAtZone = entity.occurredAtZone,
        )
}
