package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.assembly.TransactionAssembler
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.parsing.fixtures.AlJaziraFixtureLoader
import com.baraa.masroof.parsing.fixtures.AlJaziraFixtureParseHarness
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Account, card, bill, and cash-withdrawal totals come from persisted parse facts.
 * These calculation APIs require no raw SMS bodies.
 */
class DashboardParsedFactsClassificationTest {
    private val zoneId = ZoneId.of("Asia/Riyadh")
    private val occurredAt = Instant.parse("2026-08-10T12:00:00Z")
    private val salaryPeriod = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-11"))

    @Test
    fun fixtureCorpus_persistedFactsKeepSummaryAndDetailTotalsConsistent() {
        for (fixture in AlJaziraFixtureLoader.loadAllFromClasspath()) {
            val record = AlJaziraFixtureParseHarness.parseRecord(fixture, occurredAt)
            val tx = assemble(record) ?: continue
            val summary = CurrentAccountSummaryCalculator.summarize(listOf(tx), listOf(record))
            val details = CurrentAccountFlowDetailGrouper.group(listOf(tx), listOf(record))
            fun total(rows: List<FinancialTransaction>): Money = rows.fold(Money.zero(Currency.SAR)) { sum, row ->
                sum + (TransactionAmountResolver.effectiveAmount(row, Currency.SAR, emptyMap())
                    ?: Money.zero(Currency.SAR))
            }
            assertEquals(fixture.id, summary.inflow.coreTotal, total(details.income.values.flatten()))
            assertEquals(fixture.id, summary.outflow.coreTotal, total(details.expense.values.flatten()))
        }
    }

    @Test
    fun movementFixtures_keepCashBucketsFromPersistedFacts() {
        assertBucket("bill_payment_ar_001", bill = "210.00")
        assertBucket("card_payment_ar_001", creditCard = "802.62")
        assertBucket("card_payment_ar_tasdid_001", creditCard = "15000.00")
        assertBucket("withdrawal_ar_001", cash = "500.00")
        assertBucket("purchase_pos_ar_debit_001", pos = "120.00", mada = "120.00")
        assertBucket("purchase_pos_ar_debit_googlepay_001", pos = "127.00", mada = "127.00")
        assertBucket("fee_ar_001", fee = "5.00")
        assertBucket("financing_installment_ar_001", loan = "3036.11")
        assertBucket("purchase_pos_ar_cc_001")
    }

    @Test
    fun googlePayMadaPos_withoutSourceAccountLine_countsFromCardChannel() {
        val record = AlJaziraFixtureParseHarness.parseRecord("purchase_pos_ar_debit_googlepay_001", occurredAt)
        assertNull(record.event.sourceAccountRef)
        assertNull(record.details.debitSourceAccountLast4)
        assertEquals(CardSmsChannel.DEBIT, record.details.cardSmsChannel)
        val tx = requireNotNull(assemble(record))
        val blank = movement(record, tx)
        assertEquals(Money.of("127.00", Currency.SAR), blank.account.outflow.posPurchases)
        assertEquals(BigDecimal("127.00"), blank.madaByCard["BANK_ALJAZIRA:8219"]?.amount)
        assertEquals(Money.zero(Currency.SAR), blank.account.outflow.billPayments)
    }

