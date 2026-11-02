package com.baraa.masroof.application.transaction

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.ownership.RegistryIdentity
import com.baraa.masroof.parsing.model.ParsedEventDetails
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionReconciliationServiceTest : ReconciliationTestFixture() {
    @Test
    fun purchaseWithStatementWording_isNotAutoIgnored() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        persistEvent(
            smsId = "sms-buy-statement",
            body = """
                شراء عبر نقاط البيع
                بمبلغ: 89.50 SAR
                المبلغ المستحق: 1,250.00 SAR
                تاريخ الاستحقاق: 25/08/2026
            """.trimIndent(),
            event = event(
                id = "pe-buy-statement",
                rawSmsId = "sms-buy-statement",
                family = MessageFamily.PURCHASE,
                amount = money("89.50"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(1, summary.assembledSingle)
        assertEquals(0, summary.ignored)
        assertEquals(FinancialTransactionType.EXPENSE, ftRepo.listAll().single().type)
    }

    @Test
    fun purchase_assemblesExpense() = runBlocking {
        persistEvent(
            smsId = "sms-buy",
            event = event(
                id = "pe-buy",
                rawSmsId = "sms-buy",
                family = MessageFamily.PURCHASE,
                amount = money("51.99"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                channel = PurchaseChannel.ONLINE,
                merchant = "Keeta",
            ),
        )
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(1, summary.assembledSingle)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.EXPENSE, tx.type)
        assertEquals(money("51.99"), tx.amount)
        assertEquals(FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "7271"), tx.sourceContainerId)
        assertNull(tx.categoryId)
    }

    @Test
    fun cardPayment_isCreditCardPayment_notExpense() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        persistEvent(
            smsId = "sms-ccp",
            event = event(
                id = "pe-ccp",
                rawSmsId = "sms-ccp",
                family = MessageFamily.CARD_PAYMENT,
                amount = money("200.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
        )
        reconciliation.reconcileStoredEvents()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.CREDIT_CARD_PAYMENT, tx.type)
        assertNotEquals(FinancialTransactionType.EXPENSE, tx.type)
        assertEquals(FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"), tx.sourceContainerId)
        assertEquals(FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "7271"), tx.destinationContainerId)
        // Registry entry exists; classification did not require inventing CardType.
        assertNotNull(cards.get(CardReference(Bank.BANK_ALJAZIRA, "7271")))
    }

    @Test
    fun purchasePlusCardPayment_onlyOneExpense() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        persistEvent(
            smsId = "sms-a",
            event = event(
                id = "pe-a",
                rawSmsId = "sms-a",
                family = MessageFamily.PURCHASE,
                amount = money("51.99"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                channel = PurchaseChannel.ONLINE,
            ),
        )
        persistEvent(
            smsId = "sms-b",
            event = event(
                id = "pe-b",
                rawSmsId = "sms-b",
                family = MessageFamily.CARD_PAYMENT,
                amount = money("51.99"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
            at = Instant.parse("2026-08-02T12:00:00Z"),
        )
        reconciliation.reconcileStoredEvents()
        val txs = ftRepo.listAll()
        assertEquals(2, txs.size)
        assertEquals(1, txs.count { it.type == FinancialTransactionType.EXPENSE })
        assertEquals(1, txs.count { it.type == FinancialTransactionType.CREDIT_CARD_PAYMENT })
    }

    @Test
    fun refund_notIncome_usesDestinationContainerId() = runBlocking {
        persistEvent(
            smsId = "sms-rf",
            event = event(
                id = "pe-rf",
                rawSmsId = "sms-rf",
                family = MessageFamily.REFUND,
                amount = money("10.00"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
        )
        reconciliation.reconcileStoredEvents()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.REFUND, tx.type)
        assertNotEquals(FinancialTransactionType.INCOME, tx.type)
        assertNull(tx.sourceContainerId)
        assertEquals(FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"), tx.destinationContainerId)
    }

    @Test
    fun cardOnlyRefund_usesCardDestinationContainerId() = runBlocking {
        persistEvent(
            smsId = "sms-rf-card",
            event = event(
                id = "pe-rf-card",
                rawSmsId = "sms-rf-card",
                family = MessageFamily.REFUND,
                amount = money("12.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
        )
        reconciliation.reconcileStoredEvents()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.REFUND, tx.type)
        assertNull(tx.sourceContainerId)
        assertEquals(FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "7271"), tx.destinationContainerId)
    }

    @Test
    fun withdrawal_notExpense() = runBlocking {
        persistEvent(
            smsId = "sms-wd",
            event = event(
                id = "pe-wd",
                rawSmsId = "sms-wd",
                family = MessageFamily.WITHDRAWAL,
                amount = money("100.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            ),
        )
        reconciliation.reconcileStoredEvents()
        assertEquals(FinancialTransactionType.CASH_WITHDRAWAL, ftRepo.listAll().single().type)
    }

    @Test
    fun fee_type() = runBlocking {
        persistEvent(
            smsId = "sms-fee",
            event = event(
                id = "pe-fee",
                rawSmsId = "sms-fee",
                family = MessageFamily.FEE,
                amount = money("2.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            ),
        )
        reconciliation.reconcileStoredEvents()
        assertEquals(FinancialTransactionType.FEE, ftRepo.listAll().single().type)
    }

    @Test
    fun wifeIntraBank_externalTransferIn() = runBlocking {
        val wife = AccountReference(Bank.BANK_ALJAZIRA, "wife1")
        val user = AccountReference(Bank.BANK_ALJAZIRA, "3001")
        confirmation.markAccountExternal(wife)
        confirmation.confirmAccountOwned(user)
        persistEvent(
            smsId = "sms-wife",
            event = event(
                id = "pe-wife",
                rawSmsId = "sms-wife",
                family = MessageFamily.TRANSFER_IN,
                amount = money("500.00"),
                source = wife,
                destination = user,
                network = BankNetworkType.INTRA_BANK,
            ),
        )
        reconciliation.reconcileStoredEvents()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_IN, tx.type)
        assertNotEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertNotEquals(FinancialTransactionType.INCOME, tx.type)
    }

    @Test
    fun singleEventOwnedToOwned_selfTransfer() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))
        persistEvent(
            smsId = "sms-self",
            event = event(
                id = "pe-self",
                rawSmsId = "sms-self",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("50.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3002"),
                network = BankNetworkType.INTRA_BANK,
            ),
        )
        reconciliation.reconcileStoredEvents()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"), tx.sourceContainerId)
        assertEquals(FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002"), tx.destinationContainerId)
        assertEquals(listOf("pe-self"), tx.linkedParsedEventIds)
    }

    @Test
    fun reconcileAffectedRawSmsIds_pairsSelfTransferWithinWindow() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3003"))
        persistEvent(
            smsId = "sms-out-inc",
            event = event(
                id = "pe-out-inc",
                rawSmsId = "sms-out-inc",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("4445.67"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
                counterparty = "براء بخش",
            ),
        )
        persistEvent(
            smsId = "sms-in-inc",
            event = event(
                id = "pe-in-inc",
                rawSmsId = "sms-in-inc",
                family = MessageFamily.TRANSFER_IN,
                amount = money("4445.67"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
            ),
        )
        reconciliation.reconcileAffectedRawSmsIds(listOf("sms-in-inc"))
        assertEquals(1, ftRepo.listAll().size)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(setOf("sms-in-inc", "sms-out-inc"), ftRepo.listRawSmsIds(tx.id).toSet())
    }

    @Test
    fun incomingMissingSource_ownedDestination_postsExternalIn() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        persistEvent(
            smsId = "sms-in-nosrc",
            event = event(
                id = "pe-in-nosrc",
                rawSmsId = "sms-in-nosrc",
                family = MessageFamily.TRANSFER_IN,
                amount = money("4445.67"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        reconciliation.reconcileStoredEvents()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_IN, tx.type)
        assertEquals(FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"), tx.destinationContainerId)
    }

    @Test
    fun unmatchedTransfer_unownedLocalSide_staysPending() = runBlocking {
        persistEvent(
            smsId = "sms-unowned",
            event = event(
                id = "pe-unowned",
                rawSmsId = "sms-unowned",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, ftRepo.listAll().size)
        assertTrue(summary.pendingMatch >= 1)
    }

    @Test
    fun aljaziraToD360Pair_oneSelfTransfer_doesNotMutateUnknownRegistry() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank("D360"), "6810"))
        val t = LocalDateTime.parse("2026-08-10T12:00:00")
        persistEvent(
            smsId = "sms-out",
            event = event(
                id = "pe-out",
                rawSmsId = "sms-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
            details = ParsedEventDetails(occurredAtLocal = t),
            at = Instant.parse("2026-08-10T09:00:00Z"),
        )
        persistEvent(
            smsId = "sms-in",
            event = event(
                id = "pe-in",
                rawSmsId = "sms-in",
                bank = Bank("D360"),
                family = MessageFamily.TRANSFER_IN,
                amount = money("500.00"),
                destination = AccountReference(Bank("D360"), "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
            details = ParsedEventDetails(occurredAtLocal = t.plusMinutes(1)),
            at = Instant.parse("2026-08-10T09:01:00Z"),
        )
        reconciliation.reconcileStoredEvents()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(2, tx.linkedParsedEventIds.size)
        assertEquals(FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"), tx.sourceContainerId)
        assertEquals(FinancialContainerIdFactory.accountId(Bank("D360"), "6810"), tx.destinationContainerId)
        assertFalse(tx.sourceContainerId!!.contains("UNKNOWN"))
        assertFalse(tx.destinationContainerId!!.contains("UNKNOWN"))
        assertNull(accounts.get(AccountReference(Bank.UNKNOWN, "6810")))
        assertEquals(OwnershipStatus.UNKNOWN, OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository).resolveAccount(AccountReference(Bank.UNKNOWN, "6810")))
        assertEquals(2, accounts.listAll().size)
        assertNull(FinancialContainerIdFactory.accountId(AccountReference(Bank.UNKNOWN, "6810")))
    }

    @Test
    fun reconciliation_isIdempotent() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))
        persistEvent(
            smsId = "sms-idemp",
            event = event(
                id = "pe-idemp",
                rawSmsId = "sms-idemp",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("50.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3002"),
            ),
        )
        reconciliation.reconcileStoredEvents()
        val first = ftRepo.listAll()
        reconciliation.reconcileStoredEvents()
        assertEquals(first, ftRepo.listAll())
        assertEquals(1, first.size)
    }

    @Test
    fun eventOrderIndependence_forMatchedPair() = runBlocking {
        val t = LocalDateTime.parse("2026-08-10T12:00:00")

        suspend fun seed(outFirst: Boolean): com.baraa.masroof.domain.model.FinancialTransaction {
            db.clearAllTables()
            confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
            confirmation.confirmAccountOwned(AccountReference(Bank("D360"), "6810"))
            suspend fun out() {
                persistEvent(
                    smsId = "sms-out",
                    event = event(
                        id = "pe-out",
                        rawSmsId = "sms-out",
                        family = MessageFamily.TRANSFER_OUT,
                        amount = money("500.00"),
                        source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                        destination = AccountReference(Bank.UNKNOWN, "6810"),
                    ),
                    details = ParsedEventDetails(occurredAtLocal = t, transactionReference = "REF1"),
                )
            }
            suspend fun inn() {
                persistEvent(
                    smsId = "sms-in",
                    event = event(
                        id = "pe-in",
                        rawSmsId = "sms-in",
                        bank = Bank("D360"),
                        family = MessageFamily.TRANSFER_IN,
                        amount = money("500.00"),
                        destination = AccountReference(Bank("D360"), "6810"),
                    ),
                    details = ParsedEventDetails(
                        occurredAtLocal = t.plusMinutes(1),
                        transactionReference = "REF1",
                    ),
                    at = Instant.parse("2026-08-10T09:01:00Z"),
                )
            }
            if (outFirst) {
                out(); inn()
            } else {
                inn(); out()
            }
            reconciliation.reconcileStoredEvents()
            return ftRepo.listAll().single()
        }

        val a = seed(true)
        val b = seed(false)
        assertEquals(a.id, b.id)
        assertEquals(a.type, b.type)
        assertEquals(a.amount, b.amount)
        assertEquals(a.sourceContainerId, b.sourceContainerId)
        assertEquals(a.destinationContainerId, b.destinationContainerId)
        assertEquals(a.linkedParsedEventIds.sorted(), b.linkedParsedEventIds.sorted())
    }

    @Test
    fun batchPass_matchesIncrementalPerEventReconciliation() = runBlocking {
        val t = LocalDateTime.parse("2026-08-10T12:00:00")
        val own3001 = AccountReference(Bank.BANK_ALJAZIRA, "3001")
        val own3003 = AccountReference(Bank.BANK_ALJAZIRA, "3003")
        val batch = listOf(
            Triple(
                event("pe-b-out", "sms-b-out", MessageFamily.TRANSFER_OUT, money("700.00"), source = own3001, destination = own3003),
                ParsedEventDetails(occurredAtLocal = t),
                Instant.parse("2026-08-10T09:00:00Z"),
            ),
            Triple(
                event("pe-b-in", "sms-b-in", MessageFamily.TRANSFER_IN, money("700.00"), source = own3001, destination = own3003),
                ParsedEventDetails(occurredAtLocal = t),
                Instant.parse("2026-08-10T09:00:30Z"),
            ),
            Triple(
                event(
                    "pe-b-buy",
                    "sms-b-buy",
                    MessageFamily.PURCHASE,
                    money("51.99"),
                    card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                    merchant = "Keeta",
                ),
                ParsedEventDetails(occurredAtLocal = t.plusHours(1)),
                Instant.parse("2026-08-10T10:00:00Z"),
            ),
            Triple(
                event("pe-b-ext", "sms-b-ext", MessageFamily.TRANSFER_OUT, money("120.00"), source = own3001, counterparty = "TEST_PERSON"),
                ParsedEventDetails(occurredAtLocal = t.plusHours(2)),
                Instant.parse("2026-08-10T11:00:00Z"),
            ),
        )

        suspend fun seedAndReconcile(incremental: Boolean): List<com.baraa.masroof.domain.model.FinancialTransaction> {
            db.clearAllTables()
            confirmation.confirmAccountOwned(own3001)
            confirmation.confirmAccountOwned(own3003)
            confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
            for ((event, details, at) in batch) {
                persistEvent(smsId = event.rawSmsId, event = event, details = details, at = at)
                if (incremental) reconciliation.reconcileAffectedRawSmsIds(listOf(event.rawSmsId))
            }
            if (incremental) {
                reconciliation.reconcileStoredEvents()
                reconciliation.reconcileStoredEvents()
            } else {
                reconciliation.reconcileAffectedRawSmsIds(rawRepo.listIdsByReceivedAt())
            }
            return ftRepo.listAll().sortedBy { it.id }
        }

        val perEvent = seedAndReconcile(incremental = true)
        val batched = seedAndReconcile(incremental = false)

        assertEquals(perEvent, batched)
        assertEquals(
            setOf(
                FinancialTransactionType.EXPENSE,
                FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
                FinancialTransactionType.SELF_TRANSFER,
            ),
            batched.map { it.type }.toSet(),
        )
    }

    @Test
    fun parseReprocessing_keepsTransactionLinkedToCurrentEvent() = runBlocking {
        persistEvent(
            smsId = "sms-re",
            event = event(
                id = "pe-e1",
                rawSmsId = "sms-re",
                family = MessageFamily.PURCHASE,
                amount = money("11.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                channel = PurchaseChannel.POS,
            ),
        )
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        reconciliation.reconcileStoredEvents()
        val txId = ftRepo.findByRawSmsId("sms-re")!!.id

        parsedRepo.save(
            event(
                id = "pe-e2",
                rawSmsId = "sms-re",
                family = MessageFamily.PURCHASE,
                amount = money("11.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                channel = PurchaseChannel.ONLINE,
                merchant = "Updated",
            ),
        )
        val tx = ftRepo.getById(txId)!!
        assertEquals(listOf("pe-e2"), tx.linkedParsedEventIds)
        assertEquals(1, ftRepo.listAll().size)
    }

    @Test
    fun billPayment_autoAssembles() = runBlocking {
        persistEvent(
            smsId = "sms-bill",
            event = event(
                id = "pe-bill",
                rawSmsId = "sms-bill",
                family = MessageFamily.BILL_PAYMENT,
                amount = money("80.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            ),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(1, ftRepo.listAll().size)
        assertEquals(FinancialTransactionType.BILL_PAYMENT, ftRepo.listAll().single().type)
        assertEquals(0, summary.needsReview)
    }

    @Test
    fun containerIds_areBankScopedAndNamespaced() {
        val a1 = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val a2 = FinancialContainerIdFactory.accountId(Bank("D360"), "3001")
        val c1 = FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "3001")
        assertNotEquals(a1, a2)
        assertNotEquals(a1, c1)
        assertEquals(a1, FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"))
        assertNull(FinancialContainerIdFactory.accountId(AccountReference(Bank.UNKNOWN, "6810")))
        try {
            FinancialContainerIdFactory.accountId(Bank.UNKNOWN, "6810")
            fail("expected IllegalArgumentException for Bank.UNKNOWN")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun transactionId_isDeterministicFromSortedRawSmsIds() {
        val a = TransactionIdFactory.fromRawSmsIds(listOf("b", "a"))
        val b = TransactionIdFactory.fromRawSmsIds(listOf("a", "b"))
        assertEquals(a, b)
    }

    @Test
    fun bankUnknown_cannotBeConfirmed_still() = runBlocking {
        try {
            confirmation.confirmAccountOwned(AccountReference(Bank.UNKNOWN, "6810"))
            fail("expected")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(RegistryIdentity.isKnownBank(Bank.UNKNOWN))
    }

    @Test
    fun matchedUnknownBridge_doesNotPersistUnknownContainerId() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank("D360"), "6810"))
        val t = LocalDateTime.parse("2026-08-10T12:00:00")
        persistEvent(
            smsId = "sms-out-u",
            event = event(
                id = "pe-out-u",
                rawSmsId = "sms-out-u",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
            details = ParsedEventDetails(occurredAtLocal = t),
            at = Instant.parse("2026-08-10T09:00:00Z"),
        )
        persistEvent(
            smsId = "sms-in-u",
            event = event(
                id = "pe-in-u",
                rawSmsId = "sms-in-u",
                bank = Bank("D360"),
                family = MessageFamily.TRANSFER_IN,
                amount = money("500.00"),
                destination = AccountReference(Bank("D360"), "6810"),
                network = BankNetworkType.INTER_BANK,
            ),
            details = ParsedEventDetails(occurredAtLocal = t.plusMinutes(1)),
            at = Instant.parse("2026-08-10T09:01:00Z"),
        )
        reconciliation.reconcileStoredEvents()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals("account:D360:6810", tx.destinationContainerId)
        assertNotEquals("account:UNKNOWN:6810", tx.destinationContainerId)
        assertNotEquals("account:UNKNOWN:6810", tx.sourceContainerId)
    }

    @Test
    fun financingInstallmentFromOwnedAccount_autoConfirmsLoanAndAssemblesRepayment() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        loans.observe(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL), "sms-loan")
        val body = """
            خصم: قسط تمويل
            من: 3001
            القسط: SAR 3,036.11
            المبلغ المتبقي: SAR 33,397.25
            لـ: تمويل شخصي
            في: 2026-08-27 01:10
        """.trimIndent()
        persistEvent(
            smsId = "sms-loan",
            body = body,
            event = event(
                id = "evt-loan",
                rawSmsId = "sms-loan",
                family = MessageFamily.FINANCING_INSTALLMENT,
                amount = money("3036.11"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                counterparty = "تمويل شخصي",
            ),
            details = ParsedEventDetails(
                outstandingBalance = money("33397.25"),
                loanType = LoanType.PERSONAL,
            ),
            at = Instant.parse("2026-08-27T01:10:00Z"),
        )

        val summary = reconciliation.reconcileStoredEvents()

        assertEquals(1, summary.assembledSingle)
        assertEquals(OwnershipStatus.OWNED, loans.resolve(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)))
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.LOAN_REPAYMENT, tx.type)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            tx.sourceContainerId,
        )
        assertEquals(
            FinancialContainerIdFactory.loanId(Bank.BANK_ALJAZIRA, LoanType.PERSONAL),
            tx.destinationContainerId,
        )
    }

    @Test
    fun staleFeeFinancingInstallment_upgradesToLoanRepaymentOnReconcile() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        loans.observe(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL), "sms-loan")
        persistEvent(
            smsId = "sms-loan",
            body = """
                خصم: قسط تمويل
                من: 3001
                القسط: SAR 3,036.11
                لـ: تمويل شخصي
            """.trimIndent(),
            event = event(
                id = "evt-loan",
                rawSmsId = "sms-loan",
                family = MessageFamily.FINANCING_INSTALLMENT,
                amount = money("3036.11"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                counterparty = "تمويل شخصي",
            ),
            details = ParsedEventDetails(loanType = LoanType.PERSONAL),
            at = Instant.parse("2026-08-27T01:10:00Z"),
        )
        val staleFee = com.baraa.masroof.domain.model.FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf("sms-loan")),
            type = FinancialTransactionType.FEE,
            amount = money("3036.11"),
            occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = null,
            counterparty = "تمويل شخصي",
            categoryId = null,
            linkedParsedEventIds = listOf("evt-loan"),
        )
        ftRepo.save(staleFee, listOf("sms-loan"))

        reconciliation.reconcileStoredEvents()

        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.LOAN_REPAYMENT, tx.type)
        assertEquals(
            FinancialContainerIdFactory.loanId(Bank.BANK_ALJAZIRA, LoanType.PERSONAL),
            tx.destinationContainerId,
        )
    }

    @Test
    fun financingInstallment_doesNotReconfirmExternalLoan() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        loans.observe(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL), "sms-loan")
        confirmation.markLoanExternal(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL))
        persistEvent(
            smsId = "sms-loan",
            body = """
                خصم: قسط تمويل
                من: 3001
                القسط: SAR 3,036.11
                لـ: تمويل شخصي
            """.trimIndent(),
            event = event(
                id = "evt-loan",
                rawSmsId = "sms-loan",
                family = MessageFamily.FINANCING_INSTALLMENT,
                amount = money("3036.11"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                counterparty = "تمويل شخصي",
            ),
            details = ParsedEventDetails(loanType = LoanType.PERSONAL),
            at = Instant.parse("2026-08-27T01:10:00Z"),
        )

        reconciliation.reconcileStoredEvents()

        assertEquals(
            OwnershipStatus.EXTERNAL,
            loans.resolve(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)),
        )
    }


    /** A FEE row the heal cannot turn into a repayment is kept as is, not counted as a failure on every pass. */
    @Test
    fun staleFeeFinancingInstallment_withoutLoanType_staysFeeAndDoesNotFailReconciliation() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        persistEvent(
            smsId = "sms-loan",
            body = """
                خصم: قسط تمويل
                من: 3001
                القسط: SAR 3,036.11
            """.trimIndent(),
            event = event(
                id = "evt-loan",
                rawSmsId = "sms-loan",
                family = MessageFamily.FINANCING_INSTALLMENT,
                amount = money("3036.11"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            ),
            details = ParsedEventDetails(loanType = null),
            at = Instant.parse("2026-08-27T01:10:00Z"),
        )
        val staleFee = com.baraa.masroof.domain.model.FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf("sms-loan")),
            type = FinancialTransactionType.FEE,
            amount = money("3036.11"),
            occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("evt-loan"),
        )
        ftRepo.save(staleFee, listOf("sms-loan"))

        val repair = reconciliation.repairLegacyTransfersDetailed()
        val full = reconciliation.reconcileStoredEventsDetailed()

        assertEquals(0, repair.summary.failed)
        assertEquals(0, full.summary.failed)
        assertEquals(FinancialTransactionType.FEE, ftRepo.listAll().single().type)
    }

    /** Ownership is a write; it needs the same parse-status gate as posting. */
    @Test
    fun financingInstallmentNotYetValidated_doesNotConfirmLoanOwnership() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        loans.observe(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL), "sms-loan")
        persistEvent(
            smsId = "sms-loan",
            body = """
                خصم: قسط تمويل
                من: 3001
                القسط: SAR 3,036.11
                لـ: تمويل شخصي
            """.trimIndent(),
            event = event(
                id = "evt-loan",
                rawSmsId = "sms-loan",
                family = MessageFamily.FINANCING_INSTALLMENT,
                amount = money("3036.11"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                counterparty = "تمويل شخصي",
                status = ParseStatus.REVIEW_REQUIRED,
            ),
            details = ParsedEventDetails(loanType = LoanType.PERSONAL),
            at = Instant.parse("2026-08-27T01:10:00Z"),
        )

        reconciliation.reconcileStoredEvents()

        assertEquals(OwnershipStatus.UNKNOWN, loans.resolve(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)))
        assertTrue(ftRepo.listAll().isEmpty())
    }
}
