package com.baraa.masroof.integrity

import com.baraa.masroof.application.dashboard.GoldenExpected
import com.baraa.masroof.application.dashboard.GoldenLedgerRunner
import com.baraa.masroof.application.dashboard.GoldenLedgerScenario
import com.baraa.masroof.application.dashboard.GoldenLedgerSnapshot
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.application.sms.HistoricalDerivedRecovery
import com.baraa.masroof.application.sms.HistoricalSmsBatchProcessor
import com.baraa.masroof.application.transaction.ReconciliationCompletionPolicy
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.data.repository.RoomManualReviewResolutionRepository
import com.baraa.masroof.data.repository.RoomProcessingRetryRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.rules.InformationalMessagePolicy
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.sms.time.InstantClock
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.IOException
import java.time.Instant
import kotlin.random.Random

/**
 * Fixed seeds for M18 sequences. Every failing assertion names the seed that produced it.
 * Orders are permutations of the same provider rows; nothing here sleeps or draws entropy.
 */
internal object IntegritySeeds {
    const val FORWARD = 20261009
    const val REVERSE = 20261010
    const val LIVE_INBOX = 151515
    const val INSIDE_WINDOW = 180055
    const val AFTER_WINDOW = 200011
    const val AMBIGUOUS_TIE = 909090
    const val CURRENCY = 808080
    const val FAULT = 20261018
    const val MANUAL = 20261019
}

/**
 * Deterministic fault switches. A flag stays armed until the test clears it.
 * Room commits the delegated write before an "after" fault throws.
 */
internal class IntegrityFaults {
    var conflictSaves: Boolean = false
    var parsedSaveFailures: Int = 0
    var parsedReadFailures: Int = 0
    var replaceBeforeFailures: Int = 0
    var replaceAfterFailures: Int = 0
    var reviewBeforeFailures: Int = 0
    var reviewAfterFailures: Int = 0
    var clearBeforeFailures: Int = 0

    fun disarm() {
        conflictSaves = false
        parsedSaveFailures = 0
        parsedReadFailures = 0
        replaceBeforeFailures = 0
        replaceAfterFailures = 0
        reviewBeforeFailures = 0
        reviewAfterFailures = 0
        clearBeforeFailures = 0
    }
}

/**
 * Room ledger from [DashboardLedgerWorld] plus repository doubles that fail at
 * one processing boundary. Production repositories stay available for replay.
 */
internal class IntegrityLedger(val world: DashboardLedgerWorld) {
    val faults = IntegrityFaults()
    val clock: InstantClock = InstantClock { Instant.parse("2026-09-15T09:00:00Z") }
    val corrections = RoomUserCorrectionRepository(world.db.userCorrectionDao())
    val retries = RoomProcessingRetryRepository(world.db.processingRetryDao())
    private val registry = BankSmsRegistry(listOf(AlJaziraSmsAdapter()))
    private val discovery = OwnershipDiscoveryService(world.accounts, world.cards, world.loans)
    private val ownership = OwnershipResolver(world.accounts, world.cards, world.loans)
    private val confirmation = OwnershipConfirmationService(world.accounts, world.cards, world.loans)

    private val parsed = object : ParsedEventRepository by world.parsedRepo {
        override suspend fun save(
            event: com.baraa.masroof.domain.model.ParsedEvent,
            details: ParsedEventDetails,
        ) {
            if (consume(faults.parsedSaveFailures)) {
                faults.parsedSaveFailures -= 1
                throw IOException("after raw sms capture")
            }
            world.parsedRepo.save(event, details)
        }

        override suspend fun listByRawSmsIds(rawSmsIds: Collection<String>): List<ParsedEventRecord> {
            if (faults.parsedReadFailures > 0) {
                faults.parsedReadFailures -= 1
                throw IOException("after parsed event save")
            }
            return world.parsedRepo.listByRawSmsIds(rawSmsIds)
        }
    }

