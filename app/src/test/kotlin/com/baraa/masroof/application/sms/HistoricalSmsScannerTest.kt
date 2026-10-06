package com.baraa.masroof.application.sms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessRawSmsUseCase
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.aljazira.AlJaziraParsingPipeline
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.parsing.fixtures.AlJaziraFixtureLoader
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.time.InstantClock
import java.time.LocalDateTime
import java.time.ZoneId
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.parsing.parser.SmsParseGateway
import com.baraa.masroof.sms.datasource.InboxRow
import com.baraa.masroof.sms.datasource.SmsDataSource
import com.baraa.masroof.sms.datasource.SmsPermissionException
import com.baraa.masroof.sms.datasource.SmsProviderException
import com.baraa.masroof.sms.model.ProviderSmsRecord
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
class HistoricalSmsScannerTest {

    private lateinit var db: MasroofDatabase
    private lateinit var batchProcessor: HistoricalSmsBatchProcessor

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        batchProcessor = batchProcessor(RoomRawSmsRepository(db.rawSmsDao()))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun processesOldestToNewest_andCountsOutcomes() = runBlocking {
        val t1 = Instant.parse("2026-08-01T10:00:00Z")
        val t2 = Instant.parse("2026-08-02T10:00:00Z")
        val purchase = purchaseBody()
        val otp = "رمز التحقق الخاص بك هو 482911. لا تشاركه مع أي شخص."
        val after = Instant.parse("2026-07-01T00:00:00Z")
        val order = mutableListOf<String>()
        val source = FakeSmsDataSource(
            rows = listOf(
                InboxRow.Valid(ProviderSmsRecord("1", "AlJazira", purchase, t1)),
                InboxRow.Valid(ProviderSmsRecord("2", "OtherBank", purchase, t1)),
                InboxRow.Valid(ProviderSmsRecord("3", "AlJazira", otp, t2)),
                InboxRow.Valid(ProviderSmsRecord("1", "AlJazira", purchase, t1)),
            ),
            onQuery = { receivedAfter -> order += "after=${receivedAfter?.toEpochMilli()}" },
        )
        val result = HistoricalSmsScanner(source, batchProcessor).scan(receivedAfter = after)
        assertEquals(listOf("after=${after.toEpochMilli()}"), order)
        assertEquals(4, result.scanned)
        assertEquals(1, result.parsed)
        assertEquals(1, result.nonFinancial)
        assertEquals(1, result.notRelevant)
        assertEquals(1, result.duplicates)
        assertEquals(2, db.rawSmsDao().count())
        assertNull(result.failure)
    }

    @Test
    fun permissionDenied_isDistinctFailure() = runBlocking {
        val source = object : SmsDataSource {
            override fun queryInbox(receivedAfter: Instant?) = throw SmsPermissionException()
        }
        val result = HistoricalSmsScanner(source, batchProcessor).scan()
        assertEquals(SmsScanFailure.PermissionDenied, result.failure)
        assertEquals(0, result.scanned)
    }

    @Test
    fun providerError_isDistinctFailure() = runBlocking {
        val source = object : SmsDataSource {
            override fun queryInbox(receivedAfter: Instant?) =
                throw SmsProviderException("boom")
        }
        val result = HistoricalSmsScanner(source, batchProcessor).scan()
        assertTrue(result.failure is SmsScanFailure.ProviderError)
        assertEquals(0, result.scanned)
    }

    @Test
    fun lazyPermissionException_duringIteration_isCaught() = runBlocking {
        val source = object : SmsDataSource {
            override fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> = sequence {
                throw SmsPermissionException("lazy")
            }
        }
        val result = HistoricalSmsScanner(source, batchProcessor).scan()
        assertEquals(SmsScanFailure.PermissionDenied, result.failure)
        assertEquals(0, result.scanned)
    }

    @Test
    fun lazyProviderException_preservesPartialSummary() = runBlocking {
        val good = InboxRow.Valid(
            ProviderSmsRecord(
                "1",
                "AlJazira",
                purchaseBody(),
                Instant.parse("2026-08-01T00:00:00Z"),
            ),
        )
        val source = object : SmsDataSource {
            override fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> = sequence {
                yield(good)
                throw SmsProviderException("cursor died")
            }
        }
        val result = HistoricalSmsScanner(source, batchProcessor).scan()
        assertTrue(result.failure is SmsScanFailure.ProviderError)
        assertEquals(1, result.scanned)
        assertEquals(1, result.parsed)
        assertEquals(1, db.rawSmsDao().count())
    }

