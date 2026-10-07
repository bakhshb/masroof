package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.matching.TransactionMatcher
import com.baraa.masroof.domain.matching.TransferMatchCandidate
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.ownership.RegistryIdentity
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.hash.SmsBodyHasher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionReconciliationServiceTest {

    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var parsedRepo: RoomParsedEventRepository
    private lateinit var ftRepo: RoomFinancialTransactionRepository
    private lateinit var accounts: RoomAccountRegistryRepository
    private lateinit var cards: RoomCardRegistryRepository
    private lateinit var loans: RoomLoanRegistryRepository
    private lateinit var confirmation: OwnershipConfirmationService
    private lateinit var reconciliation: TransactionReconciliationService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        accounts = RoomAccountRegistryRepository.from(db)
        cards = RoomCardRegistryRepository.from(db)
        loans = RoomLoanRegistryRepository.from(db)
        confirmation = OwnershipConfirmationService(accounts, cards, loans)
        reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, loans),
            ownershipConfirmationService = confirmation,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun staleOtpLinkedTransaction_isRemovedOnReconcile() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        val otpBody =
            """
            One Time Password for Online Purchase
            Code: 8811
            For: SAUDI ELECTRICITY COMPANY
            Amount: SAR 438.5
            Date: 2026-08-12 07:49
            """.trimIndent()
        persistEvent(
            smsId = "sms-otp-dup",
            body = otpBody,
            event = event(
                id = "pe-otp-dup",
                rawSmsId = "sms-otp-dup",
                family = MessageFamily.OTP,
                amount = null,
            ),
        )
        val staleTx = com.baraa.masroof.domain.model.FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf("sms-otp-dup")),
            type = FinancialTransactionType.EXPENSE,
            amount = money("438.50"),
            occurredAt = Instant.parse("2026-08-12T04:49:00Z"),
            sourceContainerId = FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, "7271"),
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-otp-dup"),
        )
        ftRepo.save(staleTx, listOf("sms-otp-dup"))
        assertEquals(1, ftRepo.listAll().size)

        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, summary.assembledSingle)
        assertEquals(1, summary.ignored)
        assertTrue(ftRepo.listAll().isEmpty())
        assertFalse(ftRepo.isRawSmsLinked("sms-otp-dup"))
    }

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
    fun unknownWithInformationalWording_staysInReview() = runBlocking {
        persistEvent(
            smsId = "sms-unknown-statement",
            body = """
                بطاقة إئتمانية: إصدار كشف حساب
                إجمالي المبلغ المستحق: SAR 0.00
                تاريخ الاستحقاق: 07/09/2026
            """.trimIndent(),
            event = event(
                id = "pe-unknown-statement",
                rawSmsId = "sms-unknown-statement",
                family = MessageFamily.UNKNOWN,
                amount = money("0.00"),
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, summary.ignored)
        assertEquals(0, summary.assembledSingle)
        assertEquals(1, summary.needsReview)
        assertTrue(ftRepo.listAll().isEmpty())
    }

    @Test
    fun linkedPurchase_isNotRemovedBecauseBodyHasInformationalWording() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        persistEvent(
            smsId = "sms-linked-buy",
            body = "شراء\nتاريخ الاستحقاق: 25/08/2026\nالمبلغ المستحق: 10 SAR",
            event = event(
                id = "pe-linked-buy",
                rawSmsId = "sms-linked-buy",
                family = MessageFamily.PURCHASE,
                amount = money("40.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
        )
        reconciliation.reconcileStoredEvents()
        assertEquals(1, ftRepo.listAll().size)

        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(1, summary.alreadyLinked)
        assertEquals(0, summary.ignored)
        assertEquals(1, ftRepo.listAll().size)
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
    fun duplicateOwnedToOwnedSmsLegs_absorbIntoSingleSelfTransfer() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3003"))
        persistEvent(
            smsId = "sms-out",
            event = event(
                id = "pe-out",
                rawSmsId = "sms-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("4445.67"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
                counterparty = "براء بخش",
            ),
        )
        persistEvent(
            smsId = "sms-in",
            event = event(
                id = "pe-in",
                rawSmsId = "sms-in",
                family = MessageFamily.TRANSFER_IN,
                amount = money("4445.67"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
            ),
        )
        reconciliation.reconcileStoredEvents()
        assertEquals(1, ftRepo.listAll().size)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(setOf("sms-in", "sms-out"), ftRepo.listRawSmsIds(tx.id).toSet())
    }

    @Test
    fun reconcileAfterParsedEvent_healsStaleExternalPairOutsideReceivedWindow() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        val occurredAt = LocalDateTime.parse("2026-08-01T12:00:00")
        val outAt = Instant.parse("2026-08-01T12:00:00Z")
        val inAt = outAt.plus(TransactionMatcher.TRANSFER_MATCH_WINDOW.multipliedBy(3))
        persistEvent(
            smsId = "sms-out-stale",
            at = outAt,
            details = ParsedEventDetails(occurredAtLocal = occurredAt),
            event = event(
                id = "pe-out-stale",
                rawSmsId = "sms-out-stale",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("4445.67"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
                counterparty = "براء بخش",
            ),
        )
        val outgoing = parsedRepo.findByRawSmsId("sms-out-stale")!!.event
        reconciliation.reconcileAfterParsedEvent(outgoing)
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, ftRepo.listAll().single().type)

        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3003"))
        persistEvent(
            smsId = "sms-in-stale",
            at = inAt,
            details = ParsedEventDetails(occurredAtLocal = occurredAt.plusMinutes(2)),
            event = event(
                id = "pe-in-stale",
                rawSmsId = "sms-in-stale",
                family = MessageFamily.TRANSFER_IN,
                amount = money("4445.67"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
            ),
        )
        val incoming = parsedRepo.findByRawSmsId("sms-in-stale")!!.event
        reconciliation.reconcileAfterParsedEvent(incoming)

        assertEquals(1, ftRepo.listAll().size)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(setOf("sms-in-stale", "sms-out-stale"), ftRepo.listRawSmsIds(tx.id).toSet())
    }

    @Test
    fun laterReferenceCounterpart_upgradesExternalWithoutDuplicating() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        val occurredAt = LocalDateTime.parse("2026-08-10T12:00:00")
        persistEvent(
            smsId = "sms-ref-out",
            at = Instant.parse("2026-08-10T09:00:00Z"),
            details = ParsedEventDetails(occurredAtLocal = occurredAt, transactionReference = "REF-HEAL"),
            event = event(
                id = "pe-ref-out",
                rawSmsId = "sms-ref-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("700.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        reconciliation.reconcileAfterParsedEvent(parsedRepo.findByRawSmsId("sms-ref-out")!!.event)
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, ftRepo.listAll().single().type)

        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3003"))
        persistEvent(
            smsId = "sms-ref-in",
            at = Instant.parse("2026-08-10T09:02:00Z"),
            details = ParsedEventDetails(occurredAtLocal = occurredAt.plusMinutes(1), transactionReference = "REF-HEAL"),
            event = event(
                id = "pe-ref-in",
                rawSmsId = "sms-ref-in",
                family = MessageFamily.TRANSFER_IN,
                amount = money("700.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        reconciliation.reconcileAfterParsedEvent(parsedRepo.findByRawSmsId("sms-ref-in")!!.event)

        assertEquals(1, ftRepo.listAll().size)
        val healed = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, healed.type)
        assertEquals(setOf("sms-ref-out", "sms-ref-in"), ftRepo.listRawSmsIds(healed.id).toSet())
        assertEquals(listOf("pe-ref-in", "pe-ref-out"), healed.linkedParsedEventIds.sorted())
    }

    @Test
    fun ambiguousReferenceCounterparts_areNotHealed() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3003"))
        val occurredAt = LocalDateTime.parse("2026-08-10T12:00:00")
        persistEvent(
            smsId = "sms-amb-out",
            at = Instant.parse("2026-08-10T09:00:00Z"),
            details = ParsedEventDetails(occurredAtLocal = occurredAt, transactionReference = "REF-AMB"),
            event = event(
                id = "pe-amb-out",
                rawSmsId = "sms-amb-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("700.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        reconciliation.reconcileStoredEvents()
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, ftRepo.listAll().single().type)

        persistEvent(
            smsId = "sms-amb-in-a",
            at = Instant.parse("2026-08-10T09:01:00Z"),
            details = ParsedEventDetails(occurredAtLocal = occurredAt, transactionReference = "REF-AMB"),
            event = event(
                id = "pe-amb-in-a",
                rawSmsId = "sms-amb-in-a",
                family = MessageFamily.TRANSFER_IN,
                amount = money("700.00"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        persistEvent(
            smsId = "sms-amb-in-b",
            at = Instant.parse("2026-08-10T09:01:30Z"),
            details = ParsedEventDetails(occurredAtLocal = occurredAt.plusMinutes(1), transactionReference = "REF-AMB"),
            event = event(
                id = "pe-amb-in-b",
                rawSmsId = "sms-amb-in-b",
                family = MessageFamily.TRANSFER_IN,
                amount = money("700.00"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTER_BANK,
            ),
        )
        reconciliation.reconcileStoredEvents()

        assertTrue(ftRepo.listAll().none { it.type == FinancialTransactionType.SELF_TRANSFER })
        val outgoing = ftRepo.findByRawSmsId("sms-amb-out")
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, outgoing?.type)
        assertEquals(listOf("sms-amb-out"), outgoing?.let { ftRepo.listRawSmsIds(it.id) })
    }

    @Test
    fun reconcileAfterParsedEvent_pairsSelfTransferWithinWindow() = runBlocking {
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
        val incoming = parsedRepo.findByRawSmsId("sms-in-inc")!!.event
        reconciliation.reconcileAfterParsedEvent(incoming)
        assertEquals(1, ftRepo.listAll().size)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
        assertEquals(setOf("sms-in-inc", "sms-out-inc"), ftRepo.listRawSmsIds(tx.id).toSet())
    }

    @Test
    fun outgoingUnknownWithoutCounterpart_postsExternalOut() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        persistEvent(
            smsId = "sms-unk",
            event = event(
                id = "pe-unk",
                rawSmsId = "sms-unk",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
                counterparty = "TEST_BENEFICIARY",
            ),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(1, ftRepo.listAll().size)
        assertEquals(0, summary.pendingMatch)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, tx.type)
        assertNotEquals(FinancialTransactionType.EXPENSE, tx.type)
        assertEquals(FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"), tx.sourceContainerId)
        assertNull(tx.destinationContainerId)
        assertEquals("TEST_BENEFICIARY", tx.counterparty)
    }

    @Test
    fun incomingUnknownWithoutCounterpart_postsExternalIn() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        persistEvent(
            smsId = "sms-in-unk",
            event = event(
                id = "pe-in-unk",
                rawSmsId = "sms-in-unk",
                family = MessageFamily.TRANSFER_IN,
                amount = money("200.00"),
                source = AccountReference(Bank.UNKNOWN, "9999"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                network = BankNetworkType.INTER_BANK,
                counterparty = "TEST_COMPANY",
            ),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, summary.pendingMatch)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_IN, tx.type)
        assertNotEquals(FinancialTransactionType.INCOME, tx.type)
        assertNull(tx.sourceContainerId)
        assertEquals(FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"), tx.destinationContainerId)
        assertEquals("TEST_COMPANY", tx.counterparty)
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
    fun matcher_requiresStrongBridge() {
        val out = candidate(
            id = "o",
            raw = "ro",
            family = MessageFamily.TRANSFER_OUT,
            amount = money("10.00"),
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.UNKNOWN, "9999"),
            sourceOwn = OwnershipStatus.OWNED,
            destOwn = OwnershipStatus.UNKNOWN,
            local = LocalDateTime.parse("2026-08-10T12:00:00"),
        )
        val inn = candidate(
            id = "i",
            raw = "ri",
            family = MessageFamily.TRANSFER_IN,
            amount = money("10.00"),
            destination = AccountReference(Bank("D360"), "6810"),
            sourceOwn = OwnershipStatus.UNKNOWN,
            destOwn = OwnershipStatus.OWNED,
            local = LocalDateTime.parse("2026-08-10T12:01:00"),
            bank = Bank("D360"),
        )
        assertTrue(TransactionMatcher.findMutuallyUniquePairs(listOf(out, inn)).isEmpty())
    }

    @Test
    fun matcher_outsideWindow_noMatch() {
        val out = candidate(
            id = "o",
            raw = "ro",
            family = MessageFamily.TRANSFER_OUT,
            amount = money("10.00"),
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.UNKNOWN, "6810"),
            sourceOwn = OwnershipStatus.OWNED,
            destOwn = OwnershipStatus.UNKNOWN,
            local = LocalDateTime.parse("2026-08-10T12:00:00"),
        )
        val inn = candidate(
            id = "i",
            raw = "ri",
            family = MessageFamily.TRANSFER_IN,
            amount = money("10.00"),
            destination = AccountReference(Bank("D360"), "6810"),
            sourceOwn = OwnershipStatus.UNKNOWN,
            destOwn = OwnershipStatus.OWNED,
            local = LocalDateTime.parse("2026-08-10T12:30:00"),
            bank = Bank("D360"),
        )
        assertTrue(TransactionMatcher.findMutuallyUniquePairs(listOf(out, inn)).isEmpty())
    }

    @Test
    fun matcher_ambiguousSameAmount_noAutoMatch() {
        val out = candidate(
            id = "o",
            raw = "ro",
            family = MessageFamily.TRANSFER_OUT,
            amount = money("500.00"),
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.UNKNOWN, "6810"),
            sourceOwn = OwnershipStatus.OWNED,
            destOwn = OwnershipStatus.UNKNOWN,
            local = LocalDateTime.parse("2026-08-10T12:00:00"),
            ref = "SAME",
        )
        val b = candidate(
            id = "b",
            raw = "rb",
            family = MessageFamily.TRANSFER_IN,
            amount = money("500.00"),
            destination = AccountReference(Bank("D360"), "6810"),
            sourceOwn = OwnershipStatus.UNKNOWN,
            destOwn = OwnershipStatus.OWNED,
            local = LocalDateTime.parse("2026-08-10T12:01:00"),
            bank = Bank("D360"),
            ref = "SAME",
        )
        val c = candidate(
            id = "c",
            raw = "rc",
            family = MessageFamily.TRANSFER_IN,
            amount = money("500.00"),
            destination = AccountReference(Bank("D360"), "6810"),
            sourceOwn = OwnershipStatus.UNKNOWN,
            destOwn = OwnershipStatus.OWNED,
            local = LocalDateTime.parse("2026-08-10T12:02:00"),
            bank = Bank("D360"),
            ref = "SAME",
        )
        assertTrue(TransactionMatcher.findMutuallyUniquePairs(listOf(out, b, c)).isEmpty())
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
                if (incremental) reconciliation.reconcileAfterParsedEvent(event)
            }
            if (incremental) {
                reconciliation.reconcileStoredEvents()
                reconciliation.reconcileStoredEvents()
            } else {
                reconciliation.reconcileBatchDetailed()
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
    fun batchPass_pairsReferenceBridgedLegs_likeOrderIndependentStoredPass() = runBlocking {
        val t = LocalDateTime.parse("2026-08-10T12:00:00")
        val own3001 = AccountReference(Bank.BANK_ALJAZIRA, "3001")
        val own3003 = AccountReference(Bank.BANK_ALJAZIRA, "3003")

        suspend fun seed() {
            db.clearAllTables()
            confirmation.confirmAccountOwned(own3001)
            confirmation.confirmAccountOwned(own3003)
            persistEvent(
                smsId = "sms-r-out",
                event = event("pe-r-out", "sms-r-out", MessageFamily.TRANSFER_OUT, money("700.00"), source = own3001),
                details = ParsedEventDetails(occurredAtLocal = t, transactionReference = "REF-R"),
                at = Instant.parse("2026-08-10T09:00:00Z"),
            )
            persistEvent(
                smsId = "sms-r-in",
                event = event("pe-r-in", "sms-r-in", MessageFamily.TRANSFER_IN, money("700.00"), destination = own3003),
                details = ParsedEventDetails(occurredAtLocal = t, transactionReference = "REF-R"),
                at = Instant.parse("2026-08-10T09:00:30Z"),
            )
        }

        seed()
        reconciliation.reconcileStoredEvents()
        val storedPass = ftRepo.listAll()
        seed()
        reconciliation.reconcileBatchDetailed()
        val batchPass = ftRepo.listAll()

        assertEquals(storedPass, batchPass)
        val selfTransfer = batchPass.single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, selfTransfer.type)
        assertEquals(listOf("pe-r-in", "pe-r-out"), selfTransfer.linkedParsedEventIds.sorted())
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
    fun unknownBeneficiaryNotice_staysInReview() = runBlocking {
        val body = """
            اسم المستفيد : براء ف بن
            الاسم المختصر : حسابي D360
            حالة: غير نشط
            حساب: SA2036036036045864332670
            بنك: D360 بنك
            في : 14:04 2026-07-29
        """.trimIndent()
        persistEvent(
            smsId = "sms-info",
            event = event(
                id = "pe-info",
                rawSmsId = "sms-info",
                family = MessageFamily.UNKNOWN,
                amount = null,
            ),
            body = body,
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, ftRepo.listAll().size)
        assertEquals(0, summary.ignored)
        assertEquals(1, summary.needsReview)
    }

    @Test
    fun parsedNonFinancialBeneficiary_isIgnoredWithoutReadingBody() = runBlocking {
        persistEvent(
            smsId = "sms-beneficiary",
            body = """
                اسم المستفيد : براء ف بن
                حالة: غير نشط
            """.trimIndent(),
            event = event(
                id = "pe-beneficiary",
                rawSmsId = "sms-beneficiary",
                family = MessageFamily.NON_FINANCIAL,
                amount = null,
            ),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, ftRepo.listAll().size)
        assertEquals(1, summary.ignored)
        assertEquals(0, summary.needsReview)
    }

    @Test
    fun unknownWithMoneyInBodyButNoParsedAmount_stillNeedsReview() = runBlocking {
        persistEvent(
            smsId = "sms-parse-fail",
            event = event(
                id = "pe-parse-fail",
                rawSmsId = "sms-parse-fail",
                family = MessageFamily.UNKNOWN,
                amount = null,
            ),
            body = "عملية غير معروفة بمبلغ: 15000.00 SAR",
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, ftRepo.listAll().size)
        assertTrue(summary.needsReview >= 1)
    }

    @Test
    fun balanceAndNonFinancial_ignored() = runBlocking {
        persistEvent(
            smsId = "sms-bal",
            event = event(
                id = "pe-bal",
                rawSmsId = "sms-bal",
                family = MessageFamily.BALANCE_NOTICE,
                amount = null,
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            ),
        )
        persistEvent(
            smsId = "sms-nf",
            event = event(
                id = "pe-nf",
                rawSmsId = "sms-nf",
                family = MessageFamily.NON_FINANCIAL,
                amount = null,
            ),
            at = Instant.parse("2026-08-02T00:00:00Z"),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, ftRepo.listAll().size)
        assertTrue(summary.ignored >= 2)
    }

    @Test
    fun missingAmount_needsReview() = runBlocking {
        persistEvent(
            smsId = "sms-na",
            event = event(
                id = "pe-na",
                rawSmsId = "sms-na",
                family = MessageFamily.PURCHASE,
                amount = null,
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            ),
        )
        val summary = reconciliation.reconcileStoredEvents()
        assertEquals(0, ftRepo.listAll().size)
        assertTrue(summary.needsReview >= 1)
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

    @Test
    fun reviewRequiredPurchaseWithAmount_neverBecomesExpense() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        persistEvent(
            smsId = "sms-rr-buy",
            event = event(
                id = "pe-rr-buy",
                rawSmsId = "sms-rr-buy",
                family = MessageFamily.PURCHASE,
                amount = money("51.99"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                channel = PurchaseChannel.ONLINE,
                merchant = "Keeta",
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        val report = reconciliation.reconcileStoredEventsDetailed()
        assertTrue(ftRepo.listAll().isEmpty())
        assertEquals(0, report.summary.assembledSingle)
        assertEquals(1, report.summary.needsReview)
        val candidate = report.reviewCandidates.single()
        assertEquals("sms-rr-buy", candidate.rawSmsId)
        assertEquals(ReviewKind.NEEDS_REVIEW, candidate.kind)
        assertTrue(candidate.reasons.contains("parse_review_required"))
        assertFalse("sms-rr-buy" in report.settledRawSmsIds)
    }

    @Test
    fun partialPurchase_neverAutoCreatesTransaction() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        persistEvent(
            smsId = "sms-partial",
            event = event(
                id = "pe-partial",
                rawSmsId = "sms-partial",
                family = MessageFamily.PURCHASE,
                amount = money("10.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                status = ParseStatus.PARTIAL,
            ),
        )
        val report = reconciliation.reconcileStoredEventsDetailed()
        assertTrue(ftRepo.listAll().isEmpty())
        assertTrue(report.reviewCandidates.single().reasons.contains("parse_partial"))
    }

    @Test
    fun reviewRequiredTransfers_areNotPairedOrPostedAsExternal() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3003"))
        persistEvent(
            smsId = "sms-rr-out",
            event = event(
                id = "pe-rr-out",
                rawSmsId = "sms-rr-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("4445.67"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        persistEvent(
            smsId = "sms-rr-in",
            event = event(
                id = "pe-rr-in",
                rawSmsId = "sms-rr-in",
                family = MessageFamily.TRANSFER_IN,
                amount = money("4445.67"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        persistEvent(
            smsId = "sms-rr-ext",
            event = event(
                id = "pe-rr-ext",
                rawSmsId = "sms-rr-ext",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("500.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.UNKNOWN, "6810"),
                network = BankNetworkType.INTER_BANK,
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        val report = reconciliation.reconcileStoredEventsDetailed()
        assertTrue(ftRepo.listAll().isEmpty())
        assertEquals(0, report.summary.matchedPairs)
        assertEquals(
            setOf("sms-rr-out", "sms-rr-in", "sms-rr-ext"),
            report.reviewCandidates.map { it.rawSmsId }.toSet(),
        )
        assertTrue(report.reviewCandidates.all { it.kind == ReviewKind.NEEDS_REVIEW })
        assertTrue(report.reviewCandidates.all { "parse_review_required" in it.reasons })
    }

    @Test
    fun reviewRequiredTransfer_doesNotUpgradeStaleExternalToSelfTransfer() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3003"))
        persistEvent(
            smsId = "sms-ok-out",
            event = event(
                id = "pe-ok-out",
                rawSmsId = "sms-ok-out",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("700.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
            ),
        )
        val staleExternal = com.baraa.masroof.domain.model.FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf("sms-ok-out")),
            type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            amount = money("700.00"),
            occurredAt = Instant.parse("2026-08-01T12:00:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-ok-out"),
        )
        ftRepo.save(staleExternal, listOf("sms-ok-out"))
        persistEvent(
            smsId = "sms-rr-in2",
            event = event(
                id = "pe-rr-in2",
                rawSmsId = "sms-rr-in2",
                family = MessageFamily.TRANSFER_IN,
                amount = money("700.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
                network = BankNetworkType.INTRA_BANK,
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        reconciliation.reconcileStoredEvents()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, tx.type)
        assertFalse(ftRepo.isRawSmsLinked("sms-rr-in2"))
    }

    @Test
    fun nonFinancialStatus_isIgnoredEvenWithFinancialFamily() = runBlocking {
        persistEvent(
            smsId = "sms-nf",
            event = event(
                id = "pe-nf",
                rawSmsId = "sms-nf",
                family = MessageFamily.FEE,
                amount = money("1.00"),
                source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                status = ParseStatus.NON_FINANCIAL,
            ),
        )
        val report = reconciliation.reconcileStoredEventsDetailed()
        assertTrue(ftRepo.listAll().isEmpty())
        assertTrue(report.reviewCandidates.isEmpty())
        assertEquals(1, report.summary.ignored)
    }

    @Test
    fun amountCorrection_reviewRequiredPurchase_isAssembled() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        val correctionRepo = com.baraa.masroof.data.repository.RoomUserCorrectionRepository(db.userCorrectionDao())
        val withCorrections = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, loans),
            ownershipConfirmationService = confirmation,
            effectiveParsedEventProvider = com.baraa.masroof.application.review.EffectiveParsedEventProvider(
                parsedRepo,
                correctionRepo,
            ),
        )
        persistEvent(
            smsId = "sms-rr-fix",
            event = event(
                id = "pe-rr-fix",
                rawSmsId = "sms-rr-fix",
                family = MessageFamily.PURCHASE,
                amount = null,
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        withCorrections.reconcileStoredEvents()
        assertTrue(ftRepo.listAll().isEmpty())
        correctionRepo.save(
            com.baraa.masroof.domain.model.UserCorrection(
                id = "corr-rr-fix",
                targetRawSmsId = "sms-rr-fix",
                correctedType = null,
                correctedAmount = money("42.00"),
                correctedMerchant = null,
                correctedCounterparty = null,
                createdAt = Instant.parse("2026-08-02T12:00:00Z"),
            ),
        )
        withCorrections.reconcileStoredEvents()
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.EXPENSE, tx.type)
        assertEquals(money("42.00"), tx.amount)
    }

    @Test
    fun merchantOnlyCorrection_doesNotLiftParseStatusGate() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        val correctionRepo = com.baraa.masroof.data.repository.RoomUserCorrectionRepository(db.userCorrectionDao())
        val withCorrections = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, loans),
            ownershipConfirmationService = confirmation,
            effectiveParsedEventProvider = com.baraa.masroof.application.review.EffectiveParsedEventProvider(
                parsedRepo,
                correctionRepo,
            ),
        )
        persistEvent(
            smsId = "sms-rr-merchant-only",
            event = event(
                id = "pe-rr-merchant-only",
                rawSmsId = "sms-rr-merchant-only",
                family = MessageFamily.PURCHASE,
                amount = money("42.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                merchant = "Original",
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        correctionRepo.save(
            com.baraa.masroof.domain.model.UserCorrection(
                id = "corr-rr-merchant-only",
                targetRawSmsId = "sms-rr-merchant-only",
                correctedType = null,
                correctedAmount = null,
                correctedMerchant = "Corrected Merchant",
                correctedCounterparty = null,
                createdAt = Instant.parse("2026-08-02T12:00:00Z"),
            ),
        )

        val report = withCorrections.reconcileStoredEventsDetailed()

        assertTrue(ftRepo.listAll().isEmpty())
        assertEquals(1, report.summary.needsReview)
        assertTrue(report.reviewCandidates.single().reasons.contains("parse_review_required"))
    }

    @Test
    fun restoreIgnoredReviewRequiredPurchase_assemblesExpense() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        val reviewRepo = com.baraa.masroof.data.repository.RoomReviewRepository(db.reviewItemDao())
        val correctionRepo = com.baraa.masroof.data.repository.RoomUserCorrectionRepository(db.userCorrectionDao())
        val effective = com.baraa.masroof.application.review.EffectiveParsedEventProvider(parsedRepo, correctionRepo)
        val resolver = OwnershipResolver(accounts, cards, loans)
        val withReviews = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = resolver,
            ownershipConfirmationService = confirmation,
            reviewRepository = reviewRepo,
            effectiveParsedEventProvider = effective,
        )
        val clock = com.baraa.masroof.sms.time.InstantClock { Instant.parse("2026-08-02T12:00:00Z") }
        persistEvent(
            smsId = "sms-rr-restore",
            event = event(
                id = "pe-rr-restore",
                rawSmsId = "sms-rr-restore",
                family = MessageFamily.PURCHASE,
                amount = money("51.99"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                merchant = "Keeta",
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
        val review = reviewRepo.upsertRequired(
            "sms-rr-restore",
            ReviewKind.NEEDS_REVIEW,
            listOf("parse_review_required"),
            clock.now(),
        )
        reviewRepo.markResolved(
            id = review.id,
            resolutionKind = com.baraa.masroof.domain.model.ReviewResolutionKind.USER_NON_FINANCIAL,
            resolvedAt = clock.now(),
            resolvedTransactionId = null,
        )
        withReviews.reconcileStoredEvents()
        assertTrue(ftRepo.listAll().isEmpty())

        val restore = TransactionRestoreService(
            reviewRepository = reviewRepo,
            financialTransactionRepository = ftRepo,
            reconciliation = withReviews,
            reclassification = TransactionReclassificationService(ftRepo, effective, resolver, confirmation),
            clock = clock,
        )
        val result = restore.restore("sms-rr-restore")

        assertTrue("restore result: $result", result is RestoreResult.Success)
        val tx = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.EXPENSE, tx.type)
        assertEquals(money("51.99"), tx.amount)
    }

    @Test
    fun reprocessInAnotherDeviceZone_keepsAlJaziraOccurredAt() = runBlocking {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
        val local = java.time.LocalDateTime.of(2026, 8, 26, 22, 30)
        persistEvent(
            smsId = "sms-zone",
            event = event(
                id = "pe-zone",
                rawSmsId = "sms-zone",
                family = MessageFamily.PURCHASE,
                amount = money("12.00"),
                card = CardReference(Bank.BANK_ALJAZIRA, "7271"),
                channel = PurchaseChannel.ONLINE,
                merchant = "Shop",
            ),
            details = ParsedEventDetails(occurredAtLocal = local),
        )
        val tokyo = serviceIn(java.time.ZoneId.of("Asia/Tokyo"))
        tokyo.reconcileStoredEvents()
        val first = ftRepo.findByRawSmsId("sms-zone")!!
        assertEquals(local.atZone(java.time.ZoneId.of("Asia/Riyadh")).toInstant(), first.occurredAt)
        assertEquals("Asia/Riyadh", first.occurredAtZone)

        val newYork = serviceIn(java.time.ZoneId.of("America/New_York"))
        newYork.reconcileStoredEvents()
        val again = ftRepo.findByRawSmsId("sms-zone")!!
        assertEquals(first.occurredAt, again.occurredAt)
        assertEquals("Asia/Riyadh", again.occurredAtZone)
    }

    @Test
    fun unknownBank_persistsFirstZoneAcrossReprocess() = runBlocking {
        val other = Bank("OTHER")
        confirmation.confirmCardOwned(CardReference(other, "1111"))
        val local = java.time.LocalDateTime.of(2026, 8, 26, 22, 30)
        persistEvent(
            smsId = "sms-other-zone",
            event = event(
                id = "pe-other-zone",
                rawSmsId = "sms-other-zone",
                family = MessageFamily.PURCHASE,
                amount = money("8.00"),
                bank = other,
                card = CardReference(other, "1111"),
                channel = PurchaseChannel.ONLINE,
            ),
            details = ParsedEventDetails(occurredAtLocal = local),
        )
        serviceIn(java.time.ZoneId.of("Asia/Tokyo")).reconcileStoredEvents()
        val first = ftRepo.findByRawSmsId("sms-other-zone")!!
        assertEquals("Asia/Tokyo", first.occurredAtZone)
        assertEquals(local.atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant(), first.occurredAt)

        serviceIn(java.time.ZoneId.of("America/New_York")).reconcileStoredEvents()
        val again = ftRepo.findByRawSmsId("sms-other-zone")!!
        assertEquals(first.occurredAt, again.occurredAt)
        assertEquals("Asia/Tokyo", again.occurredAtZone)
    }

    private fun serviceIn(zone: java.time.ZoneId) = TransactionReconciliationService(
        parsedEventRepository = parsedRepo,
        rawSmsRepository = rawRepo,
        financialTransactionRepository = ftRepo,
        ownershipResolver = com.baraa.masroof.domain.ownership.OwnershipResolver(accounts, cards, loans),
        ownershipConfirmationService = confirmation,
        zoneId = zone,
    )

    private suspend fun persistEvent(
        smsId: String,
        event: ParsedEvent,
        details: ParsedEventDetails = ParsedEventDetails(),
        at: Instant = Instant.parse("2026-08-01T12:00:00Z"),
        body: String = "body-$smsId",
    ) {
        rawRepo.insertIfAbsent(
            RawSms(
                id = smsId,
                sender = "AlJazira",
                body = body,
                receivedAt = at,
                deviceMessageId = smsId.removePrefix("sms-"),
                bodyHash = SmsBodyHasher.sha256Hex(body),
            ),
        )
        parsedRepo.save(event, details)
    }

    private fun money(v: String) = Money.of(BigDecimal(v), Currency.SAR)

    private fun event(
        id: String,
        rawSmsId: String,
        family: MessageFamily,
        amount: Money?,
        bank: Bank = Bank.BANK_ALJAZIRA,
        source: AccountReference? = null,
        destination: AccountReference? = null,
        card: CardReference? = null,
        network: BankNetworkType? = null,
        channel: PurchaseChannel? = null,
        merchant: String? = null,
        counterparty: String? = null,
        status: ParseStatus = ParseStatus.SUCCESS,
    ) = ParsedEvent(
        id = id,
        rawSmsId = rawSmsId,
        bank = bank,
        messageFamily = family,
        direction = MoneyDirection.OUTGOING,
        amount = amount,
        purchaseChannel = channel,
        sourceAccountRef = source,
        destinationAccountRef = destination,
        cardRef = card,
        merchant = merchant,
        counterparty = counterparty,
        occurredAt = null,
        bankNetworkType = network,
        confidence = Confidence(1.0),
        parseStatus = status,
    )

    private fun candidate(
        id: String,
        raw: String,
        family: MessageFamily,
        amount: Money,
        source: AccountReference? = null,
        destination: AccountReference? = null,
        sourceOwn: OwnershipStatus,
        destOwn: OwnershipStatus,
        local: LocalDateTime?,
        bank: Bank = Bank.BANK_ALJAZIRA,
        ref: String? = null,
    ) = TransferMatchCandidate(
        event = event(
            id = id,
            rawSmsId = raw,
            family = family,
            amount = amount,
            bank = bank,
            source = source,
            destination = destination,
        ),
        transactionReference = ref,
        occurredAtLocal = local,
        receivedAt = Instant.parse("2026-08-10T09:00:00Z"),
        sourceOwnership = sourceOwn,
        destinationOwnership = destOwn,
    )
}
