package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserDecisionRepository
import com.baraa.masroof.data.repository.appendDecision
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.hash.SmsBodyHasher
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Differential oracle for plan 3.3a: random SMS sequences run through the pre-3.3a reconciliation path (the debug
 * switch) and through the ledger writer on two identical in-memory databases, with ownership changes, user decisions
 * and seeded legacy rows mid-sequence. After every step both ledgers must agree per raw SMS set on row presence,
 * type, amount, currency and containers, on the open review kind per raw SMS and on `summary.failed`. Zone, time,
 * id, merchant and counterparty are not compared (owner-accepted re-derivation, docs/ARCHITECTURE.md "Known
 * differences"). One other difference is allowed, "Scope": the writer may differ from the old scoped path where it
 * equals the old path run with full passes only (a third world), because a scoped result never differs from the full
 * one while the old scoped pass was narrower.
 *
 * Deleted together with the old path in plan 3.3b.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerWriterDifferentialTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theWriterMatchesTheOldPathOnRandomScenarios() = runBlocking {
        val failures = mutableListOf<String>()
        var rows = 0
        var pairs = 0
        var scopeLags = 0
        for (seed in SEEDS) {
            val scenario = Scenario(Random(seed))
            World(context, legacy = true).use { old ->
                World(context, legacy = true, fullPassesOnly = true).use { oldFull ->
                    World(context, legacy = false).use { new ->
                        scenario.run(old, oldFull, new)?.let { failures += "seed $seed: $it\n${scenario.log.joinToString("\n")}" }
                        scopeLags += scenario.excludedScope
                        new.ledger.listAll().forEach { tx -> rows++; if (new.ledger.listRawSmsIds(tx.id).size > 1) pairs++ }
                    }
                }
            }
            if (failures.size >= 3) break
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
        // The scenarios must post and pair, or agreeing proves nothing.
        assertTrue("rows=$rows pairs=$pairs", rows > SEEDS.count() * 2 && pairs > SEEDS.count() / 4)
        println("differential: ${SEEDS.count()} scenarios, rows=$rows pairs=$pairs, steps equal only to the old full-pass path (Scope)=$scopeLags")
    }

    /**
     * One database with its reconciliation on the old path ([legacy]) or the writer; [fullPassesOnly] runs a full pass
     * wherever production runs a scoped one.
     */
    private class World(context: Context, legacy: Boolean, private val fullPassesOnly: Boolean = false) : AutoCloseable {
        val db: MasroofDatabase = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java).allowMainThreadQueries().build()
        val raw = RoomRawSmsRepository(db.rawSmsDao())
        val parsed = RoomParsedEventRepository(db.parsedEventDao())
        val ledger = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        val reviews = RoomReviewRepository(db.reviewItemDao())
        val decisions = RoomUserDecisionRepository(db)
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        val loans = RoomLoanRegistryRepository.from(db)
        private val queue = ReviewQueueUpdater(reviews, ledger) { AT }
        private val service = TransactionReconciliationService(
            rawSmsRepository = raw, financialTransactionRepository = ledger,
            ownershipResolver = OwnershipResolver(accounts, cards, loans),
            ownershipConfirmationService = OwnershipConfirmationService(accounts, cards, loans),
            effectiveParsedEventProvider = EffectiveParsedEventProvider(parsed, decisions),
            reviewRepository = reviews, userDecisionRepository = decisions, zoneId = ZONE,
            inTransaction = { write -> db.withTransaction { write() } }, useLegacyPath = { legacy },
        )

        suspend fun reconcile(ids: List<String>?): Int {
            val report = if (ids == null || fullPassesOnly) service.reconcileStoredEventsDetailed() else service.reconcileAffectedRawSmsIds(ids)
            queue.applyReport(report)
            return report.summary.failed
        }

        /** Row facts per raw SMS set, then the open review kind per raw SMS. */
        suspend fun state(): String {
            val rows = ledger.listAll().map { tx ->
                val ids = ledger.listRawSmsIds(tx.id).sorted()
                "$ids ${tx.type} ${tx.amount.amount.toPlainString()} ${tx.amount.currency} ${tx.sourceContainerId} ${tx.destinationContainerId}"
            }.sorted()
            val open = reviews.listAll().filter { it.status == ReviewStatus.REQUIRED }.map { "${it.rawSmsId}:${it.kind}" }.sorted()
            return (rows + open).joinToString("\n")
        }

        override fun close() = db.close()
    }

    /** A random sequence of arrivals, ownership changes, decisions and full passes, applied to both worlds alike. */
    private class Scenario(private val random: Random) {
        val log = mutableListOf<String>()
        private var next = 0
        private var clock = T0
        private val arrived = mutableListOf<String>()
        var excludedScope = 0

        /**
         * Applies every step to the three worlds and compares the writer with the old path after each one. A difference
         * from the old scoped path is allowed when the writer equals the old path run with full passes only (Known
         * differences, "Scope": a scoped result never differs from the full one; the old scoped pass was narrower).
         */
        suspend fun run(old: World, oldFull: World, new: World): String? {
            val worlds = listOf(old, oldFull, new)
            val initial = ACCOUNTS.associateWith { listOf(OwnershipStatus.OWNED, OwnershipStatus.OWNED, OwnershipStatus.EXTERNAL, null).random(random) }
            for ((account, status) in initial) if (status != null) worlds.forEach { it.accounts.setOwnership(account, status) }
            worlds.forEach { it.cards.setOwnership(CARD, OwnershipStatus.OWNED) }
            log += "ownership $initial"
            if (random.nextInt(3) == 0) seedLegacy(worlds)
            repeat(random.nextInt(4, 12)) { step ->
                val failed = when (random.nextInt(10)) {
                    in 0..5 -> arrive(worlds)
                    6 -> changeOwnership(worlds)
                    7 -> decide(worlds)
                    8 -> editRow(worlds)
                    else -> { log += "full"; worlds.map { it.reconcile(null) } }
                }
                compare(old, oldFull, new, failed)?.let { return "step $step: $it" }
            }
            return compare(old, oldFull, new, worlds.map { it.reconcile(null) }.also { log += "final full" })
        }

        private suspend fun compare(old: World, oldFull: World, new: World, failed: List<Int>): String? {
            val b = new.state()
            val a = old.state()
            if (a == b && failed[0] == failed[2]) return null
            val full = oldFull.state()
            if (full == b && failed[1] == failed[2]) {
                log += "  (differs from the old scoped path, equals the old full-pass path: Scope)"
                excludedScope++
                return null
            }
            return "failed old=${failed[0]} oldFull=${failed[1]} new=${failed[2]}\n--- old\n$a\n--- old, full passes only\n$full\n--- new\n$b"
        }

        private suspend fun arrive(worlds: List<World>): List<Int> {
            val batch = (0 until listOf(1, 1, 1, 2, 3).random(random)).map { message() }
            log += "arrive ${batch.joinToString { describe(it) }}"
            for ((event, details, at) in batch) worlds.forEach { it.persist(event, details, at) }
            arrived += batch.map { it.first.rawSmsId }
            return worlds.map { it.reconcile(batch.map { it.first.rawSmsId }) }
        }

        private suspend fun changeOwnership(worlds: List<World>): List<Int> {
            val account = ACCOUNTS.random(random)
            val status = listOf(OwnershipStatus.OWNED, OwnershipStatus.EXTERNAL).random(random)
            log += "ownership $account -> $status"
            worlds.forEach { it.accounts.setOwnership(account, status) }
            return worlds.map { world -> world.reconcile(world.parsed.listRawSmsIdsReferencingAccount(account)) }
        }

        /** A legacy edit on a posted single row, the same in both worlds: its type edited, or its zone emptied. */
        private suspend fun editRow(worlds: List<World>): List<Int> {
            val rows = worlds.last().ledger.listAll().filter { row ->
                worlds.last().ledger.listRawSmsIds(row.id).size == 1 && worlds.all { it.ledger.getById(row.id) != null }
            }
            val row = rows.randomOrNull(random) ?: return worlds.map { it.reconcile(null) }
            val edited = if (random.nextBoolean()) row.copy(type = FinancialTransactionType.INCOME) else row.copy(occurredAtZone = null)
            log += "edit ${row.id.takeLast(6)} type=${edited.type} zone=${edited.occurredAtZone}"
            worlds.forEach { world -> world.ledger.getById(row.id)?.let { world.ledger.update(edited.copy(occurredAt = it.occurredAt)) } }
            return worlds.map { it.reconcile(null) }
        }

        /** A decision on an SMS no row links in either world, the state every real decision writer leaves it in. */
        private suspend fun decide(worlds: List<World>): List<Int> {
            val free = arrived.filter { id -> worlds.all { it.ledger.findByRawSmsId(id) == null } }
            val subject = free.randomOrNull(random) ?: return worlds.map { it.reconcile(null) }
            val receivedAt = worlds[0].raw.getById(subject)!!.receivedAt
            val partner = free.filter { it != subject }.randomOrNull(random)
            val decision = when (random.nextInt(5)) {
                4 -> UserDecision(subject, UserDecisionKind.SELF_TRANSFER_PAIR, AT, pairedRawSmsId = partner,
                    transactionType = FinancialTransactionType.SELF_TRANSFER, occurredAt = receivedAt, occurredAtZone = ZONE.id)
                0 -> UserDecision(subject, UserDecisionKind.NON_FINANCIAL, AT)
                1 -> UserDecision(subject, UserDecisionKind.EXTERNAL_TRANSFER, AT)
                2 -> UserDecision(subject, UserDecisionKind.FINANCIAL_TYPE, AT, transactionType = FinancialTransactionType.EXPENSE,
                    occurredAt = receivedAt, occurredAtZone = ZONE.id)
                else -> UserDecision(subject, UserDecisionKind.CORRECTION, AT, amount = Money.of(BigDecimal("33.00"), Currency.SAR))
            }
            log += "decide ${decision.kind} on $subject"
            worlds.forEach { it.db.appendDecision(decision) }
            return worlds.map { it.reconcile(listOfNotNull(subject, decision.pairedRawSmsId)) }
        }

        /** Legacy rows as older versions left them, the same in both worlds: a 3-SMS expense and a link-less row. */
        private suspend fun seedLegacy(worlds: List<World>) {
            val purchases = (0 until 3).map { purchase(Random(it)) }
            for ((event, details, at) in purchases) worlds.forEach { it.persist(event, details, at) }
            val ids = purchases.map { it.first.rawSmsId }
            val merged = FinancialTransaction(TransactionIdFactory.fromRawSmsIds(ids), FinancialTransactionType.EXPENSE,
                Money.of(BigDecimal("10.00"), Currency.SAR), purchases[0].third, "card:BANK_ALJAZIRA:7271", null, null, null, null,
                purchases.map { it.first.id }.sorted(), occurredAtZone = null)
            val linkless = merged.copy(id = "transaction:legacy-linkless", linkedParsedEventIds = emptyList(),
                appliedExchangeRate = BigDecimal("3.75"), exchangeRateSource = com.baraa.masroof.domain.model.ExchangeRateSource.SMS)
            worlds.forEach { world ->
                world.ledger.save(merged, ids)
                world.db.financialTransactionDao().insertTransactionIfAbsent(com.baraa.masroof.data.room.mapper.FinancialTransactionMapper.toEntity(linkless))
            }
            arrived += ids
            log += "legacy 3-sms expense $ids, link-less row"
        }

        private fun message(): Triple<ParsedEvent, ParsedEventDetails, Instant> =
            when (random.nextInt(10)) {
                in 0..1 -> purchase(random)
                2 -> simple(MessageFamily.REFUND, card = CARD)
                3 -> simple(MessageFamily.OTP, amount = null)
                4 -> simple(MessageFamily.FINANCING_INSTALLMENT, source = ACCOUNTS[0], loan = true)
                else -> transfer()
            }

        private fun purchase(random: Random) = simple(MessageFamily.PURCHASE, card = CARD,
            status = if (random.nextInt(6) == 0) ParseStatus.REVIEW_REQUIRED else ParseStatus.SUCCESS)

        private fun transfer(): Triple<ParsedEvent, ParsedEventDetails, Instant> {
            val family = listOf(MessageFamily.TRANSFER_OUT, MessageFamily.TRANSFER_IN).random(random)
            val source = (ACCOUNTS + OTHER).random(random)
            val destination = (ACCOUNTS + OTHER).filter { it != source }.random(random)
            return simple(family, source = source, destination = destination,
                network = listOf(BankNetworkType.INTRA_BANK, BankNetworkType.INTER_BANK).random(random),
                reference = listOf(null, "R1", "R2").random(random), amount = listOf("100.00", "100.00", "250.00").random(random))
        }

        private fun simple(
            family: MessageFamily,
            amount: String? = listOf("10.00", "20.00").random(random),
            card: CardReference? = null,
            source: AccountReference? = null,
            destination: AccountReference? = null,
            network: BankNetworkType? = null,
            reference: String? = null,
            loan: Boolean = false,
            status: ParseStatus = ParseStatus.SUCCESS,
        ): Triple<ParsedEvent, ParsedEventDetails, Instant> {
            // Mostly close together, so transfers compete within the match window; sometimes a day later.
            clock = clock.plusSeconds(if (random.nextInt(5) == 0) 86_400 else random.nextLong(0, 240))
            val id = "sms-${next++}"
            val event = ParsedEvent(
                id = "pe-$id", rawSmsId = id, bank = Bank.BANK_ALJAZIRA, messageFamily = family, direction = MoneyDirection.OUTGOING,
                amount = amount?.let { Money.of(BigDecimal(it), Currency.SAR) }, purchaseChannel = null, sourceAccountRef = source,
                destinationAccountRef = destination, cardRef = card, merchant = "M$id", counterparty = null, occurredAt = null,
                bankNetworkType = network, confidence = Confidence(1.0), parseStatus = status,
            )
            val details = ParsedEventDetails(
                transactionReference = reference, loanType = LoanType.PERSONAL.takeIf { loan },
                occurredAtLocal = LocalDateTime.ofInstant(clock, ZoneOffset.ofHours(3)).takeIf { random.nextBoolean() },
            )
            return Triple(event, details, clock)
        }

        private fun describe(message: Triple<ParsedEvent, ParsedEventDetails, Instant>): String {
            val (event, details, at) = message
            return "${event.rawSmsId} ${event.messageFamily} ${event.amount?.amount} ${event.sourceAccountRef?.maskedNumber}->" +
                "${event.destinationAccountRef?.maskedNumber} ${event.bankNetworkType} ref=${details.transactionReference} " +
                "local=${details.occurredAtLocal != null} ${event.parseStatus} at=$at"
        }
    }

    private companion object {
        val SEEDS = 0 until 300
        val ZONE: ZoneId = ZoneId.of("Asia/Riyadh")
        val T0: Instant = Instant.parse("2026-08-01T09:00:00Z")
        val AT: Instant = Instant.parse("2026-09-01T00:00:00Z")
        val ACCOUNTS = listOf("1111", "2222", "3333").map { AccountReference(Bank.BANK_ALJAZIRA, it) }
        val OTHER = AccountReference(Bank.BANK_ALJAZIRA, "9999")
        val CARD = CardReference(Bank.BANK_ALJAZIRA, "7271")


        suspend fun World.persist(event: ParsedEvent, details: ParsedEventDetails, at: Instant) {
            raw.insertIfAbsent(RawSms(event.rawSmsId, "AlJazira", "body-${event.rawSmsId}", at, event.rawSmsId, SmsBodyHasher.sha256Hex(event.rawSmsId)))
            parsed.save(event, details)
            if (event.loanTypeIsPersonal(details)) loans.observe(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL), event.rawSmsId)
        }

        fun ParsedEvent.loanTypeIsPersonal(details: ParsedEventDetails) = details.loanType == LoanType.PERSONAL && messageFamily == MessageFamily.FINANCING_INSTALLMENT
    }
}