    @Test
    fun malformedProviderRows_areCounted() = runBlocking {
        val good = InboxRow.Valid(
            ProviderSmsRecord("1", "AlJazira", purchaseBody(), Instant.parse("2026-08-01T00:00:00Z")),
        )
        val source = FakeSmsDataSource(listOf(good, InboxRow.Malformed, InboxRow.Malformed))
        val result = HistoricalSmsScanner(source, batchProcessor).scan()
        assertEquals(3, result.scanned)
        assertEquals(2, result.skippedMalformed)
        assertEquals(1, result.parsed)
        assertEquals(1, db.rawSmsDao().count())
    }

    @Test
    fun failedWithNullRawSmsId_incrementsFailedOnly_notInserted() = runBlocking {
        val failingRawRepo = object : RawSmsRepository {
            override suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult {
                throw IllegalStateException("db down")
            }

            override suspend fun getById(id: String): RawSms? = null
            override suspend fun existsById(id: String): Boolean = false
            override suspend fun findByDeviceMessageId(deviceMessageId: String): RawSms? = null
            override suspend fun listIdsByReceivedAt(): List<String> = emptyList()
            override suspend fun findCrossSourceNearDuplicate(
                sender: String,
                bodyHash: String,
                fromInclusive: Instant,
                toInclusive: Instant,
                lookingForLiveRow: Boolean,
            ): RawSms? = null
        }
        val svc = batchProcessor(failingRawRepo)
        val source = FakeSmsDataSource(
            listOf(
                InboxRow.Valid(
                    ProviderSmsRecord(
                        "10",
                        "AlJazira",
                        purchaseBody(),
                        Instant.parse("2026-08-01T00:00:00Z"),
                    ),
                ),
            ),
        )
        val result = HistoricalSmsScanner(source, svc).scan()
        assertEquals(1, result.scanned)
        assertEquals(0, result.inserted)
        assertEquals(1, result.failed)
        assertEquals(0, result.parsed)
        assertNull(result.failure)
    }

    @Test
    fun failedWithPersistedRawSmsId_incrementsInsertedAndFailed() = runBlocking {
        val exploding = SmsParseGateway { throw IllegalStateException("parse boom") }
        val svc = batchProcessor(
            RoomRawSmsRepository(db.rawSmsDao()),
            BankSmsRegistry(listOf(AlJaziraSmsAdapter(pipeline = exploding))),
        )
        val source = FakeSmsDataSource(
            listOf(
                InboxRow.Valid(
                    ProviderSmsRecord(
                        "11",
                        "AlJazira",
                        purchaseBody(),
                        Instant.parse("2026-08-01T00:00:00Z"),
                    ),
                ),
            ),
        )
        val result = HistoricalSmsScanner(source, svc).scan()
        assertEquals(1, result.scanned)
        assertEquals(1, result.inserted)
        assertEquals(1, result.failed)
        assertEquals(0, result.parsed)
        assertEquals(1, db.rawSmsDao().count())
        assertNull(result.failure)
    }

    private fun batchProcessor(
        rawSmsRepository: RawSmsRepository,
        registry: BankSmsRegistry = BankSmsRegistry(listOf(AlJaziraSmsAdapter())),
    ) = HistoricalSmsBatchProcessor(
        capture = CaptureBankSmsUseCase(rawSmsRepository, registry),
        processStored = ProcessStoredSmsUseCase(
            rawSmsRepository = rawSmsRepository,
            parsedEventRepository = RoomParsedEventRepository(db.parsedEventDao()),
            bankSmsRegistry = registry,
        ),
    )

    private fun purchaseBody() = """
        شراء عبر الانترنت
        بطاقة: 7271
        لدى: Keeta
        بمبلغ: 51.99 SAR
        في: 14:32 03-08-2026
    """.trimIndent()