    private val posted = object : FinancialTransactionRepository by world.ftRepo {
        override suspend fun save(
            transaction: FinancialTransaction,
            rawSmsIds: Collection<String>,
        ): FinancialTransactionSaveResult {
            if (faults.conflictSaves) {
                return FinancialTransactionSaveResult.Conflict(
                    rawSmsId = rawSmsIds.firstOrNull().orEmpty(),
                    existingTransactionId = "conflict-existing",
                )
            }
            return world.ftRepo.save(transaction, rawSmsIds)
        }

        override suspend fun replaceExclusiveStaleLinks(
            transaction: FinancialTransaction,
            rawSmsIds: Collection<String>,
            staleRawSmsIds: Collection<String>,
        ): FinancialTransactionSaveResult {
            if (faults.replaceBeforeFailures > 0) {
                faults.replaceBeforeFailures -= 1
                throw IOException("before transactional link replace")
            }
            val saved = world.ftRepo.replaceExclusiveStaleLinks(transaction, rawSmsIds, staleRawSmsIds)
            if (faults.replaceAfterFailures > 0) {
                faults.replaceAfterFailures -= 1
                throw IOException("after transactional link replace")
            }
            return saved
        }
    }

    private val reviews = object : ReviewRepository by world.reviewRepo {
        override suspend fun upsertRequired(
            rawSmsId: String,
            kind: com.baraa.masroof.domain.model.ReviewKind,
            reasons: List<String>,
            now: Instant,
        ): com.baraa.masroof.domain.model.ReviewItem {
            if (faults.reviewBeforeFailures > 0) {
                faults.reviewBeforeFailures -= 1
                throw IOException("before review upsert")
            }
            val saved = world.reviewRepo.upsertRequired(rawSmsId, kind, reasons, now)
            if (faults.reviewAfterFailures > 0) {
                faults.reviewAfterFailures -= 1
                throw IOException("after review upsert")
            }
            return saved
        }
    }

    private val retryWrites = object : ProcessingRetryRepository by retries {
        override suspend fun clear(rawSmsId: String) {
            if (faults.clearBeforeFailures > 0) {
                faults.clearBeforeFailures -= 1
                throw IOException("before clearing recovery state")
            }
            retries.clear(rawSmsId)
        }

        override suspend fun clear(rawSmsIds: List<String>) {
            if (faults.clearBeforeFailures > 0) {
                faults.clearBeforeFailures -= 1
                throw IOException("before clearing recovery state")
            }
            retries.clear(rawSmsIds)
        }
    }

    val reconciliation = TransactionReconciliationService(
        parsedEventRepository = parsed,
        rawSmsRepository = world.rawRepo,
        financialTransactionRepository = posted,
        ownershipResolver = ownership,
        ownershipConfirmationService = confirmation,
        reviewRepository = reviews,
        zoneId = world.zone,
    )

    val productionReconciliation = TransactionReconciliationService(
        parsedEventRepository = world.parsedRepo,
        rawSmsRepository = world.rawRepo,
        financialTransactionRepository = world.ftRepo,
        ownershipResolver = ownership,
        ownershipConfirmationService = confirmation,
        effectiveParsedEventProvider = EffectiveParsedEventProvider(world.parsedRepo, corrections),
        reviewRepository = world.reviewRepo,
        zoneId = world.zone,
    )

    private val reviewQueue = ReviewQueueUpdater(reviews, posted, clock)
    private val productionReviewQueue = ReviewQueueUpdater(world.reviewRepo, world.ftRepo, clock)
    private val recovery = com.baraa.masroof.application.ingestion.ProcessingRecovery(
        processingRetryRepository = retryWrites,
        reviewRepository = reviews,
        ingestionReviewService = IngestionReviewService(reviews, clock),
        clock = clock,
    )

    private val processor = HistoricalSmsBatchProcessor(
        capture = world.captureBankSms,
        processStored = ProcessStoredSmsUseCase(
            rawSmsRepository = world.rawRepo,
            parsedEventRepository = parsed,
            bankSmsRegistry = registry,
        ),
        ownershipDiscovery = discovery,
        reconciliation = reconciliation,
        reviewQueueUpdater = reviewQueue,
        processingRecovery = recovery,
    )

