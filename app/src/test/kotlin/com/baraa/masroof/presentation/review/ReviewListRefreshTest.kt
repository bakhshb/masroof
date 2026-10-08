package com.baraa.masroof.presentation.review

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.ReviewDetailLoader
import com.baraa.masroof.application.review.ReviewOwnershipWorkflow
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.transaction.TransactionReclassificationService
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.application.transaction.TransactionRestoreService
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomManualReviewResolutionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.util.concurrent.Executor

/**
 * Opening the review list and reloading it after a resolved action reads the queue.
 * Those paths do not run [ReviewWorkflowService.refreshReviewQueue].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReviewListRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = InstantClock { Instant.parse("2026-08-11T12:00:00Z") }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun refresh_readsTheQueueAndLeavesOlderHistoryUnposted() = runTest {
        World().use { world ->
            world.confirmCardOwned("7271")
            world.persistPurchase(smsId = "sms-old", eventId = "pe-old", amount = "19.00", last4 = "7271")
            world.requireReview("sms-review", "needs a person")
            world.parsed.reset()

            world.viewModel.refresh()
            advanceUntilIdle()

            assertEquals(listOf("sms-review"), world.viewModel.uiState.value.items.map { it.id.substringAfter("review:") })
            assertNull(world.viewModel.uiState.value.error)
            assertEquals(0, world.parsed.listAllCalls)
            assertNull(world.transactions.findByRawSmsId("sms-old"))
        }
    }

    @Test
    fun resolvedActionReload_doesNotRunFullReconciliation() = runTest {
        World().use { world ->
            world.confirmCardOwned("7271")
            world.persistPurchase(smsId = "sms-old", eventId = "pe-old", amount = "19.00", last4 = "7271")
            val reviewId = world.requireReview("sms-review", "needs a person")
            world.parsed.reset()
            val viewModel = world.viewModel

            viewModel.openDetail(reviewId)
            advanceUntilIdle()
            viewModel.resolveAsIgnored()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.items.isEmpty())
            assertEquals(ReviewMessage.RESOLVED, viewModel.uiState.value.message)
            assertNull(viewModel.uiState.value.error)
            assertEquals(0, world.parsed.listAllCalls)
            assertNull(world.transactions.findByRawSmsId("sms-old"))
        }
    }

    @Test
    fun dismissAllInformational_reloadsWithoutFullReconciliation() = runTest {
        World().use { world ->
            world.persistPurchase(
                smsId = "sms-notice",
                eventId = "pe-notice",
                amount = null,
                last4 = null,
                family = MessageFamily.NON_FINANCIAL,
            )
            world.requireReview("sms-notice", "non_financial_or_informational_message")
            world.persistPurchase(smsId = "sms-old", eventId = "pe-old", amount = "8.00", last4 = "7271")
            world.parsed.reset()

            world.viewModel.dismissAllInformational()
            advanceUntilIdle()

            assertTrue(world.viewModel.uiState.value.items.isEmpty())
            assertEquals(ReviewMessage.RESOLVED, world.viewModel.uiState.value.message)
            assertEquals(0, world.parsed.listAllCalls)
            assertNull(world.transactions.findByRawSmsId("sms-old"))
        }
    }

    @Test
    fun ownershipFollowUp_reconcilesTheCardWithoutFullHistory() = runTest {
        World().use { world ->
            world.persistPurchase(smsId = "sms-owned", eventId = "pe-owned", amount = "12.00", last4 = "7271")
            world.persistPurchase(smsId = "sms-other", eventId = "pe-other", amount = "9.00", last4 = "1111")
            val reviewId = world.requireReview("sms-owned", "purchase_instrument_ownership_unknown")
            world.parsed.reset()
            val viewModel = world.viewModel

            viewModel.openDetail(reviewId)
            advanceUntilIdle()
            assertEquals("7271", viewModel.uiState.value.selectedDetail?.ownershipCard?.last4)
            viewModel.confirmOwnershipCardOwned()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.error)
            assertEquals(0, world.parsed.listAllCalls)
            assertEquals(com.baraa.masroof.domain.model.FinancialTransactionType.EXPENSE, world.transactions.findByRawSmsId("sms-owned")!!.type)
            assertNull(world.transactions.findByRawSmsId("sms-other"))
        }
    }

    @Test
    fun restoreFollowUp_doesNotRunFullReconciliation() = runTest {
        World().use { world ->
            world.confirmCardOwned("7271")
            world.persistPurchase(smsId = "sms-buy", eventId = "pe-buy", amount = "40.00", last4 = "7271")
            world.persistPurchase(smsId = "sms-old", eventId = "pe-old", amount = "9.00", last4 = "1111")
            val reviewId = world.requireReview("sms-buy", "user_ignored_transaction")
            world.reviewRepo.markResolved(
                id = reviewId,
                resolutionKind = ReviewResolutionKind.USER_NON_FINANCIAL,
                resolvedAt = clock.now(),
                resolvedTransactionId = null,
            )
            world.parsed.reset()
            val viewModel = world.viewModel

            viewModel.openDetail(reviewId)
            advanceUntilIdle()
            viewModel.restoreIgnoredMessage()
            advanceUntilIdle()

            assertEquals(ReviewMessage.RESTORED, viewModel.uiState.value.message)
            assertNull(viewModel.uiState.value.error)
            assertEquals(0, world.parsed.listAllCalls)
            assertEquals(com.baraa.masroof.domain.model.FinancialTransactionType.EXPENSE, world.transactions.findByRawSmsId("sms-buy")!!.type)
            assertNull(world.transactions.findByRawSmsId("sms-old"))
        }
    }

    @Test
    fun correctionFollowUp_readsTheQueueWithoutFullReconciliation() = runTest {
        World().use { world ->
            world.confirmCardOwned("7271")
            world.persistPurchase(smsId = "sms-buy", eventId = "pe-buy", amount = null, last4 = "7271")
            world.persistPurchase(smsId = "sms-old", eventId = "pe-old", amount = "9.00", last4 = "1111")
            val reviewId = world.requireReview("sms-buy", "missing_amount")
            world.parsed.reset()

            val corrected = world.workflow.applyCorrection(
                reviewId = reviewId,
                correctedAmount = Money.of("51.99", Currency.SAR),
            )
            val summaries = world.loader.loadSummaries()

            assertTrue(corrected is com.baraa.masroof.application.review.ReviewWorkflowResult.Success)
            assertTrue(summaries.none { it.review.rawSmsId == "sms-buy" && it.review.status.name == "REQUIRED" })
            assertEquals(0, world.parsed.listAllCalls)
            assertNull(world.transactions.findByRawSmsId("sms-old"))
        }
    }

    private class World : AutoCloseable {
        private val clock = InstantClock { Instant.parse("2026-08-11T12:00:00Z") }
        private val db: MasroofDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MasroofDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() })
            .build()
        private val rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        val parsed = CountingParsedEvents(RoomParsedEventRepository(db.parsedEventDao()))
        private val financial = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        val transactions = financial
        val reviewRepo = RoomReviewRepository(db.reviewItemDao())
        private val corrections = RoomUserCorrectionRepository(db.userCorrectionDao())
        private val accounts = RoomAccountRegistryRepository.from(db)
        private val cards = RoomCardRegistryRepository.from(db)
        private val loans = RoomLoanRegistryRepository.from(db)
        private val confirmation = OwnershipConfirmationService(accounts, cards, loans)
        private val resolver = OwnershipResolver(accounts, cards, loans)
        private val effective = EffectiveParsedEventProvider(parsed, corrections)
        private val reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsed,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = financial,
            ownershipResolver = resolver,
            ownershipConfirmationService = confirmation,
            effectiveParsedEventProvider = effective,
            reviewRepository = reviewRepo,
        )
        private val updater = ReviewQueueUpdater(reviewRepo, financial, clock)
        val workflow = ReviewWorkflowService(
            reviewRepository = reviewRepo,
            userCorrectionRepository = corrections,
            financialTransactionRepository = financial,
            rawSmsRepository = rawRepo,
            ownershipResolver = resolver,
            ownershipConfirmationService = confirmation,
            effectiveParsedEventProvider = effective,
            parsedEventRepository = parsed,
            reconciliationService = reconciliation,
            reviewQueueUpdater = updater,
            manualReviewResolutionRepository = RoomManualReviewResolutionRepository(db, financial),
            clock = clock,
        )
        val loader = ReviewDetailLoader(workflow, rawRepo, effective)
        val viewModel = ReviewViewModel(
            reviewWorkflowService = workflow,
            detailLoader = loader,
            reviewOwnershipWorkflow = ReviewOwnershipWorkflow(cards, confirmation),
            transactionRestoreService = TransactionRestoreService(
                reviewRepository = reviewRepo,
                financialTransactionRepository = financial,
                reconciliation = reconciliation,
                reclassification = TransactionReclassificationService(
                    financialTransactionRepository = financial,
                    effectiveParsedEventProvider = effective,
                    ownershipResolver = resolver,
                ),
                clock = clock,
                reviewQueueUpdater = updater,
            ),
            reparseStoredSms = {},
            appLocaleRepository = object : AppLocaleRepository {
                override fun getLanguageTag(): String = "en"
                override fun setLanguageTag(languageTag: String) = Unit
            },
        )

        suspend fun confirmCardOwned(last4: String) {
            confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, last4))
        }

        suspend fun requireReview(rawSmsId: String, reason: String): String {
            if (rawRepo.getById(rawSmsId) == null) {
                val body = "body-$rawSmsId"
                rawRepo.insertIfAbsent(
                    RawSms(
                        id = rawSmsId,
                        sender = "AlJazira",
                        body = body,
                        receivedAt = Instant.parse("2026-08-10T09:30:00Z"),
                        deviceMessageId = rawSmsId,
                        bodyHash = SmsBodyHasher.sha256Hex(body),
                    ),
                )
            }
            return reviewRepo.upsertRequired(
                rawSmsId = rawSmsId,
                kind = ReviewKind.NEEDS_REVIEW,
                reasons = listOf(reason),
                now = clock.now(),
            ).id
        }

        suspend fun persistPurchase(
            smsId: String,
            eventId: String,
            amount: String?,
            last4: String?,
            family: MessageFamily = MessageFamily.PURCHASE,
        ) {
            val body = "body-$smsId"
            rawRepo.insertIfAbsent(
                RawSms(
                    id = smsId,
                    sender = "AlJazira",
                    body = body,
                    receivedAt = Instant.parse("2019-01-01T10:00:00Z"),
                    deviceMessageId = smsId,
                    bodyHash = SmsBodyHasher.sha256Hex(body),
                ),
            )
            parsed.save(
                ParsedEvent(
                    id = eventId,
                    rawSmsId = smsId,
                    bank = Bank.BANK_ALJAZIRA,
                    messageFamily = family,
                    direction = MoneyDirection.OUTGOING,
                    amount = amount?.let { Money.of(it, Currency.SAR) },
                    purchaseChannel = null,
                    sourceAccountRef = null,
                    destinationAccountRef = null,
                    cardRef = last4?.let { CardReference(Bank.BANK_ALJAZIRA, it) },
                    merchant = "Shop",
                    counterparty = null,
                    occurredAt = null,
                    bankNetworkType = null,
                    confidence = Confidence(1.0),
                    parseStatus = ParseStatus.SUCCESS,
                ),
            )
        }

        override fun close() {
            db.close()
        }
    }

    private class CountingParsedEvents(
        private val delegate: ParsedEventRepository,
    ) : ParsedEventRepository by delegate {
        var listAllCalls: Int = 0

        fun reset() {
            listAllCalls = 0
        }

        override suspend fun listAll(): List<ParsedEventRecord> {
            listAllCalls += 1
            return delegate.listAll()
        }
    }
}