    private class FakeSmsDataSource(
        private val rows: List<InboxRow>,
        private val onQuery: (Instant?) -> Unit = {},
    ) : SmsDataSource {
        override fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> {
            onQuery(receivedAfter)
            return rows.asSequence()
        }
    }

    @Test
    fun batchImport_matchesLegacyPerMessageImport_forFixtureCorpusInbox() = runBlocking {
        for (preOwned in listOf(false, true)) {
            val inbox = fixtureCorpusInbox()
            ImportWorld(context(), preOwned).use { legacy ->
                ImportWorld(context(), preOwned).use { batched ->
                    val legacyResult = legacy.legacyImport(inbox)
                    val batchedResult = batched.scanner(inbox).scan()

                    assertEquals(legacyResult.copy(failure = null), batchedResult)
                    assertTrue("corpus must create transactions", batched.snapshot().transactions.isNotEmpty())
                    assertTrue(
                        "corpus must pair a self transfer",
                        !preOwned || batched.snapshot().transactions.any {
                            it.type == FinancialTransactionType.SELF_TRANSFER
                        },
                    )
                    legacy.snapshot().assertSameAs(batched.snapshot(), "preOwned=$preOwned")
                }
            }
        }
    }

    @Test
    fun batchImport_defersDerivedReconciliationUntilBatchEnds() = runBlocking {
        ImportWorld(context(), preOwned = true).use { world ->
            val processor = world.batchProcessor()
            val batch = processor.startBatch()
            for (raw in purchaseInbox().map { AndroidSmsMapper.toRawSms(it) }) {
                assertTrue(batch.ingest(raw) is SmsIngestionResult.Parsed)
                assertEquals(0, world.db.financialTransactionDao().count())
            }
            assertTrue(world.reviewRepo.listAll().isEmpty())
            assertEquals(3, batch.storedEventCount)

            val summary = batch.finish()!!

            assertEquals(3, summary.assembledSingle)
            assertEquals(3, world.db.financialTransactionDao().count())
        }
    }

    @Test
    fun batchImport_scanRunsNoReconciliationWhileIterating() = runBlocking {
        ImportWorld(context(), preOwned = true).use { world ->
            val transactionsSeenWhileScanning = mutableListOf<Int>()
            val rows = purchaseInbox()
            val source = object : SmsDataSource {
                override fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> = sequence {
                    for (row in rows) {
                        transactionsSeenWhileScanning += runBlocking { world.db.financialTransactionDao().count() }
                        yield(InboxRow.Valid(row))
                    }
                    transactionsSeenWhileScanning += runBlocking { world.db.financialTransactionDao().count() }
                }
            }

            val result = HistoricalSmsScanner(source, world.batchProcessor()).scan()

            assertEquals(List(rows.size + 1) { 0 }, transactionsSeenWhileScanning)
            assertEquals(3, result.parsed)
            assertEquals(3, world.db.financialTransactionDao().count())
        }
    }

    @Test
    fun batchImport_partialProviderFailure_keepsCountersAndRunsDerivedPassForStoredEvidence() = runBlocking {
        ImportWorld(context(), preOwned = true).use { world ->
            val rows = purchaseInbox()
            val source = object : SmsDataSource {
                override fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> = sequence {
                    yield(InboxRow.Valid(rows[0]))
                    yield(InboxRow.Valid(rows[1]))
                    throw SmsProviderException("cursor died")
                }
            }

            val result = HistoricalSmsScanner(source, world.batchProcessor()).scan()

            assertTrue(result.failure is SmsScanFailure.ProviderError)
            assertEquals(2, result.scanned)
            assertEquals(2, result.inserted)
            assertEquals(2, result.parsed)
            assertEquals(2, world.db.rawSmsDao().count())
            assertEquals(2, world.db.financialTransactionDao().count())
        }
    }

