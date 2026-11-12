package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class TransactionDisplayEnricherTest {
    @Test
    fun fillsMissingMerchantFromLinkedParsedEvent() {
        val tx = FinancialTransaction(
            id = "tx-1",
            type = FinancialTransactionType.REFUND,
            amount = Money.of("6.51", Currency.USD),
            occurredAt = Instant.parse("2026-08-17T15:23:00Z"),
            sourceContainerId = null,
            destinationContainerId = "card:bank_aljazira:7271",
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-refund"),
        )
        val parsed = ParsedEventRecord(
            event = ParsedEvent(
                id = "pe-refund",
                rawSmsId = "sms-refund",
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.REFUND,
                direction = null,
                amount = Money.of("6.51", Currency.USD),
                purchaseChannel = null,
                sourceAccountRef = null,
                destinationAccountRef = null,
                cardRef = null,
                merchant = "CURSOR, AI POWERED IDE",
                counterparty = null,
                occurredAt = null,
                bankNetworkType = null,
                confidence = com.baraa.masroof.domain.model.Confidence(1.0, emptyList()),
                parseStatus = ParseStatus.SUCCESS,
            ),
            details = com.baraa.masroof.parsing.model.ParsedEventDetails(),
        )

        val enriched = TransactionDisplayEnricher.enrichMerchants(listOf(tx), listOf(parsed)).single()

        assertEquals("CURSOR, AI POWERED IDE", enriched.merchant)
    }
}
