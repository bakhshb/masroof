package com.baraa.masroof.application.review

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomUserDecisionRepository
import com.baraa.masroof.domain.model.EffectiveUserDecisions
import com.baraa.masroof.domain.repository.UserDecisionRepository
import com.baraa.masroof.testsupport.DecidedCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime

/**
 * Locks correction overlay results and the batched read shape.
 * A list of N effective records inside one bind chunk costs one correction query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EffectiveParsedEventProviderTest {
    private lateinit var db: MasroofDatabase
    private lateinit var parsedRepo: RoomParsedEventRepository
    private lateinit var corrections: DecidedCorrectionRepository
    private lateinit var decisions: RoomUserDecisionRepository
    private lateinit var countingDao: CountingDecisions
    private lateinit var provider: EffectiveParsedEventProvider

    @Before
    fun setUp() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        corrections = DecidedCorrectionRepository(db)
        decisions = RoomUserDecisionRepository(db)
        seed(rawRepo)
        countingDao = CountingDecisions(decisions)
        provider = EffectiveParsedEventProvider(parsedRepo, countingDao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun overlays_keepLaterNonNullFieldsAndAutomationConfirmation() = runBlocking {
        val listed = provider.listAllEffective()
        val multi = listed.single { it.event.rawSmsId == "raw-multi" }
        assertEquals(MessageFamily.REFUND, multi.event.messageFamily)
        assertEquals(Money.of("25.00", Currency.SAR), multi.event.amount)
        assertEquals("First", multi.event.merchant)
        assertEquals("Cashier", multi.event.counterparty)
        assertTrue(multi.userCorrected)
        assertTrue(multi.automationConfirmed)

        val merchantOnly = listed.single { it.event.rawSmsId == "raw-merchant" }
        assertEquals(MessageFamily.PURCHASE, merchantOnly.event.messageFamily)
        assertEquals(Money.of("10.00", Currency.SAR), merchantOnly.event.amount)
        assertEquals("Renamed", merchantOnly.event.merchant)
        assertTrue(merchantOnly.userCorrected)
        assertFalse(merchantOnly.automationConfirmed)

        val plain = listed.single { it.event.rawSmsId == "raw-plain" }
        assertEquals("Shop", plain.event.merchant)
        assertFalse(plain.userCorrected)
        assertFalse(plain.automationConfirmed)
    }

    @Test
    fun listReads_matchSingleRecordOverlaysAndQueryOncePerChunk() = runBlocking {
        val all = expectedEffective(parsedRepo.listAll())
        countingDao.reset()
        assertEquals(all, provider.listAllEffective())
        assertEquals(1, countingDao.batchQueries)
        assertEquals(0, countingDao.perSmsQueries)
        assertEquals(all.map { it.event.rawSmsId }, countingDao.batchIdChunks.single())

        val windowStart = Instant.parse("2026-08-01T00:00:00Z")
        val windowEnd = Instant.parse("2026-08-06T00:00:00Z")
        val local = LocalDateTime.parse("2026-08-01T00:00:00")..LocalDateTime.parse("2026-08-06T00:00:00")
        val transfers = expectedEffective(parsedRepo.listTransferCandidates(windowStart..windowEnd, windowStart..windowEnd, local))
        countingDao.reset()
        assertEquals(transfers, provider.listTransferCandidatesEffective(windowStart..windowEnd, windowStart..windowEnd, local))
        assertEquals(listOf("raw-transfer-in", "raw-transfer-out"), transfers.map { it.event.rawSmsId })
        assertEquals(1, countingDao.batchQueries)
        assertEquals(0, countingDao.perSmsQueries)
        assertEquals(transfers.map { it.event.rawSmsId }, countingDao.batchIdChunks.single())
        val transfer = transfers.single { it.event.rawSmsId == "raw-transfer-out" }
        assertEquals(MessageFamily.TRANSFER_OUT, transfer.event.messageFamily)
        assertEquals("Sibling", transfer.event.counterparty)
        assertTrue(transfer.userCorrected)
        assertFalse(transfer.automationConfirmed)

        val selected = expectedEffective(parsedRepo.listByRawSmsIds(listOf("raw-plain", "raw-multi")))
        countingDao.reset()
        assertEquals(selected, provider.listEffectiveByRawSmsIds(listOf("raw-plain", "raw-multi", "missing")))
        assertEquals(listOf("raw-multi", "raw-plain"), selected.map { it.event.rawSmsId })
        assertEquals(1, countingDao.batchQueries)
        assertEquals(0, countingDao.perSmsQueries)

        countingDao.reset()
        assertEquals(
            emptyList<ParsedEventRecord>(),
            provider.listEffectiveByRawSmsIds(listOf("missing")),
        )
        assertEquals(0, countingDao.batchQueries)
        assertEquals(0, countingDao.perSmsQueries)

        countingDao.reset()
        assertEquals(all.single { it.event.rawSmsId == "raw-multi" }, provider.findEffectiveByRawSmsId("raw-multi"))
        assertEquals(1, countingDao.perSmsQueries)
        assertEquals(0, countingDao.batchQueries)
    }

    @Test
    fun batchLookup_ordersByTargetThenTimeThenId_likeTheLegacyStore() = runBlocking {
        val lookup = listOf("raw-outside", "missing", "raw-multi", "raw-merchant", "raw-transfer-out")
        val orderedIds = listOf("corr-merchant", "corr-a", "corr-b", "corr-c", "corr-old", "corr-xfer")
        val legacy = corrections.listAll().filter { it.targetRawSmsId in lookup }
        assertEquals(orderedIds, legacy.map { it.id })
        val read = decisions.effectiveFor(lookup)
        // Chunking of this read is covered by UserDecisionReaderTest.
        assertEquals(
            legacy.map { listOf(it.targetRawSmsId, it.correctedType, it.correctedAmount, it.correctedMerchant, it.correctedCounterparty) },
            lookup.sorted().flatMap { id ->
                read.getValue(id).corrections.map { listOf(it.rawSmsId, it.messageFamily, it.amount, it.merchant, it.counterparty) }
            },
        )
        assertEquals(emptyMap<String, Any>(), decisions.effectiveFor(emptyList()))
    }

    private suspend fun expectedEffective(records: List<ParsedEventRecord>): List<ParsedEventRecord> =
        records.map { record ->
            provider.applyCorrections(record, decisions.effectiveFor(record.event.rawSmsId).corrections)
        }

    private suspend fun seed(rawRepo: RoomRawSmsRepository) {
        saveSms(rawRepo, "raw-multi", Instant.parse("2026-08-01T08:00:00Z"), MessageFamily.PURCHASE)
        saveSms(rawRepo, "raw-plain", Instant.parse("2026-08-02T08:00:00Z"), MessageFamily.PURCHASE)
        saveSms(rawRepo, "raw-merchant", Instant.parse("2026-08-03T08:00:00Z"), MessageFamily.PURCHASE)
        saveSms(rawRepo, "raw-transfer-out", Instant.parse("2026-08-04T08:00:00Z"), MessageFamily.TRANSFER_OUT)
        saveSms(rawRepo, "raw-transfer-in", Instant.parse("2026-08-05T08:00:00Z"), MessageFamily.TRANSFER_IN)
        saveSms(rawRepo, "raw-outside", Instant.parse("2026-07-15T08:00:00Z"), MessageFamily.PURCHASE)

        val sameInstant = Instant.parse("2026-08-01T11:00:00Z")
        corrections.save(
            correction(
                id = "corr-a",
                rawSmsId = "raw-multi",
                createdAt = Instant.parse("2026-08-01T10:00:00Z"),
                amount = Money.of("1.00", Currency.SAR),
                merchant = "First",
            ),
        )
        corrections.save(
            correction(
                id = "corr-b",
                rawSmsId = "raw-multi",
                createdAt = sameInstant,
                type = MessageFamily.REFUND,
                amount = Money.of("25.00", Currency.SAR),
            ),
        )
        corrections.save(
            correction(
                id = "corr-c",
                rawSmsId = "raw-multi",
                createdAt = sameInstant,
                counterparty = "Cashier",
            ),
        )
        corrections.save(
            correction(
                id = "corr-merchant",
                rawSmsId = "raw-merchant",
                createdAt = Instant.parse("2026-08-03T09:00:00Z"),
                merchant = "Renamed",
            ),
        )
        corrections.save(
            correction(
                id = "corr-xfer",
                rawSmsId = "raw-transfer-out",
                createdAt = Instant.parse("2026-08-04T09:00:00Z"),
                counterparty = "Sibling",
            ),
        )
        corrections.save(
            correction(
                id = "corr-old",
                rawSmsId = "raw-outside",
                createdAt = Instant.parse("2026-07-15T09:00:00Z"),
                merchant = "Old",
            ),
        )
    }

    private suspend fun saveSms(
        rawRepo: RoomRawSmsRepository,
        rawSmsId: String,
        receivedAt: Instant,
        family: MessageFamily,
    ) {
        rawRepo.insertIfAbsent(
            RawSms(
                id = rawSmsId,
                sender = "AlJazira",
                body = "body-$rawSmsId",
                receivedAt = receivedAt,
                deviceMessageId = rawSmsId,
                bodyHash = "hash-$rawSmsId",
            ),
        )
        parsedRepo.save(
            ParsedEvent(
                id = "pe-$rawSmsId",
                rawSmsId = rawSmsId,
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = family,
                direction = MoneyDirection.OUTGOING,
                amount = Money.of("10.00", Currency.SAR),
                purchaseChannel = null,
                sourceAccountRef = null,
                destinationAccountRef = null,
                cardRef = null,
                merchant = "Shop",
                counterparty = null,
                occurredAt = receivedAt,
                bankNetworkType = null,
                confidence = Confidence(1.0),
                parseStatus = ParseStatus.SUCCESS,
            ),
        )
    }

    private fun correction(
        id: String,
        rawSmsId: String,
        createdAt: Instant,
        type: MessageFamily? = null,
        amount: Money? = null,
        merchant: String? = null,
        counterparty: String? = null,
    ) = UserCorrection(
        id = id,
        targetRawSmsId = rawSmsId,
        correctedType = type,
        correctedAmount = amount,
        correctedMerchant = merchant,
        correctedCounterparty = counterparty,
        createdAt = createdAt,
    )

    /** Counts the provider's decision reads: one per list read, one per single-record read. */
    private class CountingDecisions(private val delegate: UserDecisionRepository) : UserDecisionRepository by delegate {
        var perSmsQueries: Int = 0
        var batchQueries: Int = 0
        val batchIdChunks: MutableList<List<String>> = mutableListOf()

        fun reset() {
            perSmsQueries = 0
            batchQueries = 0
            batchIdChunks.clear()
        }

        override suspend fun effectiveFor(rawSmsId: String): EffectiveUserDecisions {
            perSmsQueries += 1
            return delegate.effectiveFor(rawSmsId)
        }

        override suspend fun effectiveFor(rawSmsIds: Collection<String>): Map<String, EffectiveUserDecisions> {
            batchQueries += 1
            batchIdChunks += rawSmsIds.toList()
            return delegate.effectiveFor(rawSmsIds)
        }
    }
}