    @Test
    fun batchImport_rerun_isIdempotent() = runBlocking {
        ImportWorld(context(), preOwned = true).use { world ->
            val inbox = fixtureCorpusInbox()
            val first = world.scanner(inbox).scan()
            val afterFirst = world.snapshot()

            val second = world.scanner(inbox).scan()

            assertNull(second.failure)
            assertEquals(first.scanned, second.scanned)
            assertEquals(first.inserted, second.duplicates)
            assertEquals(0, second.inserted)
            assertEquals(afterFirst, world.snapshot())
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun purchaseInbox(): List<ProviderSmsRecord> =
        listOf("51.99" to "14:32", "20.00" to "15:10", "8.25" to "18:45").mapIndexed { index, (amount, time) ->
            ProviderSmsRecord(
                providerMessageId = "p$index",
                sender = "AlJazira",
                body = purchaseBody().replace("51.99", amount).replace("14:32", time),
                receivedAt = Instant.parse("2026-08-03T${time}:00Z"),
            )
        }

    /**
     * Every on-disk AlJazira fixture as one inbox. Rows are ordered by their parsed local
     * time (falling back to a fixed base) so transfer legs land together like a real inbox.
     */
    private fun fixtureCorpusInbox(): List<ProviderSmsRecord> {
        val zone = ZoneId.of("Asia/Riyadh")
        val base = Instant.parse("2026-06-01T00:00:00Z")
        return AlJaziraFixtureLoader.loadAllFromClasspath()
            .sortedBy { it.id }
            .mapIndexed { index, fixture ->
                val local = occurredAtLocal(fixture.sender, fixture.body)
                val receivedAt = (local?.atZone(zone)?.toInstant() ?: base).plusSeconds(index.toLong())
                ProviderSmsRecord("fx-$index", fixture.sender, fixture.body, receivedAt)
            }
            .sortedBy { it.receivedAt }
    }

    private fun occurredAtLocal(sender: String, body: String): LocalDateTime? {
        val result = AlJaziraParsingPipeline().parse(
            SmsParseInput("probe", sender, body, Instant.parse("2026-08-11T00:00:00Z")),
        )
        val details = when (result) {
            is ParseResult.Success -> result.details
            is ParseResult.Partial -> result.details
            is ParseResult.ReviewRequired -> result.details
            is ParseResult.NonFinancial -> result.details
            is ParseResult.Unsupported, is ParseResult.Invalid -> null
        }
        return details?.occurredAtLocal
    }

    /** Production-equivalent wiring of import + derived processing on one in-memory DB. */
    private class ImportWorld(context: Context, preOwned: Boolean) : AutoCloseable {
        val db: MasroofDatabase = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        private val clock = InstantClock { Instant.parse("2026-09-01T00:00:00Z") }
        private val rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        private val parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        private val ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        val reviewRepo = RoomReviewRepository(db.reviewItemDao())
        private val accounts = RoomAccountRegistryRepository.from(db)
        private val cards = RoomCardRegistryRepository.from(db)
        private val loans = RoomLoanRegistryRepository.from(db)
        private val registry = BankSmsRegistry(listOf(AlJaziraSmsAdapter()))
        private val discovery = OwnershipDiscoveryService(accounts, cards, loans)
        private val reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, loans),
            ownershipConfirmationService = OwnershipConfirmationService(accounts, cards, loans),
            effectiveParsedEventProvider = EffectiveParsedEventProvider(
                parsedRepo,
                RoomUserCorrectionRepository(db.userCorrectionDao()),
            ),
            reviewRepository = reviewRepo,
        )
        private val reviewQueueUpdater = ReviewQueueUpdater(reviewRepo, ftRepo, clock)
        private val capture = CaptureBankSmsUseCase(rawRepo, registry)
        private val processStored = ProcessStoredSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedRepo,
            bankSmsRegistry = registry,
            ownershipDiscovery = discovery,
            reconciliation = reconciliation,
            reviewQueueUpdater = reviewQueueUpdater,
            ingestionReviewService = IngestionReviewService(reviewRepo, clock),
        )
        init {
            if (preOwned) {
                runBlocking {
                    listOf("3001", "3002", "3003").forEach {
                        accounts.setOwnership(AccountReference(Bank.BANK_ALJAZIRA, it), OwnershipStatus.OWNED)
                    }
                    listOf("7271", "2210", "8219").forEach {
                        cards.setOwnership(CardReference(Bank.BANK_ALJAZIRA, it), OwnershipStatus.OWNED)
                    }
                }
            }
        }