    val liveProcessing = ProcessStoredSmsUseCase(
        rawSmsRepository = world.rawRepo,
        parsedEventRepository = parsed,
        bankSmsRegistry = registry,
        ownershipDiscovery = discovery,
        reconciliation = reconciliation,
        reviewQueueUpdater = reviewQueue,
        ingestionReviewService = IngestionReviewService(reviews, clock),
        processingRecovery = recovery,
    )

    val workflow = ReviewWorkflowService(
        reviewRepository = world.reviewRepo,
        userCorrectionRepository = corrections,
        financialTransactionRepository = world.ftRepo,
        rawSmsRepository = world.rawRepo,
        ownershipResolver = ownership,
        ownershipConfirmationService = confirmation,
        effectiveParsedEventProvider = EffectiveParsedEventProvider(world.parsedRepo, corrections),
        parsedEventRepository = world.parsedRepo,
        reconciliationService = productionReconciliation,
        reviewQueueUpdater = productionReviewQueue,
        manualReviewResolutionRepository = RoomManualReviewResolutionRepository(world.db, world.ftRepo),
        clock = clock,
        zoneId = world.zone,
        newCorrectionId = { "uc-m18-${correctionSequence++}" },
    )

    private var correctionSequence = 1

    fun openBatch(): HistoricalSmsBatchProcessor.Batch = processor.startBatch()

    fun derivedRecovery(): HistoricalDerivedRecovery = HistoricalDerivedRecovery(
        parsedEventRepository = world.parsedRepo,
        processingRetryRepository = retryWrites,
        ownershipDiscovery = discovery,
        reconciliation = reconciliation,
        reviewQueueUpdater = reviewQueue,
        rawSmsRepository = world.rawRepo,
    )

    /** Reparse, reconcile, and legacy transfer repair on the unwrapped production path. */
    suspend fun replayProduction() {
        val results = world.reprocessStoredEvidence()
        val blocked = results.filter {
            it is SmsIngestionResult.Failed || it is SmsIngestionResult.DerivedIncomplete
        }
        check(blocked.isEmpty()) { "replay blocked: $blocked" }
        val reconciled = productionReconciliation.reconcileStoredEventsDetailed()
        check(ReconciliationCompletionPolicy.isComplete(reconciled)) {
            "replay reconcile failed=${reconciled.summary.failed}"
        }
        productionReviewQueue.applyReport(reconciled)
        val repaired = productionReconciliation.repairLegacyTransfersDetailed()
        check(ReconciliationCompletionPolicy.isComplete(repaired)) {
            "repair failed=${repaired.summary.failed}"
        }
        productionReviewQueue.applyReport(repaired)
    }
}

internal suspend fun HistoricalSmsBatchProcessor.Batch.ingestProvider(
    row: ProviderSmsRecord,
): com.baraa.masroof.application.ingestion.SmsIngestionResult =
    ingest(AndroidSmsMapper.toRawSms(row))

internal fun providerRow(
    providerMessageId: String?,
    body: String,
    receivedAt: String,
    sender: String = "AlJazira",
): ProviderSmsRecord = ProviderSmsRecord(
    providerMessageId = providerMessageId,
    sender = sender,
    body = body,
    receivedAt = Instant.parse(receivedAt),
)

internal fun intraOut(local: String): String = """
    حوالة صادرة الى حسابك الجاري
    من: 3001
    مبلغ: SAR 2,000.00
    إلى: 3002
    في: $local
""".trimIndent()

internal fun intraIn(local: String): String = """
    حوالة واردة داخلية
    مبلغ: SAR 2,000.00
    إلى: 3002
    اسم المرسل: TEST_PERSON
    رقم حساب المرسل: 3001
    البنك المرسل: بنك الجزيرة
    في: $local
""".trimIndent()

internal fun bakeryPurchaseBody(): String = """
    شراء من نقاط البيع
    بطاقة مدى: 2210
    لدى: TEST_BAKERY
    بمبلغ: 15.50 SAR
    خصمت من حساب: 3001
    في: 09:15 05-09-2026
""".trimIndent()

