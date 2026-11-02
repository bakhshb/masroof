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
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.hash.SmsBodyHasher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Reconciling many messages costs a few batched reads, not several queries for each message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReconciliationQueryBudgetTest {
    private val reads = AtomicInteger()
    private lateinit var db: MasroofDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> if (isEvidenceRead(sql)) reads.incrementAndGet() }, Runnable::run)
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun fiftyPurchases_stayWithinAPerMessageBudget() = runBlocking {
        val raw = RoomRawSmsRepository(db.rawSmsDao())
        val parsed = RoomParsedEventRepository(db.parsedEventDao())
        val ledger = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        val loans = RoomLoanRegistryRepository.from(db)
        val card = CardReference(Bank.BANK_ALJAZIRA, "7271")
        OwnershipConfirmationService(accounts, cards, loans).confirmCardOwned(card)
        val count = 50
        repeat(count) { i ->
            val id = "sms-$i"
            val body = "purchase $i"
            raw.insertIfAbsent(RawSms(id, "AlJazira", body, Instant.parse("2026-08-01T12:00:00Z").plusSeconds(i * 60L), id, SmsBodyHasher.sha256Hex(body)))
            parsed.save(
                ParsedEvent(
                    id = "pe-$i", rawSmsId = id, bank = Bank.BANK_ALJAZIRA, messageFamily = MessageFamily.PURCHASE,
                    direction = MoneyDirection.OUTGOING, amount = Money.of(BigDecimal("10.00"), Currency.SAR),
                    purchaseChannel = null, sourceAccountRef = null, destinationAccountRef = null, cardRef = card,
                    merchant = "Shop $i", counterparty = null, occurredAt = null, bankNetworkType = null,
                    confidence = Confidence(1.0), parseStatus = ParseStatus.SUCCESS,
                ),
                ParsedEventDetails(),
            )
        }
        val service = TransactionReconciliationService(
            parsedEventRepository = parsed,
            rawSmsRepository = raw,
            financialTransactionRepository = ledger,
            ownershipResolver = OwnershipResolver(accounts, cards, loans),
            reviewRepository = RoomReviewRepository(db.reviewItemDao()),
        )

        reads.set(0)
        val summary = service.reconcileAffectedRawSmsIds((0 until count).map { "sms-$it" }).summary

        assertEquals(count, summary.assembledSingle)
        // Before batching this was about ten reads per message; now only the save's own link checks are per message.
        assertTrue("reads=${reads.get()} for $count messages", reads.get() <= PASS_READS + count * SAVE_READS)
    }

    private companion object {
        /** Batched evidence and ownership reads for the whole pass. */
        const val PASS_READS = 10

        /** The atomic save re-reads each link inside its transaction to detect a conflict. */
        const val SAVE_READS = 2

        fun isEvidenceRead(sql: String): Boolean = sql.trimStart().startsWith("SELECT", ignoreCase = true) &&
            "last_insert_rowid" !in sql && "changes()" !in sql && "room_table_modification_log" !in sql
    }
}
