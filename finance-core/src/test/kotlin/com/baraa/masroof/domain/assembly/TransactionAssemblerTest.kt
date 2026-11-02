package com.baraa.masroof.domain.assembly

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.matching.TransferMatchCandidate
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.PurchaseChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.time.Instant

class TransactionAssemblerTest {

    private val receivedAt = Instant.parse("2026-08-01T12:00:00Z")

    @Test
    fun singleEventConstructionPreservesExplicitResolutionAndEvidence() {
        val evidence = event(
            family = MessageFamily.UNKNOWN,
            amount = money("51.99"),
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
        ).copy(merchant = "Shop", counterparty = "Party", parseStatus = ParseStatus.REVIEW_REQUIRED)
        val cardId = FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "7271")
        val transaction = FinancialTransaction.fromSingleEvent(
            event = evidence,
            type = FinancialTransactionType.EXPENSE,
            occurredAt = receivedAt,
            sourceContainerId = cardId,
            destinationContainerId = null,
            occurredAtZone = "Asia/Riyadh",
        )

        assertEquals(com.baraa.masroof.domain.ids.TransactionIdFactory.fromRawSmsIds(listOf(evidence.rawSmsId)), transaction.id)
        assertEquals(FinancialTransactionType.EXPENSE, transaction.type)
        assertEquals(evidence.amount, transaction.amount)
        assertEquals(cardId, transaction.sourceContainerId)
        assertNull(transaction.destinationContainerId)
        assertEquals(listOf(evidence.id), transaction.linkedParsedEventIds)
        assertEquals(receivedAt, transaction.occurredAt)
        assertEquals("Asia/Riyadh", transaction.occurredAtZone)
        assertEquals("Shop", transaction.merchant)
        assertEquals("Party", transaction.counterparty)
        assertNull(transaction.categoryId)
        assertNull(transaction.appliedExchangeRate)
        assertNull(transaction.exchangeRateSource)
    }

    @Test
    fun assemblerSource_doesNotFabricateAccountOrCardTypes() {
        val source = File(
            "src/main/kotlin/com/baraa/masroof/domain/assembly/TransactionAssembler.kt",
        ).readText()
        val imports = source.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .toSet()
        assertFalse(imports.contains("import com.baraa.masroof.domain.model.AccountType"))
        assertFalse(imports.contains("import com.baraa.masroof.domain.model.CardType"))
        assertFalse(imports.contains("import com.baraa.masroof.domain.model.Account"))
        assertFalse(imports.contains("import com.baraa.masroof.domain.model.Card"))
        assertFalse(source.contains("AccountType."))
        assertFalse(source.contains("CardType."))
        assertFalse(Regex("""\bAccount\s*\(""").containsMatchIn(source))
        assertFalse(Regex("""\bCard\s*\(""").containsMatchIn(source))
    }

    @Test
    fun purchase_knownCard_expenseWithCardSourceId() {
        val outcome = TransactionAssembler.assembleSingle(
            event = event(
                family = MessageFamily.PURCHASE,
                amount = money("51.99"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                channel = PurchaseChannel.ONLINE,
            ),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.OWNED,
        ) as TransactionAssembler.Outcome.Assembled

        assertEquals(FinancialTransactionType.EXPENSE, outcome.transaction.type)
        assertEquals(
            FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "7271"),
            outcome.transaction.sourceContainerId,
        )
        assertNull(outcome.transaction.destinationContainerId)
    }

    @Test
    fun purchase_madaDebitWithAccount_usesAccountSourceId() {
        val outcome = TransactionAssembler.assembleSingle(
            event = event(
                family = MessageFamily.PURCHASE,
                amount = money("120.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                card = CardReference(Bank.BANK_ALJAZIRA, "2210"),
                channel = PurchaseChannel.POS,
            ),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.OWNED,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.OWNED,
        ) as TransactionAssembler.Outcome.Assembled

        assertEquals(FinancialTransactionType.EXPENSE, outcome.transaction.type)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            outcome.transaction.sourceContainerId,
        )
        assertNull(outcome.transaction.destinationContainerId)
    }

    @Test
    fun cardPayment_creditCardPayment_withoutInventingCardType() {
        val outcome = TransactionAssembler.assembleSingle(
            event = event(
                family = MessageFamily.CARD_PAYMENT,
                amount = money("200.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.OWNED,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.OWNED,
        ) as TransactionAssembler.Outcome.Assembled

        assertEquals(FinancialTransactionType.CREDIT_CARD_PAYMENT, outcome.transaction.type)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            outcome.transaction.sourceContainerId,
        )
        assertEquals(
            FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "7271"),
            outcome.transaction.destinationContainerId,
        )
    }

    @Test
    fun refundToAccount_usesDestinationContainerId() {
        val outcome = TransactionAssembler.assembleSingle(
            event = event(
                family = MessageFamily.REFUND,
                amount = money("10.00"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            ),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.OWNED,
            cardOwnership = OwnershipStatus.UNKNOWN,
        ) as TransactionAssembler.Outcome.Assembled

        assertEquals(FinancialTransactionType.REFUND, outcome.transaction.type)
        assertNull(outcome.transaction.sourceContainerId)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            outcome.transaction.destinationContainerId,
        )
    }

    @Test
    fun cardOnlyRefund_usesDestinationContainerId() {
        val outcome = TransactionAssembler.assembleSingle(
            event = event(
                family = MessageFamily.REFUND,
                amount = money("10.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.OWNED,
        ) as TransactionAssembler.Outcome.Assembled

        assertEquals(FinancialTransactionType.REFUND, outcome.transaction.type)
        assertNull(outcome.transaction.sourceContainerId)
        assertEquals(
            FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "7271"),
            outcome.transaction.destinationContainerId,
        )
    }

    @Test
    fun refund_neverPutsReceivingContainerInSource() {
        val withAccount = TransactionAssembler.assembleSingle(
            event = event(
                family = MessageFamily.REFUND,
                amount = money("10.00"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.OWNED,
            cardOwnership = OwnershipStatus.OWNED,
        ) as TransactionAssembler.Outcome.Assembled

        assertNull(withAccount.transaction.sourceContainerId)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            withAccount.transaction.destinationContainerId,
        )
        assertTrue(withAccount.transaction.type == FinancialTransactionType.REFUND)
        assertTrue(withAccount.transaction.type != FinancialTransactionType.INCOME)
    }

    @Test
    fun successfulPurchase_withoutResolvedInstrument_retainsExpenseFallback() {
        val outcome = TransactionAssembler.assembleSingle(
            event = event(family = MessageFamily.PURCHASE, amount = money("15.00")),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.UNKNOWN,
        ) as TransactionAssembler.Outcome.Assembled

        assertEquals(FinancialTransactionType.EXPENSE, outcome.transaction.type)
        assertEquals(money("15.00"), outcome.transaction.amount)
        assertEquals(listOf("pe-1"), outcome.transaction.linkedParsedEventIds)
        assertEquals(listOf("sms-1"), outcome.rawSmsIds)
    }

    @Test
    fun unmatchedTransfers_reportMissingOrNonDurableOwnedEndpoint() {
        for (family in listOf(MessageFamily.TRANSFER_OUT, MessageFamily.TRANSFER_IN)) {
            val outgoing = family == MessageFamily.TRANSFER_OUT
            val side = if (outgoing) "source" else "destination"
            val endpoints = listOf(
                null to "missing_$side",
                AccountReference(Bank.BANK_ALJAZIRA, " ") to "${side}_not_durable",
                AccountReference(Bank.UNKNOWN, "3001") to "${side}_not_durable",
            )
            for ((reference, reason) in endpoints) {
                val outcome = TransactionAssembler.assembleUnmatchedOwnedTransfer(
                    candidate = transferCandidate(
                        event = event(
                            family = family,
                            amount = money("100.00"),
                            source = reference.takeIf { outgoing },
                            destination = reference.takeUnless { outgoing },
                        ),
                        sourceOwnership = if (outgoing) OwnershipStatus.OWNED else OwnershipStatus.UNKNOWN,
                        destinationOwnership = if (outgoing) OwnershipStatus.UNKNOWN else OwnershipStatus.OWNED,
                    ),
                )
                assertEquals(TransactionAssembler.Outcome.NeedsReview(listOf(reason)), outcome)
            }
        }
    }

    @Test
    fun ownedOwnedIntraBank_assembleSingle_defersUntilPairing() {
        val outcome = TransactionAssembler.assembleSingle(
            event = event(
                family = MessageFamily.TRANSFER_OUT,
                amount = money("2000.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3002"),
                bankNetworkType = BankNetworkType.INTRA_BANK,
            ),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.OWNED,
            destinationOwnership = OwnershipStatus.OWNED,
            cardOwnership = OwnershipStatus.UNKNOWN,
        )
        assertTrue(outcome is TransactionAssembler.Outcome.PendingMatch)
    }

    @Test
    fun unmatchedOwnedOwned_withPendingCounterpart_staysPending() {
        val outgoing = transferCandidate(
            event = event(
                id = "pe-out",
                rawSmsId = "sms-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("2000.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3002"),
                bankNetworkType = BankNetworkType.INTRA_BANK,
            ),
            sourceOwnership = OwnershipStatus.OWNED,
            destinationOwnership = OwnershipStatus.OWNED,
        )
        val incoming = transferCandidate(
            event = event(
                id = "pe-in",
                rawSmsId = "sms-in",
                family = MessageFamily.TRANSFER_IN,
                amount = money("2000.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3002"),
                bankNetworkType = BankNetworkType.INTRA_BANK,
            ),
            sourceOwnership = OwnershipStatus.OWNED,
            destinationOwnership = OwnershipStatus.OWNED,
        )
        val outcome = TransactionAssembler.assembleUnmatchedOwnedTransfer(
            candidate = outgoing,
            pendingCounterparts = listOf(incoming),
        )
        assertTrue(outcome is TransactionAssembler.Outcome.PendingMatch)
    }

    /** A cross-bank pair stores one zone; both legs' wall clocks are read in it, so a re-check finds the same instant. */
    @Test
    fun matchedPairAcrossBanks_readsBothWallClocksInTheStoredZone() {
        val local = java.time.LocalDateTime.parse("2026-08-10T12:00:00")
        val riyadh = java.time.ZoneId.of("Asia/Riyadh")
        val snb = Bank("BANK_SNB")
        val out = transferCandidate(
            event(MessageFamily.TRANSFER_OUT, money("100.00"), source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(snb, "4001"), bankNetworkType = BankNetworkType.INTER_BANK).copy(occurredAt = null),
            OwnershipStatus.OWNED, OwnershipStatus.OWNED,
        ).copy(occurredAtLocal = local.plusMinutes(5))
        val incoming = transferCandidate(
            event(MessageFamily.TRANSFER_IN, money("100.00"), source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(snb, "4001"), id = "pe-2", rawSmsId = "sms-2",
                bankNetworkType = BankNetworkType.INTER_BANK).copy(occurredAt = null, bank = snb),
            OwnershipStatus.OWNED, OwnershipStatus.OWNED,
        ).copy(occurredAtLocal = local)

        val tx = (TransactionAssembler.assembleMatchedPair(
            com.baraa.masroof.domain.matching.TransferMatchPair(out, incoming),
            OwnershipStatus.OWNED, OwnershipStatus.OWNED, fallbackZone = java.time.ZoneId.of("Asia/Dubai"),
        ) as TransactionAssembler.Outcome.Assembled).transaction

        assertEquals(riyadh.id, tx.occurredAtZone)
        assertEquals(local.atZone(riyadh).toInstant(), tx.occurredAt)
    }

    /** The reconciler builds candidates only from gate-passing evidence, such as a user's financial confirmation. */
    @Test
    fun unmatchedOwnedOwned_userConfirmedLegNotYetValidated_stillPosts() {
        val outcome = TransactionAssembler.assembleUnmatchedOwnedTransfer(
            candidate = transferCandidate(
                event = event(
                    family = MessageFamily.TRANSFER_OUT,
                    amount = money("2000.00"),
                    source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                    destination = AccountReference(Bank.BANK_ALJAZIRA, "3002"),
                    bankNetworkType = BankNetworkType.INTRA_BANK,
                    status = ParseStatus.REVIEW_REQUIRED,
                ),
                sourceOwnership = OwnershipStatus.OWNED,
                destinationOwnership = OwnershipStatus.OWNED,
            ),
        )

        assertEquals(
            FinancialTransactionType.SELF_TRANSFER,
            (outcome as TransactionAssembler.Outcome.Assembled).transaction.type,
        )
    }

    @Test
    fun unmatchedOwnedOwned_withoutCounterpart_postsSelfTransfer() {
        val outcome = TransactionAssembler.assembleUnmatchedOwnedTransfer(
            candidate = transferCandidate(
                event = event(
                    family = MessageFamily.TRANSFER_OUT,
                    amount = money("2000.00"),
                    source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                    destination = AccountReference(Bank.BANK_ALJAZIRA, "3002"),
                    bankNetworkType = BankNetworkType.INTRA_BANK,
                ),
                sourceOwnership = OwnershipStatus.OWNED,
                destinationOwnership = OwnershipStatus.OWNED,
            ),
        ) as TransactionAssembler.Outcome.Assembled

        assertEquals(FinancialTransactionType.SELF_TRANSFER, outcome.transaction.type)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            outcome.transaction.sourceContainerId,
        )
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002"),
            outcome.transaction.destinationContainerId,
        )
    }

    @Test
    fun unmatchedOutgoing_ownedSource_unknownDest_externalOut() {
        val outcome = TransactionAssembler.assembleUnmatchedOwnedTransfer(
            candidate = transferCandidate(
                event = event(
                    family = MessageFamily.TRANSFER_OUT,
                    amount = money("500.00"),
                    source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                    destination = AccountReference(Bank.UNKNOWN, "0593"),
                ),
                sourceOwnership = OwnershipStatus.OWNED,
                destinationOwnership = OwnershipStatus.UNKNOWN,
            ),
        ) as TransactionAssembler.Outcome.Assembled

        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, outcome.transaction.type)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            outcome.transaction.sourceContainerId,
        )
        assertNull(outcome.transaction.destinationContainerId)
        assertFalse(outcome.transaction.type == FinancialTransactionType.EXPENSE)
    }

    @Test
    fun unmatchedIncoming_ownedDest_unknownSource_externalIn() {
        val outcome = TransactionAssembler.assembleUnmatchedOwnedTransfer(
            candidate = transferCandidate(
                event = event(
                    family = MessageFamily.TRANSFER_IN,
                    amount = money("200.00"),
                    source = AccountReference(Bank.UNKNOWN, "9999"),
                    destination = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                ),
                sourceOwnership = OwnershipStatus.UNKNOWN,
                destinationOwnership = OwnershipStatus.OWNED,
            ),
        ) as TransactionAssembler.Outcome.Assembled

        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_IN, outcome.transaction.type)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            outcome.transaction.destinationContainerId,
        )
        assertNull(outcome.transaction.sourceContainerId)
        assertFalse(outcome.transaction.type == FinancialTransactionType.INCOME)
    }

    @Test
    fun unmatchedOutgoing_unownedSource_staysPending() {
        val outcome = TransactionAssembler.assembleUnmatchedOwnedTransfer(
            candidate = transferCandidate(
                event = event(
                    family = MessageFamily.TRANSFER_OUT,
                    amount = money("500.00"),
                    source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                    destination = AccountReference(Bank.UNKNOWN, "0593"),
                ),
                sourceOwnership = OwnershipStatus.UNKNOWN,
                destinationOwnership = OwnershipStatus.UNKNOWN,
            ),
        )
        assertTrue(outcome is TransactionAssembler.Outcome.PendingMatch)
    }

    @Test
    fun bankUnknown_referenceDoesNotPersistDurableContainerId() {
        val outcome = TransactionAssembler.assembleSingle(
            event = event(
                family = MessageFamily.FEE,
                amount = money("1.00"),
                source = AccountReference(Bank.UNKNOWN, "6810"),
            ),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.UNKNOWN,
        ) as TransactionAssembler.Outcome.Assembled

        assertNull(outcome.transaction.sourceContainerId)
        assertNull(outcome.transaction.destinationContainerId)
        assertFalse(
            listOfNotNull(
                outcome.transaction.sourceContainerId,
                outcome.transaction.destinationContainerId,
            ).any { it.contains("UNKNOWN") },
        )
    }

    @Test
    fun reviewRequiredPurchaseWithAmount_needsReview_notExpense() {
        val outcome = TransactionAssembler.assembleSingle(
            event = event(
                family = MessageFamily.PURCHASE,
                amount = money("51.99"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                status = ParseStatus.REVIEW_REQUIRED,
            ),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.OWNED,
        )
        assertEquals(
            TransactionAssembler.Outcome.NeedsReview(listOf("parse_review_required")),
            outcome,
        )
    }

    @Test
    fun nonSuccessStatuses_mapToDurableReasons_andNeverAssemble() {
        val expected = mapOf(
            ParseStatus.PARTIAL to listOf("parse_partial"),
            ParseStatus.INVALID to listOf("invalid_parsed_event"),
            ParseStatus.UNSUPPORTED to listOf("unsupported_bank_message_format"),
            ParseStatus.REVIEW_REQUIRED to listOf("parse_review_required", "missing_amount"),
        )
        for ((status, reasons) in expected) {
            val outcome = TransactionAssembler.assembleSingle(
                event = event(
                    family = MessageFamily.FEE,
                    amount = if (status == ParseStatus.REVIEW_REQUIRED) null else money("1.00"),
                    source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                    status = status,
                ),
                receivedAt = receivedAt,
                sourceOwnership = OwnershipStatus.OWNED,
                destinationOwnership = OwnershipStatus.UNKNOWN,
                cardOwnership = OwnershipStatus.UNKNOWN,
            )
            assertEquals(TransactionAssembler.Outcome.NeedsReview(reasons), outcome, "status=$status")
        }
    }

    @Test
    fun reviewRequiredTransfer_isNotPendingMatch() {
        val outcome = TransactionAssembler.assembleSingle(
            event = event(
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                status = ParseStatus.REVIEW_REQUIRED,
            ),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.OWNED,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.UNKNOWN,
        )
        assertTrue(outcome is TransactionAssembler.Outcome.NeedsReview)
    }

    @Test
    fun nonFinancialStatus_isIgnored() {
        val outcome = TransactionAssembler.assembleSingle(
            event = event(
                family = MessageFamily.PURCHASE,
                amount = money("51.99"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                status = ParseStatus.NON_FINANCIAL,
            ),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.OWNED,
        )
        assertEquals(TransactionAssembler.Outcome.Ignored, outcome)
    }

    @Test
    fun userConfirmedReviewRequiredPurchase_assemblesExpense() {
        val outcome = TransactionAssembler.assembleSingle(
            event = event(
                family = MessageFamily.PURCHASE,
                amount = money("51.99"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                status = ParseStatus.REVIEW_REQUIRED,
            ),
            receivedAt = receivedAt,
            sourceOwnership = OwnershipStatus.UNKNOWN,
            destinationOwnership = OwnershipStatus.UNKNOWN,
            cardOwnership = OwnershipStatus.OWNED,
            userConfirmed = true,
        ) as TransactionAssembler.Outcome.Assembled
        assertEquals(FinancialTransactionType.EXPENSE, outcome.transaction.type)
    }

    @Test
    fun automationEligibility_onlySuccessOrUserConfirmed() {
        for (status in ParseStatus.entries) {
            val e = event(family = MessageFamily.PURCHASE, amount = money("1.00"), status = status)
            assertEquals(status == ParseStatus.SUCCESS, TransactionAssembler.isAutomationEligible(e))
            assertTrue(TransactionAssembler.isAutomationEligible(e, userConfirmed = true))
        }
    }

    private fun money(v: String) = Money.of(BigDecimal(v), Currency.SAR)

    private fun transferCandidate(
        event: ParsedEvent,
        sourceOwnership: OwnershipStatus,
        destinationOwnership: OwnershipStatus,
    ) = TransferMatchCandidate(
        event = event,
        transactionReference = null,
        occurredAtLocal = null,
        receivedAt = receivedAt,
        sourceOwnership = sourceOwnership,
        destinationOwnership = destinationOwnership,
    )

    private fun event(
        family: MessageFamily,
        amount: Money?,
        source: AccountReference? = null,
        destination: AccountReference? = null,
        card: CardReference? = null,
        channel: PurchaseChannel? = null,
        status: ParseStatus = ParseStatus.SUCCESS,
        id: String = "pe-1",
        rawSmsId: String = "sms-1",
        bankNetworkType: BankNetworkType? = null,
    ) = ParsedEvent(
        id = id,
        rawSmsId = rawSmsId,
        bank = Bank.BANK_ALJAZIRA,
        messageFamily = family,
        direction = MoneyDirection.OUTGOING,
        amount = amount,
        purchaseChannel = channel,
        sourceAccountRef = source,
        destinationAccountRef = destination,
        cardRef = card,
        merchant = null,
        counterparty = null,
        occurredAt = receivedAt,
        bankNetworkType = bankNetworkType,
        confidence = Confidence(1.0),
        parseStatus = status,
    )
}