        fun batchProcessor() = HistoricalSmsBatchProcessor(
            capture = capture,
            processStored = processStored,
            ownershipDiscovery = discovery,
            reconciliation = reconciliation,
            reviewQueueUpdater = reviewQueueUpdater,
        )

        fun scanner(rows: List<ProviderSmsRecord>): HistoricalSmsScanner =
            HistoricalSmsScanner(FakeSmsDataSource(rows.map { InboxRow.Valid(it) }), batchProcessor())

        /** Pre-M3.3 behavior: per-message derived processing, then two full passes at scan end. */
        suspend fun legacyImport(rows: List<ProviderSmsRecord>): SmsScanResult {
            val facade = ProcessRawSmsUseCase(capture, processStored)
            var result = SmsScanResult()
            val senders = linkedSetOf<String>()
            for (row in rows) {
                senders += row.sender
                result = result.count(facade.ingest(AndroidSmsMapper.toRawSms(row), logOutcome = false))
            }
            reconciliation.reconcileStoredEvents()
            reviewQueueUpdater.applyReport(reconciliation.reconcileStoredEventsDetailed())
            return result.copy(scanned = rows.size, distinctSenders = senders.take(12))
        }

        suspend fun snapshot() = ImportSnapshot(
            rawSmsIds = rawRepo.listIdsByReceivedAt(),
            parsedEvents = parsedRepo.listAll().sortedBy { it.event.id },
            transactions = ftRepo.listAll().sortedBy { it.id },
            reviews = reviewRepo.listAll().sortedBy { it.id },
            // Registry row ids are random; compare entries by their identity fields.
            accounts = accounts.listAll().map { it.copy(id = "") }.sortedBy { it.maskedNumber },
            cards = cards.listAll().map { it.copy(id = "") }.sortedBy { it.last4 },
            loans = loans.listAll().map { it.copy(id = "") }.sortedBy { it.loanType },
        )

        override fun close() {
            db.close()
        }

        private fun SmsScanResult.count(outcome: SmsIngestionResult): SmsScanResult = when (outcome) {
            is SmsIngestionResult.Duplicate -> copy(duplicates = duplicates + 1)
            is SmsIngestionResult.NotRelevant -> copy(notRelevant = notRelevant + 1)
            is SmsIngestionResult.Parsed -> copy(inserted = inserted + 1, parsed = parsed + 1)
            is SmsIngestionResult.ReviewRequired -> copy(inserted = inserted + 1, reviewRequired = reviewRequired + 1)
            is SmsIngestionResult.NonFinancial -> copy(inserted = inserted + 1, nonFinancial = nonFinancial + 1)
            is SmsIngestionResult.Unsupported -> copy(inserted = inserted + 1, unsupported = unsupported + 1)
            is SmsIngestionResult.Invalid -> copy(inserted = inserted + 1, failed = failed + 1)
            is SmsIngestionResult.Failed ->
                copy(inserted = inserted + if (outcome.rawSmsId != null) 1 else 0, failed = failed + 1)
        }
    }

    private data class ImportSnapshot(
        val rawSmsIds: List<String>,
        val parsedEvents: List<ParsedEventRecord>,
        val transactions: List<FinancialTransaction>,
        val reviews: List<ReviewItem>,
        val accounts: List<AccountRegistryEntry>,
        val cards: List<CardRegistryEntry>,
        val loans: List<LoanRegistryEntry>,
    ) {
        fun assertSameAs(other: ImportSnapshot, label: String) {
            assertEquals("$label rawSms", rawSmsIds, other.rawSmsIds)
            assertEquals("$label parsedEvents", parsedEvents, other.parsedEvents)
            assertEquals("$label transactions", transactions.diff(other.transactions), emptyList<String>())
            assertEquals("$label reviews", reviews.diff(other.reviews), emptyList<String>())
            assertEquals("$label accounts", accounts, other.accounts)
            assertEquals("$label cards", cards, other.cards)
            assertEquals("$label loans", loans, other.loans)
        }

        private fun <T> List<T>.diff(other: List<T>): List<String> =
            (this - other.toSet()).map { "legacy-only: $it" } + (other - this.toSet()).map { "batch-only: $it" }
    }
}