internal fun usdPurchaseBody(): String = """
    شراء عبر الانترنت
    بطاقة ائتمانية: 7271
    لدى: TEST_FX_SHOP
    بمبلغ: USD 10.00
    في: 2026-09-06 10:00
""".trimIndent()

internal fun balanceNoticeBody(): String = """
    إشعار رصيد
    حساب: 3001
    الرصيد المتاح: SAR 17230.03
    في: 2026-09-04 08:00
""".trimIndent()

internal fun unknownBankNoticeBody(): String =
    "تنبيه بنك الجزيرة: حدث تحديث في خدماتك. راجع التطبيق للتفاصيل."

internal fun otpBody(): String =
    "رمز التحقق لعملية شراء عبر الانترنت: 482913\n" +
        "بمبلغ: 250.00 SAR\n" +
        "لدى: TEST_STORE\n" +
        "لا تشارك الرمز مع أحد"

/** At least two distinct permutations. The seed of each permutation is stable. */
internal fun seededOrders(
    seed: Int,
    rows: List<ProviderSmsRecord>,
): List<Pair<Int, List<ProviderSmsRecord>>> {
    val orders = linkedMapOf<Int, List<ProviderSmsRecord>>()
    orders[seed] = rows.shuffled(Random(seed))
    orders[seed + 1] = rows.asReversed()
    if (orders.values.distinct().size < 2 && rows.size > 1) {
        orders[seed + 2] = rows
    }
    return orders.map { it.key to it.value }
}

internal suspend fun ownAccounts(world: DashboardLedgerWorld, masks: List<String>) {
    for (mask in masks) {
        world.accounts.setOwnership(
            AccountReference(Bank.BANK_ALJAZIRA, mask),
            OwnershipStatus.OWNED,
        )
    }
}

internal suspend fun ownCard(world: DashboardLedgerWorld, last4: String, type: CardType) {
    val reference = CardReference(Bank.BANK_ALJAZIRA, last4)
    world.cards.setOwnership(reference, OwnershipStatus.OWNED)
    world.cards.updateCardType(reference, type)
}

internal fun periodScenario(id: String): GoldenLedgerScenario = GoldenLedgerScenario(
    id = id,
    status = "ACTIVE",
    periodAnchor = "2026-09-10",
    description = id,
    expected = GoldenExpected(
        rawSmsCount = 0,
        parsed = emptyList(),
        transactions = emptyList(),
        displayedRows = emptyList(),
    ),
)

internal suspend fun prepareShuffled(
    world: DashboardLedgerWorld,
    scenario: GoldenLedgerScenario,
    seed: Int,
    rows: List<ProviderSmsRecord>,
) {
    for (account in scenario.ownedAccounts) {
        world.accounts.setOwnership(
            AccountReference(Bank.fromId(account.bank), account.masked),
            OwnershipStatus.OWNED,
        )
    }
    for (card in scenario.ownedCards) {
        val reference = CardReference(Bank.fromId(card.bank), card.last4)
        world.cards.setOwnership(reference, OwnershipStatus.OWNED)
        world.cards.updateCardType(reference, CardType.valueOf(card.type))
    }
    if (scenario.messages.isEmpty()) {
        GoldenLedgerRunner.prepare(world, scenario)
        return
    }
    GoldenLedgerRunner.requireSuccessfulImport(
        id = "${scenario.id} seed=$seed",
        imported = world.importProviderRows(rows),
        label = "import",
    )
}

/**
 * Posted, REQUIRED review, or durable retry for captured financial SMS.
 * Informational and explicit non-financial rows stay terminal and unposted.
 * Exact currencies are not added together. Each RawSms links to at most one movement.
 */
