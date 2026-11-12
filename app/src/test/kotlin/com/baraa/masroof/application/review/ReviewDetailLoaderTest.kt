package com.baraa.masroof.application.review

import com.baraa.masroof.testsupport.recordDecision
import com.baraa.masroof.testsupport.saveCorrection
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import com.baraa.masroof.testsupport.NoOpAppLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReviewDetailLoaderTest {

    private lateinit var db: MasroofDatabase
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var reviewRepo: RoomReviewRepository
    private lateinit var ingestionReviews: IngestionReviewService
    private lateinit var loader: ReviewDetailLoader
    private val clock = InstantClock { Instant.parse("2026-08-11T12:00:00Z") }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        reviewRepo = RoomReviewRepository(db.reviewItemDao())
        val wiring = com.baraa.masroof.testsupport.RoomReviewWiring(db, clock, raw = rawRepo, reviews = reviewRepo)
        ingestionReviews = IngestionReviewService(reviewRepo, clock)
        loader = ReviewDetailLoader(wiring.workflow, rawRepo, wiring.effective, provenance = { null })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun ingestionReviewWithoutParsedEvent_showsSenderBodyAndDate() = runBlocking {
        val body = "رسالة بنكية بصيغة غير مدعومة"
        val receivedAt = Instant.parse("2026-08-10T09:30:00Z")
        rawRepo.insertIfAbsent(
            RawSms(
                id = "sms-unsupported",
                sender = "AlJazira",
                body = body,
                receivedAt = receivedAt,
                deviceMessageId = "u1",
                bodyHash = SmsBodyHasher.sha256Hex(body),
            ),
        )
        val review = ingestionReviews.requireReview(
            "sms-unsupported",
            IngestionReviewService.REASON_UNSUPPORTED_FORMAT,
        )

        val detail = loader.loadDetail(review.id)!!
        assertEquals("AlJazira", detail.sender)
        assertEquals(body, detail.body)
        assertEquals(receivedAt, detail.receivedAt)
        assertNull(detail.messageFamily)
        assertNull(detail.amount)
        assertNull(detail.bankId) // No bank may be guessed from the sender or quoted message text.
        val selected = reviewRepo.upsertRequired(review.rawSmsId, review.kind,
            review.reasons + "user_selected_bank:STC", clock.now())
        assertEquals("STC", loader.loadDetail(review.id)!!.bankId)
        assertEquals(selected, reviewRepo.getById(review.id))
        assertEquals(body, rawRepo.getById(review.rawSmsId)!!.body)

        val summary = loader.loadSummaries().single()
        assertEquals(review.id, summary.review.id)
        assertEquals(body, summary.body)
        assertEquals(selected.reasons, summary.review.reasons)
    }
    @Test fun teachingUsesSelectedBankAndNeverChangesTheReviewedEvidenceOrDecision() = runBlocking {
        val body = "Payment\nAmount:10SAR"
        val raw = RawSms("teaching-source", "Sender", body, clock.now(), "teaching-provider", SmsBodyHasher.sha256Hex(body))
        rawRepo.insertIfAbsent(raw)
        val parsed = RoomParsedEventRepository(db.parsedEventDao())
        val event = com.baraa.masroof.domain.model.ParsedEvent("event-teaching", raw.id,
            com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA, com.baraa.masroof.domain.model.MessageFamily.UNKNOWN,
            null, null, null, null, null, null, null, null, null, null,
            com.baraa.masroof.domain.model.Confidence(0.0), com.baraa.masroof.domain.model.ParseStatus.REVIEW_REQUIRED)
        parsed.save(event)
        val review = ingestionReviews.requireReview(raw.id, IngestionReviewService.REASON_UNSUPPORTED_FORMAT)
        assertEquals("BANK_ALJAZIRA", loader.loadDetail(review.id)!!.bankId)
        reviewRepo.upsertRequired(raw.id, review.kind, review.reasons + "user_selected_bank:STC", clock.now())
        val decision = reviewRepo.markResolved(review.id,
            com.baraa.masroof.domain.model.ReviewResolutionKind.USER_NON_FINANCIAL, clock.now(), null)
        val detail = loader.loadDetail(review.id)!!
        assertEquals("STC", detail.bankId)
        val directory = java.nio.file.Files.createTempDirectory("review-teaching-").toFile()
        try {
            val patterns = com.baraa.masroof.application.patterns.MessagePatternsWorkflow(
                com.baraa.masroof.data.patterns.MessagePatternStore(directory.resolve("patterns.json")),
                com.baraa.masroof.data.room.DatabaseAccessGate(),
                appLogService = NoOpAppLog,)
            patterns.addBank(0, "Synthetic bank", detail.sender!!, detail.bankId)
            val preview = patterns.teach(1, detail.bankId!!, detail.sender,
                com.baraa.masroof.application.patterns.PatternOperation.PURCHASE, detail.body!!,
                listOf(com.baraa.masroof.application.patterns.PatternBinding("amount", body.indexOf("10SAR"), body.length)))
            patterns.saveTeaching(preview)
            assertEquals(decision, reviewRepo.getById(review.id))
            assertEquals(raw, rawRepo.getById(raw.id))
            assertEquals(event, parsed.findByRawSmsId(raw.id)!!.event)
            assertEquals(emptyList<com.baraa.masroof.domain.model.FinancialTransaction>(),
                RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao()).listAll())
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun pendingPatternPreviewExtractsEachMessageAndAbstainsOnConflictsWithoutWrites() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("pending-patterns-").toFile()
        try {
            val store = com.baraa.masroof.data.patterns.MessagePatternStore(directory.resolve("patterns.json"))
            val body = "Payment\nAmount:10SAR"
            val pattern = com.baraa.masroof.engine.bank.patterns.SavedMessagePattern.fromChoices("purchase", "STC", "STC Bank", "PURCHASE", body,
                listOf(com.baraa.masroof.engine.bank.patterns.FieldChoice("amount", body.indexOf("10SAR"), body.length)))
            val bank = com.baraa.masroof.engine.bank.patterns.BankMessagePatterns("STC", listOf("STC Bank"), patterns = listOf(pattern))
            store.saveBank(0, bank)
            val inputs = listOf("first", "second", "no-match", "posted", "corrected", "resolved", "other-bank")
            for (id in inputs) {
                val text = when(id) { "second" -> body.replace("10SAR", "250SAR"); "no-match" -> "Different wording"; else -> body }
                rawRepo.insertIfAbsent(RawSms(id, "STC Bank", text, clock.now(), id, SmsBodyHasher.sha256Hex(text)))
                val review = ingestionReviews.requireReview(id, IngestionReviewService.REASON_UNSUPPORTED_FORMAT)
                if (id == "resolved") reviewRepo.markResolved(review.id, com.baraa.masroof.domain.model.ReviewResolutionKind.USER_NON_FINANCIAL, clock.now(), null)
                if (id == "other-bank") {
                    reviewRepo.upsertRequired(id, review.kind, review.reasons + "user_selected_bank:OTHER", clock.now())
                    db.recordDecision(com.baraa.masroof.domain.model.UserDecision(id,
                        com.baraa.masroof.domain.model.UserDecisionKind.BANK_SELECTION, clock.now(), bankId = "OTHER"))
                }
            }
            db.saveCorrection(com.baraa.masroof.domain.model.UserCorrection("correction", "corrected", null, null, "USER", null, clock.now()))
            val corrections = com.baraa.masroof.data.repository.RoomUserDecisionRepository(db)
            val transactions = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
            val guardedTransactions = object : com.baraa.masroof.domain.repository.FinancialTransactionRepository by transactions {
                override suspend fun isRawSmsLinked(rawSmsId: String) = rawSmsId == "posted"
            }
            val preview = com.baraa.masroof.application.patterns.PendingPatternsWorkflow(store,
                com.baraa.masroof.data.room.DatabaseAccessGate(), reviewRepo, rawRepo,
                RoomParsedEventRepository(db.parsedEventDao()), corrections, guardedTransactions,
                com.baraa.masroof.bank.BankSmsRegistry(emptyList()))
            val originalReviews = reviewRepo.listAll()
            val originalRaw = rawRepo.getByIds(inputs)
            val result = preview.preview("STC", 1)
            assertEquals(3, result.rows.size)
            val matches = result.rows.flatMap { it.matches }.map { it.fields.getValue("amount").text }.sorted()
            assertEquals(listOf("10SAR", "250SAR"), matches)
            assertEquals(1, result.rows.count { it.status == com.baraa.masroof.application.patterns.PendingPatternStatus.NO_MATCH })
            // A long queue from another bank first must not hide this bank's rows.
            val otherBank = reviewRepo.listRequired().first { it.rawSmsId == "other-bank" }
            val crowded = object : com.baraa.masroof.domain.repository.ReviewRepository by reviewRepo {
                override suspend fun listRequired() = List(1_000) { otherBank.copy(id = "crowd-$it") } + reviewRepo.listRequired()
            }
            val crowdedReport = com.baraa.masroof.application.patterns.PendingPatternsWorkflow(store,
                com.baraa.masroof.data.room.DatabaseAccessGate(), crowded, rawRepo,
                RoomParsedEventRepository(db.parsedEventDao()), corrections, guardedTransactions,
                com.baraa.masroof.bank.BankSmsRegistry(emptyList())).preview("STC", 1)
            assertEquals(result.rows, crowdedReport.rows)
            org.junit.Assert.assertFalse(crowdedReport.limited)
            // This bank's own rows stop at the cap, and the report says so.
            val first = reviewRepo.listRequired().first { it.rawSmsId == "first" }
            val full = object : com.baraa.masroof.domain.repository.ReviewRepository by reviewRepo {
                override suspend fun listRequired() = List(1_001) { first.copy(id = "own-$it") }
            }
            val fullReport = com.baraa.masroof.application.patterns.PendingPatternsWorkflow(store,
                com.baraa.masroof.data.room.DatabaseAccessGate(), full, rawRepo,
                RoomParsedEventRepository(db.parsedEventDao()), corrections, guardedTransactions,
                com.baraa.masroof.bank.BankSmsRegistry(emptyList())).preview("STC", 1)
            assertEquals(1_000, fullReport.rows.size)
            org.junit.Assert.assertTrue(fullReport.limited)
            store.saveBank(1, bank.copy(patterns = listOf(pattern.copy(id = "competing"))))
            val conflict = preview.preview("STC", 2)
            assertEquals(2, conflict.rows.count { it.status == com.baraa.masroof.application.patterns.PendingPatternStatus.PATTERN_CONFLICT })
            org.junit.Assert.assertTrue(runCatching { preview.preview("STC", 1) }.isFailure)
            store.saveBank(2, com.baraa.masroof.engine.bank.patterns.BankMessagePatterns("COLLISION", listOf("STC Bank")))
            assertEquals(setOf(com.baraa.masroof.application.patterns.PendingPatternStatus.BANK_CONFLICT),
                preview.preview("STC", 3).rows.map { it.status }.toSet())
            assertEquals(originalReviews, reviewRepo.listAll())
            assertEquals(originalRaw, rawRepo.getByIds(inputs))
            assertEquals(emptyList<com.baraa.masroof.domain.model.FinancialTransaction>(), transactions.listAll())
        } finally { directory.deleteRecursively() }
        Unit
    }

}
