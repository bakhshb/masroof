package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardRole
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class CreditFacilityOverviewBuilderTest {
    @Test
    fun alJaziraSetup_groupsPrimaryAndTwoSupplementariesWithMadaDebit() {
        val overview = CreditCardsOverview(
            cards = listOf(
                row("1111", "400.00"),
                row("2222", "150.00"),
                row("3333", "75.00"),
            ),
            aggregateDueAmount = null,
            aggregateDueUpdatedAt = null,
            aggregateDueDate = null,
            aggregatePeriodSpendingNet = SignedMoneyAmount(BigDecimal("625.00"), Currency.SAR),
            aggregateStatementSpendingNet = SignedMoneyAmount(BigDecimal("625.00"), Currency.SAR),
            aggregateStatementPeriodLabel = "Jul-Aug",
            calendarMonthLabel = null,
            salaryPeriodLabel = "Aug",
            currency = Currency.SAR,
        )
        val registry = listOf(
            card("1111", CardRole.PRIMARY, CardType.CREDIT),
            card("2222", CardRole.SUPPLEMENTARY, CardType.CREDIT, parent = "1111"),
            card("3333", CardRole.SUPPLEMENTARY, CardType.CREDIT, parent = "1111"),
            debit("9999"),
        )

        val facilities = CreditFacilityOverviewBuilder.build(overview, registry)

        assertEquals(1, facilities.facilities.size)
        assertEquals(1, facilities.debitCards.size)
        assertEquals("1111", facilities.facilities.single().primaryLast4)
        assertEquals(2, facilities.facilities.single().supplementaries.size)
        assertEquals(
            BigDecimal("625.00"),
            facilities.facilities.single().facilityStatementSpending.amount,
        )
        assertEquals("9999", facilities.debitCards.single().last4)
    }

    @Test
    fun twoBanksWithTheSameCardNumber_doNotShareStatementSpendOrDue() {
        val otherBank = Bank("OTHER_BANK")
        val overview = CreditCardsOverview(
            cards = listOf(
                row("7271", "100.00", bank = Bank.BANK_ALJAZIRA, due = "80.00"),
                row("7271", "250.00", bank = otherBank, due = "20.00"),
            ),
            aggregateDueAmount = null,
            aggregateDueUpdatedAt = null,
            aggregateDueDate = null,
            aggregatePeriodSpendingNet = SignedMoneyAmount(BigDecimal("350.00"), Currency.SAR),
            aggregateStatementSpendingNet = SignedMoneyAmount(BigDecimal("350.00"), Currency.SAR),
            aggregateStatementPeriodLabel = "Jul-Aug",
            calendarMonthLabel = null,
            salaryPeriodLabel = "Aug",
            currency = Currency.SAR,
        )
        val registry = listOf(
            card("7271", CardRole.STANDALONE, bank = Bank.BANK_ALJAZIRA),
            card("7271", CardRole.STANDALONE, bank = otherBank),
        )

        val facilities = CreditFacilityOverviewBuilder.build(overview, registry)

        assertEquals(2, facilities.facilities.size)
        val aljazira = facilities.facilities.single { it.bank == Bank.BANK_ALJAZIRA }
        val other = facilities.facilities.single { it.bank == otherBank }
        assertEquals(BigDecimal("100.00"), aljazira.facilityStatementSpending.amount)
        assertEquals(BigDecimal("250.00"), other.facilityStatementSpending.amount)
        assertEquals(Money.of("80.00", Currency.SAR), aljazira.facilityDue!!.amount)
        assertEquals(Money.of("20.00", Currency.SAR), other.facilityDue!!.amount)
        assertEquals(BigDecimal("350.00"), aljazira.facilityStatementSpending.amount + other.facilityStatementSpending.amount)
    }

    @Test
    fun noOwnedCredit_doesNotFallbackToAllTransactionCards() {
        val overview = CreditCardsOverview(
            cards = listOf(row("1111", "100.00")),
            aggregateDueAmount = null,
            aggregateDueUpdatedAt = null,
            aggregateDueDate = null,
            aggregatePeriodSpendingNet = SignedMoneyAmount(BigDecimal("100.00"), Currency.SAR),
            aggregateStatementSpendingNet = SignedMoneyAmount(BigDecimal("100.00"), Currency.SAR),
            aggregateStatementPeriodLabel = "Jul-Aug",
            calendarMonthLabel = null,
            salaryPeriodLabel = "Aug",
            currency = Currency.SAR,
        )

        val facilities = CreditFacilityOverviewBuilder.build(overview, registryCards = emptyList())

        assertEquals(0, facilities.facilities.size)
    }

    @Test
    fun groupsPrimaryWithSupplementariesAndSumsFacilitySpending() {
        val overview = CreditCardsOverview(
            cards = listOf(
                row("1111", "100.00"),
                row("2222", "50.00"),
                row("3333", "25.00"),
            ),
            aggregateDueAmount = null,
            aggregateDueUpdatedAt = null,
            aggregateDueDate = null,
            aggregatePeriodSpendingNet = SignedMoneyAmount(BigDecimal("175.00"), Currency.SAR),
            aggregateStatementSpendingNet = SignedMoneyAmount(BigDecimal("175.00"), Currency.SAR),
            aggregateStatementPeriodLabel = "Jul-Aug",
            calendarMonthLabel = null,
            salaryPeriodLabel = "Aug",
            currency = Currency.SAR,
        )
        val registry = listOf(
            card("1111", CardRole.PRIMARY),
            card("2222", CardRole.SUPPLEMENTARY, parent = "1111"),
            card("3333", CardRole.SUPPLEMENTARY, parent = "1111"),
        )

        val facilities = CreditFacilityOverviewBuilder.build(overview, registry)

        assertEquals(1, facilities.facilities.size)
        val facility = facilities.facilities.single()
        assertEquals("1111", facility.primaryLast4)
        assertEquals(2, facility.supplementaries.size)
        assertEquals(
            BigDecimal("175.00"),
            facility.facilityStatementSpending.amount,
        )
        assertNull(facility.facilityDue)
    }

    @Test
    fun debitLinkedAccount_usesAccountDisplayNameFromRegistry() {
        val overview = CreditCardsOverview(
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
        val registryAccounts = listOf(
            com.baraa.masroof.domain.model.AccountRegistryEntry.forTest(
                bank = Bank.BANK_ALJAZIRA,
                maskedNumber = "1234567890",
                ownership = OwnershipStatus.OWNED,
                displayName = "Home",
                firstSeenRawSmsId = "sms",
                lastSeenRawSmsId = "sms",
            ),
        )

        val facilities = CreditFacilityOverviewBuilder.build(
            overview = overview,
            registryCards = listOf(debit("9999")),
            registryAccounts = registryAccounts,
        )

        assertEquals("Home", facilities.debitCards.single().linkedAccountLabel)
    }

    @Test
    fun debitWithoutRegistryLink_infersLinkedAccountFromSms() {
        val overview = CreditCardsOverview(
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
        val body = "شراء من نقاط البيع\nبطاقة مدى: 9999\nخصمت من حساب: 3001"
        val parsedRecords = listOf(
            ParsedEventRecord(
                event = com.baraa.masroof.domain.model.ParsedEvent(
                    id = "evt-pos",
                    rawSmsId = "sms-pos",
                    bank = Bank.BANK_ALJAZIRA,
                    messageFamily = com.baraa.masroof.domain.model.MessageFamily.PURCHASE,
                    direction = com.baraa.masroof.domain.model.MoneyDirection.OUTGOING,
                    amount = null,
                    purchaseChannel = null,
                    cardRef = com.baraa.masroof.domain.model.CardReference(Bank.BANK_ALJAZIRA, "9999"),
                    sourceAccountRef = null,
                    destinationAccountRef = null,
                    merchant = null,
                    counterparty = body,
                    occurredAt = java.time.Instant.parse("2026-08-03T10:24:00Z"),
                    bankNetworkType = null,
                    confidence = com.baraa.masroof.domain.model.Confidence(1.0),
                    parseStatus = com.baraa.masroof.domain.model.ParseStatus.SUCCESS,
                ),
                details = com.baraa.masroof.parsing.model.ParsedEventDetails(
                    debitSourceAccountLast4 = "3001",
                ),
            ),
        )
        val registryAccounts = listOf(
            com.baraa.masroof.domain.model.AccountRegistryEntry.forTest(
                bank = Bank.BANK_ALJAZIRA,
                maskedNumber = "12345678903001",
                ownership = OwnershipStatus.OWNED,
                displayName = "Current",
                firstSeenRawSmsId = "sms",
                lastSeenRawSmsId = "sms",
            ),
        )

        val facilities = CreditFacilityOverviewBuilder.build(
            overview = overview,
            registryCards = listOf(
                CardRegistryEntry.forTest(
                    bank = Bank.BANK_ALJAZIRA,
                    last4 = "9999",
                    ownership = OwnershipStatus.OWNED,
                    cardType = CardType.DEBIT,
                    cardNetwork = com.baraa.masroof.domain.model.CardNetwork.MADA,
                    firstSeenRawSmsId = "sms",
                    lastSeenRawSmsId = "sms",
                ),
            ),
            registryAccounts = registryAccounts,
            parsedRecords = parsedRecords,
        )

        assertEquals("Current", facilities.debitCards.single().linkedAccountLabel)
        assertEquals("3001", facilities.debitCards.single().linkedAccountMaskedNumber)
    }

    private fun row(
        last4: String,
        amount: String,
        bank: Bank = Bank.BANK_ALJAZIRA,
        due: String? = null,
    ): CreditCardDashboardRow =
        CreditCardDashboardRow(
            bank = bank,
            last4 = last4,
            calendarMonthSpendingNet = SignedMoneyAmount.zero(Currency.SAR),
            statementSpendingNet = SignedMoneyAmount(BigDecimal(amount), Currency.SAR),
            salaryPeriodSpendingNet = SignedMoneyAmount(BigDecimal(amount), Currency.SAR),
            statementPeriodLabel = "Jul-Aug",
            snapshot = due?.let {
                CreditCardBalanceSnapshot(
                    availableBalance = null,
                    dueAmount = Money.of(it, Currency.SAR),
                    dueDate = LocalDate.parse("2026-09-20"),
                    statementIssuedAt = if (bank == Bank.BANK_ALJAZIRA) {
                        Instant.parse("2026-08-20T00:00:00Z")
                    } else {
                        Instant.parse("2026-08-21T00:00:00Z")
                    },
                    updatedAt = Instant.parse("2026-08-21T00:00:00Z"),
                )
            },
        )

    private fun card(
        last4: String,
        role: CardRole,
        cardType: CardType = CardType.CREDIT,
        parent: String? = null,
        bank: Bank = Bank.BANK_ALJAZIRA,
    ): CardRegistryEntry =
        CardRegistryEntry.forTest(
            bank = bank,
            last4 = last4,
            ownership = OwnershipStatus.OWNED,
            cardType = cardType,
            cardRole = role,
            parentCardLast4 = parent,
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )

    private fun debit(last4: String): CardRegistryEntry =
        CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = last4,
            ownership = OwnershipStatus.OWNED,
            cardType = CardType.DEBIT,
            cardNetwork = com.baraa.masroof.domain.model.CardNetwork.MADA,
            linkedAccountBankId = Bank.BANK_ALJAZIRA.id,
            linkedAccountMaskedNumber = "1234567890",
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )
}