internal suspend fun assertFinancialInvariants(
    world: DashboardLedgerWorld,
    seed: Int,
    label: String,
    retriesMustBeClear: Boolean = true,
) {
    val prefix = "seed=$seed $label"
    val retryIds = RoomProcessingRetryRepository(world.db.processingRetryDao())
        .listRetryableRawSmsIds()
        .toSet()
    if (retriesMustBeClear) {
        assertTrue("$prefix retry rows remain $retryIds", retryIds.isEmpty())
    }
    val corrections = RoomUserCorrectionRepository(world.db.userCorrectionDao())
    val linkOwners = linkedMapOf<String, String>()
    for (transaction in world.ftRepo.listAll()) {
        val links = world.ftRepo.listRawSmsIds(transaction.id)
        assertTrue("$prefix transaction ${transaction.id} has no RawSms link", links.isNotEmpty())
        for (rawSmsId in links) {
            assertNotNull("$prefix missing RawSms $rawSmsId", world.rawRepo.getById(rawSmsId))
            val previous = linkOwners.put(rawSmsId, transaction.id)
            assertTrue(
                "$prefix RawSms $rawSmsId links to ${transaction.id} and $previous",
                previous == null,
            )
            assertEquals(
                "$prefix RawSms $rawSmsId posted movement",
                transaction.id,
                world.ftRepo.findByRawSmsId(rawSmsId)!!.id,
            )
            val parsedAmount = world.parsedRepo.findByRawSmsId(rawSmsId)?.event?.amount
            val correctedAmount = corrections.latestForRawSmsId(rawSmsId)?.correctedAmount
            if (parsedAmount != null && correctedAmount == null) {
                assertEquals(
                    "$prefix stored currency",
                    parsedAmount.currency,
                    transaction.amount.currency,
                )
            }
        }
    }

    val currencies = world.ftRepo.listAll().map { it.amount.currency }.distinct()
    if (currencies.size > 1) {
        val first = world.ftRepo.listAll().first { it.amount.currency == currencies[0] }.amount
        val second = world.ftRepo.listAll().first { it.amount.currency == currencies[1] }.amount
        try {
            first + second
            fail("$prefix added ${first.currency} to ${second.currency}")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("Currency mismatch") == true)
        }
    }

    for (rawSmsId in world.rawRepo.listIdsByReceivedAt()) {
        val parsed = world.parsedRepo.findByRawSmsId(rawSmsId)
        val transaction = world.ftRepo.findByRawSmsId(rawSmsId)
        val review = world.reviewRepo.findByRawSmsId(rawSmsId)
        val retrying = rawSmsId in retryIds
        val explicitNonFinancial = review?.status == ReviewStatus.RESOLVED &&
            review.resolutionKind == ReviewResolutionKind.USER_NON_FINANCIAL
        if (explicitNonFinancial) {
            assertNull("$prefix explicit non-financial $rawSmsId posted", transaction)
            assertFalse("$prefix explicit non-financial $rawSmsId still retrying", retrying)
            continue
        }
        if (parsed == null) {
            assertNull("$prefix unparsed $rawSmsId posted", transaction)
            assertTrue(
                "$prefix unparsed $rawSmsId has no review or retry",
                review?.status == ReviewStatus.REQUIRED || retrying,
            )
            continue
        }
        val informational = InformationalMessagePolicy.shouldAutoIgnore(parsed.event.messageFamily) ||
            parsed.event.parseStatus == ParseStatus.NON_FINANCIAL
        if (informational) {
            assertNull("$prefix informational $rawSmsId posted", transaction)
            assertFalse("$prefix informational $rawSmsId still retrying", retrying)
            if (review != null) {
                assertTrue(
                    "$prefix informational $rawSmsId left REQUIRED",
                    review.status != ReviewStatus.REQUIRED,
                )
            }
            continue
        }
        val covered = transaction != null || review?.status == ReviewStatus.REQUIRED || retrying
        assertTrue(
            "$prefix financial ${parsed.event.messageFamily} $rawSmsId uncovered",
            covered,
        )
        if (transaction != null) {
            assertFalse("$prefix posted $rawSmsId still retrying", retrying)
        }
    }
}

private fun consume(remaining: Int): Boolean = remaining > 0
