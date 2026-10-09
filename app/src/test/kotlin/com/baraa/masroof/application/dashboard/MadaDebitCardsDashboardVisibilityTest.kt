package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardRole
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.presentation.dashboard.DashboardUiState
import com.baraa.masroof.presentation.dashboard.OwnedCardUi
import com.baraa.masroof.presentation.dashboard.followedCreditFacilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Reproduces: Settings shows all owned cards, but dashboard omits Mada cards that were
 * never explicitly marked [CardType.DEBIT] during registry setup.
 */
class MadaDebitCardsDashboardVisibilityTest {
    @Test
    fun ownedMadaCardsWithoutDebitType_appearInDashboardDebitList() {
        val registry = listOf(
            creditCard("1111"),
            ownedMadaWithoutMetadata("8219"),
            ownedMadaWithoutMetadata("5555"),
            ownedMadaWithoutMetadata("7777"),
        )
        val parsedRecords = listOf(
            parsedMadaPurchase("evt-8219", "sms-8219", "8219"),
            parsedMadaPurchase("evt-5555", "sms-5555", "5555"),
            parsedMadaPurchase("evt-7777", "sms-7777", "7777"),
        )
        val overview = CreditCardsOverview(
            cards = listOf(creditRow("1111")),
            aggregateDueAmount = null,
            aggregateDueUpdatedAt = null,
            aggregateDueDate = null,
            aggregatePeriodSpendingNet = SignedMoneyAmount.zero(Currency.SAR),
            aggregateStatementSpendingNet = SignedMoneyAmount.zero(Currency.SAR),
            aggregateStatementPeriodLabel = null,
            calendarMonthLabel = null,
            salaryPeriodLabel = "Aug",
            currency = Currency.SAR,
        )

        val facilities = CreditFacilityOverviewBuilder.build(
            overview = overview,
            registryCards = registry,
            parsedRecords = parsedRecords,
        )

        assertEquals(1, facilities.facilities.size)
        assertEquals(
            "Expected owned Mada cards with debit SMS evidence in debitCards",
            listOf("5555", "7777", "8219"),
            facilities.debitCards.map { it.last4 }.sorted(),
        )
    }

    @Test
    fun followedCreditFacilities_includesOwnedMadaDebitCards() {
        val registry = listOf(
            creditCard("1111"),
            ownedMadaWithoutMetadata("8219"),
            ownedMadaWithoutMetadata("5555"),
        )
        val parsedRecords = listOf(
            parsedMadaPurchase("evt-8219", "sms-8219", "8219"),
            parsedMadaPurchase("evt-5555", "sms-5555", "5555"),
        )
        val built = CreditFacilityOverviewBuilder.build(
            overview = emptyCreditOverview(),
            registryCards = registry,
            parsedRecords = parsedRecords,
        )
        val state = DashboardUiState(
            creditFacilities = built,
            ownedCards = registry.map {
                OwnedCardUi(bank = it.bank, last4 = it.last4)
            },
        )

        val followed = state.followedCreditFacilities()

        assertNotNull(followed)
        assertEquals(1, followed!!.facilities.size)
        assertEquals(
            listOf("5555", "8219"),
            followed.debitCards.map { it.last4 }.sorted(),
        )
    }

    @Test
    fun debitCardRegistryInferrer_detectsMadaFromSmsWhenTypeMissing() {
        val entry = ownedMadaWithoutMetadata("8219")
        val parsedRecords = listOf(parsedMadaPurchase("evt-8219", "sms-8219", "8219"))

        assertTrue(
            CardRegistryDebitClassifier.isDebitRegistryEntry(
                entry,
                parsedRecords = parsedRecords,
            ),
        )
    }

    @Test
    fun debitCardRegistryInferrer_ignoresCreditSmsAtRamadanMerchant() {
        val entry = ownedMadaWithoutMetadata("7271")
        val parsedRecords = listOf(
            parsedMadaPurchase("evt-7271", "sms-7271", "7271", CardSmsChannel.CREDIT),
        )

        assertFalse(
            CardRegistryDebitClassifier.isDebitRegistryEntry(
                entry,
                parsedRecords = parsedRecords,
            ),
        )
    }

    private fun creditCard(last4: String): CardRegistryEntry =
        CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = last4,
            ownership = OwnershipStatus.OWNED,
            cardType = CardType.CREDIT,
            cardRole = CardRole.PRIMARY,
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )

    private fun ownedMadaWithoutMetadata(last4: String): CardRegistryEntry =
        CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = last4,
            ownership = OwnershipStatus.OWNED,
            cardType = null,
            cardNetwork = null,
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )

    private fun creditRow(last4: String): CreditCardDashboardRow =
        CreditCardDashboardRow(
            bank = Bank.BANK_ALJAZIRA,
            last4 = last4,
            calendarMonthSpendingNet = SignedMoneyAmount.zero(Currency.SAR),
            statementSpendingNet = SignedMoneyAmount.zero(Currency.SAR),
            salaryPeriodSpendingNet = SignedMoneyAmount(BigDecimal("100.00"), Currency.SAR),
            statementPeriodLabel = null,
            snapshot = null,
        )

    private fun emptyCreditOverview(): CreditCardsOverview =
        CreditCardsOverview(
            cards = emptyList(),
            aggregateDueAmount = null,
            aggregateDueUpdatedAt = null,
            aggregateDueDate = null,
            aggregatePeriodSpendingNet = SignedMoneyAmount.zero(Currency.SAR),
            aggregateStatementSpendingNet = SignedMoneyAmount.zero(Currency.SAR),
            aggregateStatementPeriodLabel = null,
            calendarMonthLabel = null,
            salaryPeriodLabel = "Aug",
            currency = Currency.SAR,
        )

    private fun parsedMadaPurchase(
        eventId: String,
        smsId: String,
        last4: String,
        channel: CardSmsChannel = CardSmsChannel.DEBIT,
    ): ParsedEventRecord =
        ParsedEventRecord(
            event = ParsedEvent(
                id = eventId,
                rawSmsId = smsId,
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.PURCHASE,
                direction = MoneyDirection.OUTGOING,
                amount = null,
                purchaseChannel = null,
                cardRef = CardReference(Bank.BANK_ALJAZIRA, last4),
                sourceAccountRef = null,
                destinationAccountRef = null,
                merchant = "SHOP",
                counterparty = null,
                occurredAt = Instant.parse("2026-08-03T10:24:00Z"),
                bankNetworkType = null,
                confidence = Confidence(1.0),
                parseStatus = ParseStatus.SUCCESS,
            ),
            details = ParsedEventDetails(cardSmsChannel = channel),
        )
}
