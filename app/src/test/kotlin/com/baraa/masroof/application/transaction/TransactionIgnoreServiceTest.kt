package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionIgnoreServiceTest {
    private lateinit var db: MasroofDatabase
    private lateinit var ftRepo: RoomFinancialTransactionRepository
    private lateinit var parsedRepo: RoomParsedEventRepository
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var reviewRepo: RoomReviewRepository
    private lateinit var service: TransactionIgnoreService
    private lateinit var reconciliation: TransactionReconciliationService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        reviewRepo = RoomReviewRepository(db.reviewItemDao())
        val clock = InstantClock { Instant.parse("2026-08-02T12:00:00Z") }
        service = TransactionIgnoreService(
            persistence = com.baraa.masroof.data.repository.RoomTransactionIgnoreRepository(db),
            clock = clock,
        )
        reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = com.baraa.masroof.domain.ownership.OwnershipResolver(
                com.baraa.masroof.data.repository.RoomAccountRegistryRepository.from(db),
                com.baraa.masroof.data.repository.RoomCardRegistryRepository.from(db),
                com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository,
            ),
            reviewRepository = reviewRepo,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun singleSmsTransaction_isDeletedAndMarkedNonFinancial() = runBlocking {
        persistExpense(smsId = "sms-buy", amount = "120.00")
        val txId = TransactionIdFactory.fromRawSmsIds(listOf("sms-buy"))

        val result = service.ignore(txId)
        assertTrue(result is IgnoreResult.Success)
        assertNull(ftRepo.getById(txId))
        val review = reviewRepo.findByRawSmsId("sms-buy")!!
        assertEquals(ReviewStatus.RESOLVED, review.status)
        assertEquals(ReviewResolutionKind.USER_NON_FINANCIAL, review.resolutionKind)
        assertNull(review.resolvedTransactionId)
    }

    @Test
    fun ignoredTransaction_isNotRecreatedOnReconcile() = runBlocking {
        persistExpense(smsId = "sms-buy", amount = "120.00")
        val txId = TransactionIdFactory.fromRawSmsIds(listOf("sms-buy"))
        assertTrue(service.ignore(txId) is IgnoreResult.Success)

        reconciliation.reconcileStoredEvents()

        assertNull(ftRepo.findByRawSmsId("sms-buy"))
    }

    @Test
    fun pairedTransaction_isRejected() = runBlocking {
        listOf("sms-a", "sms-b").forEach { smsId ->
            rawRepo.insertIfAbsent(
                RawSms(
                    id = smsId,
                    sender = "AlJazira",
                    body = "body-$smsId",
                    bodyHash = SmsBodyHasher.sha256Hex("body-$smsId"),
                    receivedAt = Instant.parse("2026-08-01T10:00:00Z"),
                    deviceMessageId = smsId,
                ),
            )
        }
        val tx = FinancialTransaction(
            id = "tx-pair",
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = Money.of("100.00", Currency.SAR),
            occurredAt = Instant.parse("2026-08-01T10:00:00Z"),
            sourceContainerId = null,
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = emptyList(),
        )
        ftRepo.save(tx, listOf("sms-a", "sms-b"))
        val result = service.ignore("tx-pair")
        assertTrue(result is IgnoreResult.Rejected)
        assertEquals("paired_transaction_not_supported", (result as IgnoreResult.Rejected).reason)
    }

    @Test
    fun failureAfterDelete_rollsBackMovementLinksAndIgnoreDecision() = runBlocking {
        persistExpense("sms-atomic-ignore", "120.00")
        val id = TransactionIdFactory.fromRawSmsIds(listOf("sms-atomic-ignore"))
        val failing = TransactionIgnoreService(
            persistence = com.baraa.masroof.data.repository.RoomTransactionIgnoreRepository(db, afterDelete = {
                error("injected failure inside ignore transaction")
            }),
            clock = InstantClock { Instant.parse("2026-08-02T12:00:00Z") },
        )
        assertEquals(IgnoreResult.Rejected("ignore_failed"), failing.ignore(id))
        org.junit.Assert.assertNotNull(ftRepo.getById(id))
        assertEquals(listOf("sms-atomic-ignore"), ftRepo.listRawSmsIds(id))
        assertNull(reviewRepo.findByRawSmsId("sms-atomic-ignore"))
        assertTrue(service.ignore(id) is IgnoreResult.Success)
        reconciliation.reconcileStoredEvents()
        assertNull(ftRepo.getById(id))
    }

    private suspend fun persistExpense(smsId: String, amount: String) {
        val body = "purchase-$smsId"
        rawRepo.insertIfAbsent(
            RawSms(
                id = smsId,
                sender = "Jazira Bank",
                body = body,
                bodyHash = SmsBodyHasher.sha256Hex(body),
                receivedAt = Instant.parse("2026-08-02T10:00:00Z"),
                deviceMessageId = smsId,
            ),
        )
        val event = ParsedEvent(
            id = "pe-$smsId",
            rawSmsId = smsId,
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.PURCHASE,
            direction = MoneyDirection.OUTGOING,
            parseStatus = ParseStatus.SUCCESS,
            amount = Money.of(amount, Currency.SAR),
            occurredAt = Instant.parse("2026-08-02T10:00:00Z"),
            sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destinationAccountRef = null,
            cardRef = null,
            purchaseChannel = null,
            merchant = "STORE",
            counterparty = null,
            bankNetworkType = null,
            confidence = Confidence(1.0),
        )
        parsedRepo.save(event, ParsedEventDetails())
        val tx = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf(smsId)),
            type = FinancialTransactionType.EXPENSE,
            amount = Money.of(amount, Currency.SAR),
            occurredAt = event.occurredAt!!,
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = "STORE",
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf(event.id),
        )
        assertEquals(FinancialTransactionSaveResult.Saved, ftRepo.save(tx, listOf(smsId)))
    }
}