    @Test
    fun debitSourceAccountLast4_attributesPurchaseWhenSourceRefIsMissing() {
        val cardId = FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "2210")
        val record = ParsedEventRecord(
            event = ParsedEvent(
                id = "evt-debit-fact",
                rawSmsId = "sms-debit-fact",
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.PURCHASE,
                direction = MoneyDirection.OUTGOING,
                amount = Money.of("120.00", Currency.SAR),
                purchaseChannel = null,
                sourceAccountRef = null,
                destinationAccountRef = null,
                cardRef = CardReference(Bank.BANK_ALJAZIRA, "2210"),
                merchant = "TEST_GROCER",
                counterparty = null,
                occurredAt = occurredAt,
                bankNetworkType = null,
                confidence = Confidence(1.0),
                parseStatus = ParseStatus.SUCCESS,
            ),
            details = ParsedEventDetails(
                cardSmsChannel = CardSmsChannel.DEBIT,
                debitSourceAccountLast4 = "3001",
            ),
        )
        val tx = FinancialTransaction(
            id = "tx-debit-fact",
            type = FinancialTransactionType.EXPENSE,
            amount = Money.of("120.00", Currency.SAR),
            occurredAt = occurredAt,
            sourceContainerId = cardId,
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf(record.event.id),
        )
        val blank = movement(record, tx)
        assertEquals(Money.of("120.00", Currency.SAR), blank.account.outflow.posPurchases)
        assertEquals(Money.zero(Currency.SAR), blank.account.outflow.billPayments)
    }

    @Test
    fun purchaseFamily_ignoresBillWordingInDisplayCounterparty() {
        val record = AlJaziraFixtureParseHarness.parseRecord("purchase_pos_ar_debit_001", occurredAt)
        val tx = requireNotNull(assemble(record))
        val misleading = movement(
            record.copy(event = record.event.copy(counterparty = "سداد فاتورة سداد بطاقة سحب نقدي")),
            tx,
        )
        assertEquals(Money.of("120.00", Currency.SAR), misleading.account.outflow.posPurchases)
        assertEquals(Money.zero(Currency.SAR), misleading.account.outflow.billPayments)
        assertEquals(Money.zero(Currency.SAR), misleading.account.outflow.creditCardPayments)
        assertEquals(Money.zero(Currency.SAR), misleading.account.outflow.cashWithdrawals)
        assertEquals(BigDecimal("120.00"), misleading.madaByCard["BANK_ALJAZIRA:2210"]?.amount)
    }

    private fun assertBucket(
        fixtureId: String,
        bill: String = "0.00",
        creditCard: String = "0.00",
        cash: String = "0.00",
        pos: String = "0.00",
        fee: String = "0.00",
        loan: String = "0.00",
        mada: String? = null,
    ) {
        val record = AlJaziraFixtureParseHarness.parseRecord(fixtureId, occurredAt)
        val tx = requireNotNull(assemble(record)) { fixtureId }
        val blank = movement(record, tx)
        val outflow = blank.account.outflow
        assertEquals(fixtureId, Money.of(bill, Currency.SAR), outflow.billPayments)
        assertEquals(fixtureId, Money.of(creditCard, Currency.SAR), outflow.creditCardPayments)
        assertEquals(fixtureId, Money.of(cash, Currency.SAR), outflow.cashWithdrawals)
        assertEquals(fixtureId, Money.of(pos, Currency.SAR), outflow.posPurchases)
        assertEquals(fixtureId, Money.of(fee, Currency.SAR), outflow.fees)
        assertEquals(fixtureId, Money.of(loan, Currency.SAR), outflow.loanRepayments)
        if (mada != null) {
            val last4 = requireNotNull(record.event.cardRef?.last4)
            assertEquals(fixtureId, BigDecimal(mada), blank.madaByCard["BANK_ALJAZIRA:$last4"]?.amount)
        }
    }

    private fun assemble(record: ParsedEventRecord): FinancialTransaction? {
        val outcome = TransactionAssembler.assembleSingle(
            event = record.event,
            receivedAt = occurredAt,
            sourceOwnership = OwnershipStatus.OWNED,
            destinationOwnership = OwnershipStatus.OWNED,
            cardOwnership = OwnershipStatus.OWNED,
            loanOwnership = OwnershipStatus.OWNED,
            loanType = record.details.loanType,
        )
        val assembled = outcome as? TransactionAssembler.Outcome.Assembled ?: return null
        return assembled.transaction.copy(occurredAt = occurredAt)
    }

    private fun movement(
        record: ParsedEventRecord,
        tx: FinancialTransaction,
    ): Movement {
        val ownedIds = setOfNotNull(
            record.event.sourceAccountRef?.let(FinancialContainerIdFactory::accountId),
            record.event.destinationAccountRef?.let(FinancialContainerIdFactory::accountId),
            record.details.debitSourceAccountLast4?.let { last4 ->
                if (record.event.bank == Bank.UNKNOWN) {
                    null
                } else {
                    FinancialContainerIdFactory.accountId(record.event.bank, last4)
                }
            },
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
        )
        val debitScope = debitScope(record)
        val account = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(tx),
            parsedRecords = listOf(record),
            ownedAccountContainerIds = ownedIds,
            ownedAccountLast4s = ownedIds.map { it.substringAfterLast(':') }.toSet(),
            debitCardScope = debitScope,
        )
        val mada = if (debitScope.ownedDebitCardContainerIds.isEmpty()) {
            emptyMap()
        } else {
            DebitCardOverviewBuilder.buildSpendingByCardKey(
                salaryPeriod = salaryPeriod,
                debitCards = debitCards(record),
                transactions = listOf(tx),
                parsedRecords = listOf(record),
                primaryCurrency = Currency.SAR,
                sarEquivalents = emptyMap(),
                ownedAccountContainerIds = ownedIds,
                ownedAccountLast4s = ownedIds.map { it.substringAfterLast(':') }.toSet(),
                zoneId = zoneId,
            ).spendingByCardKey
        }
        return Movement(account, mada)
    }

    private fun debitScope(record: ParsedEventRecord): DebitCardScopeFacts =
        DebitCardScopeFactory.fromRegistry(
            cards = debitCards(record),
            parsedRecords = listOf(record),
        )

    private fun debitCards(record: ParsedEventRecord): List<CardRegistryEntry> {
        if (record.details.cardSmsChannel != CardSmsChannel.DEBIT) return emptyList()
        val last4 = record.event.cardRef?.last4 ?: return emptyList()
        return listOf(
            CardRegistryEntry.forTest(
                bank = record.event.bank,
                last4 = last4,
                ownership = OwnershipStatus.OWNED,
                cardType = CardType.DEBIT,
                firstSeenRawSmsId = record.event.rawSmsId,
                lastSeenRawSmsId = record.event.rawSmsId,
            ),
        )
    }

    private data class Movement(
        val account: CurrentAccountSummary,
        val madaByCard: Map<String, SignedMoneyAmount>,
    )
}
