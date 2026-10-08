package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class SelfTransferDeduplicatorTest {
    private val account1 = "account:bank_aljazira:3001"
    private val account3 = "account:bank_aljazira:3003"

    @Test
    fun sharedParsedEvent_keepsTheRowWithMoreLinks() {
        val duplicate = tx(
            id = "self-a",
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = "4445.67",
            source = account1,
            dest = account3,
            linked = listOf("evt-out"),
        )
        val canonical = tx(
            id = "self-b",
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = "4445.67",
            source = account1,
            dest = account3,
            linked = listOf("evt-out", "evt-in"),
        )

        val filtered = SelfTransferDeduplicator.filter(
            transactions = listOf(duplicate, canonical),
            parsedRecords = emptyList(),
        )

        assertEquals(listOf("self-b"), filtered.map { it.id })
    }

    @Test
    fun identicalAmountAndEndpoints_withoutSharedEvidence_keepsBoth() {
        val earlier = tx(
            id = "self-aug-03",
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = "2000.00",
            source = account1,
            dest = account3,
            linked = listOf("evt-aug-03"),
            occurredAt = Instant.parse("2026-08-03T07:38:00Z"),
        )
        val later = tx(
            id = "self-aug-10",
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = "2000.00",
            source = account1,
            dest = account3,
            linked = listOf("evt-aug-10"),
            occurredAt = Instant.parse("2026-08-10T12:12:00Z"),
        )
        val input = listOf(earlier, later)

        val filtered = SelfTransferDeduplicator.filter(input, parsedRecords = emptyList())

        assertEquals(listOf("self-aug-03", "self-aug-10"), filtered.map { it.id })
        assertEquals(listOf("self-aug-03", "self-aug-10"), input.map { it.id })
    }

    @Test
    fun repeatedSelfTransfers_onDifferentDates_bothAffectAccountMovement() {
        val august3 = completeSelfTransfer(
            id = "self-aug-03",
            outEvent = "evt-aug-03-out",
            inEvent = "evt-aug-03-in",
            occurredAt = Instant.parse("2026-08-03T07:38:00Z"),
            local = LocalDateTime.parse("2026-08-03T10:38:00"),
        )
        val august10 = completeSelfTransfer(
            id = "self-aug-10",
            outEvent = "evt-aug-10-out",
            inEvent = "evt-aug-10-in",
            occurredAt = Instant.parse("2026-08-10T12:12:00Z"),
            local = LocalDateTime.parse("2026-08-10T15:12:00"),
        )
        val transactions = listOf(august3.transaction, august10.transaction)
        val records = august3.records + august10.records

        val filtered = SelfTransferDeduplicator.filter(transactions, records)
        assertEquals(listOf("self-aug-03", "self-aug-10"), filtered.map { it.id })

        val summary = summaryFor(filtered, account3, "3003")
        assertEquals(Money.of("4000.00", Currency.SAR), summary.inflow.selfTransfersIn)
        assertEquals(Money.zero(Currency.SAR), summary.outflow.selfTransfersOut)
        assertEquals(
            SignedMoneyAmount.of(Money.of("4000.00", Currency.SAR)),
            summary.accountFlow().accountSummary().remaining,
        )
        assertEquals(filtered.map { it.id }, SelfTransferDeduplicator.filter(filtered, records).map { it.id })
    }

    @Test
    fun unrelatedExternalTransfer_sameAmount_staysVisible() {
        val self = completeSelfTransfer(
            id = "self-aug-03",
            outEvent = "evt-self-out",
            inEvent = "evt-self-in",
            occurredAt = Instant.parse("2026-08-03T07:38:00Z"),
            local = LocalDateTime.parse("2026-08-03T10:38:00"),
        )
        val external = tx(
            id = "external-aug-04",
            type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            amount = "2000.00",
            source = account1,
            dest = null,
            linked = listOf("evt-external"),
            occurredAt = Instant.parse("2026-08-04T09:26:00Z"),
        )
        val externalRecord = record(
            id = "evt-external",
            family = MessageFamily.TRANSFER_OUT,
            amount = "2000.00",
            sourceLast4 = "3001",
            destLast4 = "0593",
            network = BankNetworkType.INTER_BANK,
            local = LocalDateTime.parse("2026-08-04T12:26:00"),
            reference = "TEST_REFERENCE_EXT",
        )
        val transactions = listOf(self.transaction, external)
        val records = self.records + externalRecord

        val filtered = SelfTransferDeduplicator.filter(transactions, records)

        assertEquals(listOf("self-aug-03", "external-aug-04"), filtered.map { it.id })
        val summary = summaryFor(filtered, account1, "3001")
        assertEquals(Money.of("2000.00", Currency.SAR), summary.outflow.selfTransfersOut)
        assertEquals(Money.of("2000.00", Currency.SAR), summary.outflow.externalTransfersOut)
    }

    @Test
    fun oneMovement_twoSmsLegs_shownOnce() {
        val outgoing = tx(
            id = "self-out",
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = "2000.00",
            source = account1,
            dest = account3,
            linked = listOf("evt-out"),
        )
        val incoming = tx(
            id = "self-in",
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = "2000.00",
            source = account1,
            dest = account3,
            linked = listOf("evt-in"),
        )
        val records = intraPair("evt-out", "evt-in", "2000.00", LocalDateTime.parse("2026-08-03T10:38:00"))

        val filtered = SelfTransferDeduplicator.filter(listOf(outgoing, incoming), records)

        assertEquals(1, filtered.size)
        assertEquals(Money.of("2000.00", Currency.SAR), filtered.single().amount)
        val summary = summaryFor(filtered, account3, "3003")
        assertEquals(Money.of("2000.00", Currency.SAR), summary.inflow.selfTransfersIn)
    }

    @Test
    fun externalLegSharingTheMovementEvent_isHidden() {
        val self = tx(
            id = "self",
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = "2000.00",
            source = account1,
            dest = account3,
            linked = listOf("evt-out", "evt-in"),
        )
        val external = tx(
            id = "external-out",
            type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            amount = "2000.00",
            source = account1,
            dest = null,
            linked = listOf("evt-out"),
        )

        val filtered = SelfTransferDeduplicator.filter(
            listOf(external, self),
            intraPair("evt-out", "evt-in", "2000.00", LocalDateTime.parse("2026-08-03T10:38:00")),
        )

        assertEquals(listOf("self"), filtered.map { it.id })
    }

    @Test
    fun sharedRawSms_collapsesDuplicateRepresentations() {
        val first = tx(
            id = "self-a",
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = "2000.00",
            source = account1,
            dest = account3,
            linked = listOf("evt-a"),
        )
        val second = tx(
            id = "self-b",
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = "2000.00",
            source = account1,
            dest = account3,
            linked = listOf("evt-b"),
        )
        val records = listOf(
            record(
                id = "evt-a",
                family = MessageFamily.TRANSFER_OUT,
                amount = "2000.00",
                sourceLast4 = "3001",
                destLast4 = "3003",
                rawSmsId = "sms-shared",
            ),
            record(
                id = "evt-b",
                family = MessageFamily.TRANSFER_IN,
                amount = "2000.00",
                sourceLast4 = "3001",
                destLast4 = "3003",
                rawSmsId = "sms-shared",
            ),
        )

        val filtered = SelfTransferDeduplicator.filter(listOf(first, second), records)

        assertEquals(listOf("self-b"), filtered.map { it.id })
    }

    @Test
    fun twoMovementsInsideTheMatchWindow_withoutAUniquePair_allStayVisible() {
        val legs = listOf("out-a", "out-b", "in-a", "in-b").map { suffix ->
            tx(
                id = "self-$suffix",
                type = FinancialTransactionType.SELF_TRANSFER,
                amount = "2000.00",
                source = account1,
                dest = account3,
                linked = listOf("evt-$suffix"),
            )
        }
        val local = LocalDateTime.parse("2026-08-03T10:38:00")
        val records = listOf(
            record("evt-out-a", MessageFamily.TRANSFER_OUT, "2000.00", "3001", "3003", local = local),
            record("evt-out-b", MessageFamily.TRANSFER_OUT, "2000.00", "3001", "3003", local = local),
            record("evt-in-a", MessageFamily.TRANSFER_IN, "2000.00", "3001", "3003", local = local),
            record("evt-in-b", MessageFamily.TRANSFER_IN, "2000.00", "3001", "3003", local = local),
        )

        val filtered = SelfTransferDeduplicator.filter(legs, records)

        assertEquals(legs.map { it.id }, filtered.map { it.id })
    }

    @Test
    fun pairedInternalTransferLegs_collapseOnce_andNetToZeroCashPosition() {
        val amounts = listOf("4445.67", "0.33", "28093.33")
        val local = LocalDateTime.parse("2026-08-02T15:00:00")
        val transactions = amounts.flatMap { amount ->
            listOf(
                tx(
                    id = "self-$amount-a",
                    type = FinancialTransactionType.SELF_TRANSFER,
                    amount = amount,
                    source = account1,
                    dest = account3,
                    linked = listOf("evt-$amount-out"),
                ),
                tx(
                    id = "self-$amount-b",
                    type = FinancialTransactionType.SELF_TRANSFER,
                    amount = amount,
                    source = account1,
                    dest = account3,
                    linked = listOf("evt-$amount-in"),
                ),
            )
        } + tx(
            id = "external-out",
            type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            amount = "32539.33",
            source = account3,
            dest = null,
            linked = listOf("evt-external"),
        )
        val records = amounts.flatMap { amount ->
            intraPair("evt-$amount-out", "evt-$amount-in", amount, local)
        }

        val filtered = SelfTransferDeduplicator.filter(transactions, records)
        assertEquals(
            listOf("self-4445.67-b", "self-0.33-b", "self-28093.33-b", "external-out"),
            filtered.map { it.id },
        )

        val summary = summaryFor(filtered, account3, "3003")
        assertEquals(Money.of("32539.33", Currency.SAR), summary.inflow.selfTransfersIn)
        assertEquals(Money.zero(Currency.SAR), summary.outflow.selfTransfersOut)
        assertEquals(SignedMoneyAmount.zero(Currency.SAR), summary.accountFlow().accountSummary().remaining)
    }

    @Test
    fun complementaryExternalLegs_stayVisibleUntilOneRowCarriesBothEndpoints() {
        val outgoing = tx(
            id = "external-out",
            type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            amount = "2000.00",
            source = account1,
            dest = null,
            linked = listOf("evt-out"),
        )
        val incoming = tx(
            id = "external-in",
            type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
            amount = "2000.00",
            source = null,
            dest = account3,
            linked = listOf("evt-in"),
        )
        val records = intraPair("evt-out", "evt-in", "2000.00", LocalDateTime.parse("2026-08-03T10:38:00"))

        val filtered = SelfTransferDeduplicator.filter(listOf(outgoing, incoming), records)

        assertEquals(listOf("external-out", "external-in"), filtered.map { it.id })
    }

    @Test
    fun nonTransferRows_areLeftUntouched() {
        val expense = tx(
            id = "expense",
            type = FinancialTransactionType.EXPENSE,
            amount = "2000.00",
            source = account1,
            dest = null,
            linked = listOf("evt-purchase"),
        )
        val filtered = SelfTransferDeduplicator.filter(listOf(expense), emptyList())
        assertEquals(listOf("expense"), filtered.map { it.id })
    }

    private fun summaryFor(
        transactions: List<FinancialTransaction>,
        accountId: String,
        last4: String,
    ) = CurrentAccountSummaryCalculator.summarize(
        transactions = transactions,
        parsedRecords = emptyList(),
        ownedAccountContainerIds = setOf(accountId),
        ownedAccountLast4s = setOf(last4),
        scopeMode = AccountFlowScopeMode.SingleAccount,
    )

    private fun completeSelfTransfer(
        id: String,
        outEvent: String,
        inEvent: String,
        occurredAt: Instant,
        local: LocalDateTime,
    ): Movement {
        val transaction = tx(
            id = id,
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = "2000.00",
            source = account1,
            dest = account3,
            linked = listOf(outEvent, inEvent),
            occurredAt = occurredAt,
        )
        return Movement(transaction, intraPair(outEvent, inEvent, "2000.00", local))
    }

    private fun intraPair(
        outEvent: String,
        inEvent: String,
        amount: String,
        local: LocalDateTime,
    ): List<ParsedEventRecord> = listOf(
        record(outEvent, MessageFamily.TRANSFER_OUT, amount, "3001", "3003", local = local),
        record(inEvent, MessageFamily.TRANSFER_IN, amount, "3001", "3003", local = local),
    )

    private fun tx(
        id: String,
        type: FinancialTransactionType,
        amount: String,
        source: String?,
        dest: String?,
        linked: List<String>,
        occurredAt: Instant = Instant.parse("2026-08-02T12:00:00Z"),
    ): FinancialTransaction =
        FinancialTransaction(
            id = id,
            type = type,
            amount = Money.of(amount, Currency.SAR),
            occurredAt = occurredAt,
            sourceContainerId = source,
            destinationContainerId = dest,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = linked,
        )

    private fun record(
        id: String,
        family: MessageFamily,
        amount: String,
        sourceLast4: String,
        destLast4: String,
        rawSmsId: String = "sms-$id",
        network: BankNetworkType = BankNetworkType.INTRA_BANK,
        local: LocalDateTime? = null,
        reference: String? = null,
    ): ParsedEventRecord {
        val direction = if (family == MessageFamily.TRANSFER_IN) {
            MoneyDirection.INCOMING
        } else {
            MoneyDirection.OUTGOING
        }
        return ParsedEventRecord(
            event = ParsedEvent(
                id = id,
                rawSmsId = rawSmsId,
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = family,
                direction = direction,
                amount = Money.of(amount, Currency.SAR),
                purchaseChannel = null,
                sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, sourceLast4),
                destinationAccountRef = AccountReference(Bank.BANK_ALJAZIRA, destLast4),
                cardRef = null,
                merchant = null,
                counterparty = null,
                occurredAt = null,
                bankNetworkType = network,
                confidence = Confidence(1.0),
                parseStatus = ParseStatus.SUCCESS,
            ),
            details = ParsedEventDetails(
                transactionReference = reference,
                occurredAtLocal = local,
            ),
        )
    }

    private data class Movement(
        val transaction: FinancialTransaction,
        val records: List<ParsedEventRecord>,
    )
}
