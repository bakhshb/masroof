package com.baraa.masroof.instrumentation

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.sms.LiveSmsIntake
import com.baraa.masroof.application.sms.LiveSmsProcessingWorker
import com.baraa.masroof.application.sms.LiveSmsWorkScheduler
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomProcessingRetryRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.LoanRegistryRepository
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device proof of M17. The database is private to this test so the application
 * process's own startup worker cannot post the same SMS.
 *
 * The final [LiveSmsProcessingWorker] attempt fails, leaves a LIVE retry row, and
 * posts nothing. A new [LiveSmsIntake], as after process start, discovers that row.
 * A new worker instance then replays it into one transaction and clears the marker.
 */
@RunWith(AndroidJUnit4::class)
class LiveRetryRestartTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "live-retry-restart.db"
    private lateinit var db: MasroofDatabase

    @Before
    fun openDatabase() {
        context.deleteDatabase(dbName)
        db = Room.databaseBuilder(context, MasroofDatabase::class.java, dbName)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(dbName)
    }

    @Test(timeout = 120_000)
    fun failedFinancialProcessing_survivesWorkerRestart_andReplaysOnce() = runBlocking {
        val rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        val parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        val ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        val reviewRepo = RoomReviewRepository(db.reviewItemDao())
        val retryRepo = RoomProcessingRetryRepository(db.processingRetryDao())
        val cards = RoomCardRegistryRepository.from(db)
        val accounts = RoomAccountRegistryRepository.from(db)
        val clock = InstantClock { Instant.parse("2026-08-11T12:00:00Z") }
        val appLog = AppLogService(context)
        val registry = BankSmsRegistry(listOf(AlJaziraSmsAdapter()))
        cards.setOwnership(CardReference(Bank.BANK_ALJAZIRA, "7271"), OwnershipStatus.OWNED)
        val conflicts = AtomicInteger(Int.MAX_VALUE)
        val failingLedger = object : FinancialTransactionRepository by ftRepo {
            override suspend fun save(
                transaction: FinancialTransaction,
                rawSmsIds: Collection<String>,
            ): FinancialTransactionSaveResult {
                if (conflicts.getAndDecrement() > 0) {
                    return FinancialTransactionSaveResult.Conflict(
                        rawSmsId = rawSmsIds.firstOrNull().orEmpty(),
                        existingTransactionId = "device-conflict",
                    )
                }
                return ftRepo.save(transaction, rawSmsIds)
            }
        }
        val raw = AndroidSmsMapper.toRawSms(
            ProviderSmsRecord(null, "AlJazira", PURCHASE_BODY, Instant.parse("2026-08-03T14:32:00Z")),
        )
        val captured = CaptureBankSmsUseCase(rawRepo, registry).capture(raw)
        assertTrue(captured is com.baraa.masroof.application.ingestion.BankSmsCaptureResult.Captured)

        val exhausted = worker(
            raw.id,
            LiveSmsProcessingWorker.MAX_ATTEMPTS - 1,
            processStored(rawRepo, parsedRepo, failingLedger, reviewRepo, retryRepo, cards, accounts, clock, appLog, registry),
        ).doWork()
        assertEquals(ListenableWorker.Result.failure(), exhausted)
        assertNull(ftRepo.findByRawSmsId(raw.id))
        assertEquals(listOf(raw.id), retryRepo.listRetryableRawSmsIds(ProcessingRetryMode.LIVE))

        val scheduled = mutableListOf<String>()
        val restarted = LiveSmsIntake(
            captureBankSms = CaptureBankSmsUseCase(rawRepo, registry),
            scheduler = LiveSmsWorkScheduler { scheduled += it },
            rawSmsRepository = rawRepo,
            reviewRepository = reviewRepo,
            processingRetryRepository = retryRepo,
            appLogService = appLog,
        )
        assertEquals(1, restarted.schedulePendingProcessing())
        assertEquals(listOf(raw.id), scheduled)

        conflicts.set(0)
        val replay = worker(
            raw.id,
            attempt = 0,
            processStored(rawRepo, parsedRepo, failingLedger, reviewRepo, retryRepo, cards, accounts, clock, appLog, registry),
        ).doWork()
        assertEquals(ListenableWorker.Result.success(), replay)
        val posted = ftRepo.findByRawSmsId(raw.id)
        assertTrue(posted != null)
        assertEquals(listOf(raw.id), ftRepo.listRawSmsIds(posted!!.id))
        assertEquals(1, db.financialTransactionDao().count())
        assertTrue(retryRepo.listRetryableRawSmsIds(ProcessingRetryMode.LIVE).isEmpty())
    }

    private fun processStored(
        rawRepo: RoomRawSmsRepository,
        parsedRepo: RoomParsedEventRepository,
        ledger: FinancialTransactionRepository,
        reviewRepo: RoomReviewRepository,
        retryRepo: RoomProcessingRetryRepository,
        cards: RoomCardRegistryRepository,
        accounts: RoomAccountRegistryRepository,
        clock: InstantClock,
        appLog: AppLogService,
        registry: BankSmsRegistry,
    ): ProcessStoredSmsUseCase {
        val loans = EmptyLoanRegistry
        val resolver = OwnershipResolver(accounts, cards, loans)
        val ingestionReview = IngestionReviewService(reviewRepo, clock)
        return ProcessStoredSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = registry,
            ownershipDiscovery = OwnershipDiscoveryService(accounts, cards, loans),
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = parsedRepo,
                rawSmsRepository = rawRepo,
                financialTransactionRepository = ledger,
                ownershipResolver = resolver,
                effectiveParsedEventProvider = EffectiveParsedEventProvider(
                    parsedRepo,
                    RoomUserCorrectionRepository(db.userCorrectionDao()),
                ),
                reviewRepository = reviewRepo,
            ),
            reviewQueueUpdater = ReviewQueueUpdater(reviewRepo, ledger, clock),
            ingestionReviewService = ingestionReview,
            appLogService = appLog,
            processingRecovery = com.baraa.masroof.application.ingestion.ProcessingRecovery(
                processingRetryRepository = retryRepo,
                reviewRepository = reviewRepo,
                ingestionReviewService = ingestionReview,
                clock = clock,
            ),
            reviewRepository = reviewRepo,
        )
    }

    private fun worker(
        rawSmsId: String,
        attempt: Int,
        processStored: ProcessStoredSmsUseCase,
    ): LiveSmsProcessingWorker =
        TestListenableWorkerBuilder<LiveSmsProcessingWorker>(context)
            .setInputData(LiveSmsProcessingWorker.inputFor(rawSmsId))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(LiveSmsProcessingWorker.Factory({ processStored }, null))
            .build()

    private object EmptyLoanRegistry : LoanRegistryRepository {
        override suspend fun observe(reference: LoanReference, rawSmsId: String) = Unit
        override suspend fun setOwnership(reference: LoanReference, status: OwnershipStatus) = Unit
        override suspend fun resolve(reference: LoanReference): OwnershipStatus = OwnershipStatus.UNKNOWN
        override suspend fun get(reference: LoanReference): LoanRegistryEntry? = null
        override suspend fun listAll(): List<LoanRegistryEntry> = emptyList()
        override suspend fun updateDisplayName(reference: LoanReference, displayName: String?) = Unit
    }

    private companion object {
        val PURCHASE_BODY = """
            شراء عبر الانترنت
            بطاقة: 7271
            لدى: Keeta
            بمبلغ: 51.99 SAR
            في: 14:32 03-08-2026
        """.trimIndent()
    }
}
