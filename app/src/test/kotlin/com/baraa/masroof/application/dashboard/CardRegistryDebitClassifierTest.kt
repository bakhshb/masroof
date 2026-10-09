package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardNetwork
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CardRegistryDebitClassifierTest {
    @Test
    fun explicitDebit_isDebit() {
        val entry = card(cardType = CardType.DEBIT)
        assertTrue(CardRegistryDebitClassifier.isDebitRegistryEntry(entry))
        assertFalse(
            CardRegistryDebitClassifier.isCreditRegistryEntry(entry),
        )
    }

    @Test
    fun explicitCredit_isNotDebit() {
        val entry = card(cardType = CardType.CREDIT)
        assertFalse(CardRegistryDebitClassifier.isDebitRegistryEntry(entry))
        assertTrue(CardRegistryDebitClassifier.isCreditRegistryEntry(entry))
    }

    @Test
    fun untypedMadaNetwork_isDebit() {
        val entry = card(cardNetwork = CardNetwork.MADA)
        assertTrue(CardRegistryDebitClassifier.isDebitRegistryEntry(entry))
        assertFalse(CardRegistryDebitClassifier.isCreditRegistryEntry(entry))
    }

    @Test
    fun untypedWithLinkedAccount_isDebit() {
        val entry = card(
            linkedAccountBankId = Bank.BANK_ALJAZIRA.id,
            linkedAccountMaskedNumber = "3001",
        )
        assertTrue(CardRegistryDebitClassifier.isDebitRegistryEntry(entry))
    }

    @Test
    fun untypedWithMadaSms_isDebit() {
        val entry = card(last4 = "8219")
        val body = """
            شراء عبر نقاط البيع (Google Pay)
            بطاقة مدى: 8219
            بمبلغ: 127.00 SAR
        """.trimIndent()
        val parsed = parsedRecord(last4 = "8219", body = body, channel = CardSmsChannel.DEBIT)

        assertTrue(
            CardRegistryDebitClassifier.isDebitRegistryEntry(
                entry,
                parsedRecords = listOf(parsed),
            ),
        )
        assertFalse(
            CardRegistryDebitClassifier.isCreditRegistryEntry(
                entry,
                parsedRecords = listOf(parsed),
            ),
        )
    }

    @Test
    fun untypedWithoutSignals_isCredit() {
        val entry = card()
        assertFalse(CardRegistryDebitClassifier.isDebitRegistryEntry(entry))
        assertTrue(CardRegistryDebitClassifier.isCreditRegistryEntry(entry))
    }

    @Test
    fun untypedWithCreditSmsAtRamadanMerchant_isCredit_notDebit() {
        val entry = card(last4 = "7271")
        val body = """
            شراء عبر نقاط البيع (Google Pay)
            بطاقة ائتمانية: 7271
            لدى: Ramadan Gifts
            بمبلغ: 75.00 SAR
            الرصيد المتاح: 14569.09 SAR
            إجمالي المبلغ المستحق:3921.11 SAR
        """.trimIndent()
        val parsed = parsedRecord(last4 = "7271", body = body, channel = CardSmsChannel.CREDIT)

        assertFalse(
            CardRegistryDebitClassifier.isDebitRegistryEntry(
                entry,
                parsedRecords = listOf(parsed),
            ),
        )
        assertTrue(
            CardRegistryDebitClassifier.isCreditRegistryEntry(
                entry,
                parsedRecords = listOf(parsed),
            ),
        )
    }

    private fun card(
        last4: String = "5555",
        cardType: CardType? = null,
        cardNetwork: CardNetwork? = null,
        linkedAccountBankId: String? = null,
        linkedAccountMaskedNumber: String? = null,
    ): CardRegistryEntry =
        CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = last4,
            ownership = OwnershipStatus.OWNED,
            cardType = cardType,
            cardNetwork = cardNetwork,
            linkedAccountBankId = linkedAccountBankId,
            linkedAccountMaskedNumber = linkedAccountMaskedNumber,
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )

    private fun parsedRecord(
        last4: String,
        body: String,
        channel: CardSmsChannel? = null,
    ): ParsedEventRecord {
        val event = ParsedEvent(
            id = "evt-$last4",
            rawSmsId = "sms-$last4",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.PURCHASE,
            direction = MoneyDirection.OUTGOING,
            amount = Money.of("1.00", Currency.SAR),
            purchaseChannel = null,
            sourceAccountRef = null,
            destinationAccountRef = null,
            cardRef = CardReference(Bank.BANK_ALJAZIRA, last4),
            merchant = null,
            counterparty = body,
            occurredAt = Instant.parse("2026-08-03T10:24:00Z"),
            bankNetworkType = null,
            confidence = Confidence(1.0),
            parseStatus = ParseStatus.SUCCESS,
        )
        return ParsedEventRecord(event = event, details = ParsedEventDetails(cardSmsChannel = channel))
    }
}
