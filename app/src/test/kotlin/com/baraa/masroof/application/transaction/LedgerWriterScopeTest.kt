package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomCommitmentRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserDecisionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Commitment
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
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.hash.SmsBodyHasher
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scoped = full through Room (docs/ARCHITECTURE.md, "Ledger writer"): random scenarios run on two databases, one with
 * the scoped passes production runs (`reconcileAffectedRawSmsIds`, `LedgerInputLoader.loadScope` and its bounded
 * queries), one with a full pass in their place. After every step both hold the same rows and commitment links.
 * `LedgerProjectorPropertyTest` checks the pure scope; this checks the database reads that build it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerWriterScopeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun scopedPassesWriteWhatFullPassesWrite() = runBlocking {
        var rows = 0
        var pairs = 0
        for (seed in 0 until SEEDS) {
            val scenario = Scenario(Random(seed))
            World(context, scenario.deviceZone).use { scoped ->
                World(context, scenario.deviceZone, fullPassesOnly = true).use { full ->
                    scenario.run(scoped, full)
                    val state = full.state()
                    rows += state.count { it.startsWith("row") }
                    pairs += state.count { it.startsWith("row [sms-") && it.substringBefore("]").contains(",") }
                }
            }
        }
        // Coverage floor: the scenarios must reach many rows and some rows of several SMS.
        assertTrue("rows=$rows pairs=$pairs", rows > SEEDS * 2 && pairs > SEEDS / 10)
    }

    private class World(context: Context, zone: ZoneId, private val fullPassesOnly: Boolean = false) : AutoCloseable {
        val db: MasroofDatabase = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java).allowMainThreadQueries().build()
        val raw = RoomRawSmsRepository(db.rawSmsDao())
        val parsed = RoomParsedEventRepository(db.parsedEventDao())
        val ledger = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        private val decisions = RoomUserDecisionRepository(db)
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        val loans = RoomLoanRegistryRepository.from(db)
        val commitments = RoomCommitmentRepository.from(db)
        private val service = TransactionReconciliationService(
            rawSmsRepository = raw, financialTransactionRepository = ledger,
            ownershipResolver = OwnershipResolver(accounts, cards, loans),
            ownershipConfirmationService = OwnershipConfirmationService(accounts, cards, loans),
            effectiveParsedEventProvider = EffectiveParsedEventProvider(parsed, decisions),
            reviewRepository = RoomReviewRepository(db.reviewItemDao()), userDecisionRepository = decisions, zoneId = zone,
            inTransaction = { write -> db.withTransaction { write() } },
        )

        suspend fun reconcile(ids: List<String>?) {
            val report = if (ids == null || fullPassesOnly) service.reconcileStoredEventsDetailed() else service.reconcileAffectedRawSmsIds(ids)
            assertEquals("no pass fails here", 0, report.summary.failed)
        }

        suspend fun persist(message: ScopeMessage) {
            val (event, details, at) = message
            raw.insertIfAbsent(RawSms(event.rawSmsId, "AlJazira", "body-${event.rawSmsId}", at, event.rawSmsId, SmsBodyHasher.sha256Hex(event.rawSmsId)))
            parsed.save(event, details)
            if (details.loanType != null) loans.observe(LOAN, event.rawSmsId)
        }

        /** Every row (all columns) by its raw SMS set, and the raw SMS set each commitment resolves to. */
        suspend fun state(): Set<String> {
            val sets = mutableMapOf<String, List<String>>()
            val rows = ledger.listAll().map { tx -> "row ${ledger.listRawSmsIds(tx.id).sorted().also { sets[tx.id] = it }} $tx" }
            val links = commitments.listAll().map { "commitment ${it.name} -> ${sets[it.sourceTransactionId] ?: "dangling"}" }
            return (rows + links).toSet()
        }

        override fun close() = db.close()
    }

    private class Scenario(private val random: Random) {
        val deviceZone: ZoneId = listOf(ZoneId.of("Asia/Riyadh"), ZoneId.of("UTC"), ZoneId.of("Asia/Dubai")).random(random)
        private val log = mutableListOf("device zone $deviceZone")
        private var next = 0
        private var clock = T0
        private var batched = false

        suspend fun run(scoped: World, full: World) {
            val worlds = listOf(scoped, full)
            for (account in ACCOUNTS) {
                val status = listOf(OwnershipStatus.OWNED, OwnershipStatus.OWNED, OwnershipStatus.EXTERNAL, null).random(random) ?: continue
                worlds.forEach { it.accounts.setOwnership(account, status) }
            }
            worlds.forEach { it.cards.setOwnership(CARD, OwnershipStatus.OWNED) }
            if (random.nextInt(3) == 0) seedLegacy(worlds)
            // Stored rows start at the full pass's fixed point, as the one-time repair leaves them.
            worlds.forEach { it.reconcile(null) }
            repeat(random.nextInt(4, 14)) { step ->
                when (random.nextInt(12)) {
                    in 0..7 -> arrive(worlds, listOf(1, 1, 1, 2, 3).random(random))
                    8 -> changeOwnership(worlds)
                    9 -> commit(worlds)
                    10 -> if (batched || random.nextInt(5) != 0) worlds.forEach { it.reconcile(null) } else {
                        batched = true
                        arrive(worlds, 205)
                    }
                    else -> worlds.forEach { it.reconcile(null) }
                }
                val (a, b) = scoped.state() to full.state()
                assertEquals(
                    "step $step\n${log.joinToString("\n")}\n--- scoped only\n${(a - b).sorted().joinToString("\n")}\n--- full only\n" +
                        (b - a).sorted().joinToString("\n"),
                    b, a,
                )
            }
        }

        private suspend fun arrive(worlds: List<World>, count: Int) {
            val batch = (0 until count).map { if (count > 3 && it % 20 != 0) purchase() else message() }
            log += "arrive ${batch.joinToString { "${it.first.rawSmsId} ${it.first.bank.id} ${it.first.messageFamily} ${it.third}" }}"
            for (message in batch) worlds.forEach { it.persist(message) }
            val ids = batch.map { it.first.rawSmsId }
            worlds.forEach { it.reconcile(ids) }
        }

        private suspend fun changeOwnership(worlds: List<World>) {
            val status = listOf(OwnershipStatus.OWNED, OwnershipStatus.EXTERNAL).random(random)
            val ids = if (random.nextInt(4) == 0) {
                log += "loan ownership -> $status"
                worlds.forEach { it.loans.setOwnership(LOAN, status) }
                worlds[0].parsed.listRawSmsIdsReferencingLoan(LOAN)
            } else {
                val account = ACCOUNTS.random(random)
                log += "ownership ${account.bank.id}:${account.maskedNumber} -> $status"
                worlds.forEach { it.accounts.setOwnership(account, status) }
                worlds[0].parsed.listRawSmsIdsReferencingAccount(account)
            }
            worlds.forEach { it.reconcile(ids) }
        }

        private suspend fun commit(worlds: List<World>) {
            val tx = worlds[0].ledger.listAll().randomOrNull(random) ?: return
            val name = "c${next++}"
            log += "commitment $name on ${tx.id.takeLast(6)}"
            worlds.forEach {
                runCatching {
                    it.commitments.create(Commitment(name, name, tx.amount, LocalDate.parse("2026-08-01"), null, null, true,
                        sourceTransactionId = tx.id, createdAt = AT, updatedAt = AT))
                }
            }
            worlds.forEach { it.reconcile(null) }
        }

        /** Rows as older versions left them: merged rows, a posted OTP, an installment fee, single-leg self-transfers. */
        private suspend fun seedLegacy(worlds: List<World>) {
            suspend fun store(type: FinancialTransactionType, amount: String, messages: List<ScopeMessage>) {
                for (message in messages) worlds.forEach { it.persist(message) }
                val ids = messages.map { it.first.rawSmsId }
                val row = FinancialTransaction(TransactionIdFactory.fromRawSmsIds(ids), type, Money.of(BigDecimal(amount), Currency.SAR),
                    messages[0].third, "account:BANK_ALJAZIRA:1111", "account:BANK_ALJAZIRA:2222", null, null, null,
                    messages.map { it.first.id }.sorted(), occurredAtZone = listOf(null, "Asia/Riyadh", "UTC").random(random))
                worlds.forEach { it.ledger.save(row, ids) }
            }
            log += "legacy rows"
            if (random.nextBoolean()) store(FinancialTransactionType.EXPENSE, "10.00", (0 until 3).map { purchase() })
            if (random.nextBoolean()) {
                val legs = listOf(
                    transfer(MessageFamily.TRANSFER_OUT, ACCOUNTS[0], ACCOUNTS[1], "100.00"),
                    transfer(MessageFamily.TRANSFER_IN, ACCOUNTS[0], ACCOUNTS[1], "100.00"),
                    transfer(MessageFamily.TRANSFER_OUT, ACCOUNTS[0], ACCOUNTS[1], "100.00", later = 3_600),
                )
                store(FinancialTransactionType.SELF_TRANSFER, "100.00", legs)
            }
            if (random.nextBoolean()) store(FinancialTransactionType.EXPENSE, "10.00", listOf(simple(MessageFamily.OTP, amount = null)))
            if (random.nextBoolean()) store(FinancialTransactionType.FEE, "9.00", listOf(installment()))
            repeat(random.nextInt(3)) {
                store(FinancialTransactionType.SELF_TRANSFER, "100.00", listOf(transfer(amount = "100.00")))
            }
        }

        private fun message(): ScopeMessage =
            when (random.nextInt(10)) {
                in 0..1 -> purchase()
                2 -> purchase(currency = Currency.USD)
                3 -> simple(MessageFamily.OTP, amount = null)
                4 -> installment()
                else -> transfer()
            }

        private fun purchase(currency: Currency = Currency.SAR) = simple(MessageFamily.PURCHASE, card = CARD, currency = currency,
            status = if (random.nextInt(6) == 0) ParseStatus.REVIEW_REQUIRED else ParseStatus.SUCCESS)

        private fun installment() = simple(MessageFamily.FINANCING_INSTALLMENT, source = ACCOUNTS[0], loan = true)

        private fun transfer(
            family: MessageFamily = listOf(MessageFamily.TRANSFER_OUT, MessageFamily.TRANSFER_IN).random(random),
            source: AccountReference? = null,
            destination: AccountReference? = null,
            amount: String = listOf("100.00", "100.00", "250.00").random(random),
            later: Long = 0,
        ): ScopeMessage {
            val bank = (source ?: ACCOUNTS.random(random)).bank
            val pool = ACCOUNTS.filter { it.bank == bank } + AccountReference(bank, "9999")
            val from = source ?: pool.random(random)
            val to = destination ?: pool.filter { it != from }.random(random)
            return simple(family, source = from, destination = to, bank = bank, amount = amount, later = later,
                network = listOf(BankNetworkType.INTRA_BANK, BankNetworkType.INTER_BANK).random(random),
                reference = listOf(null, "R1", "R2").random(random))
        }

        @Suppress("LongParameterList")
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
            currency: Currency = Currency.SAR,
            bank: Bank = Bank.BANK_ALJAZIRA,
            later: Long = 0,
        ): ScopeMessage {
            // Mostly close together, so transfers compete within the match window; sometimes a day later.
            clock = clock.plusSeconds(if (random.nextInt(5) == 0) 86_400 else random.nextLong(0, 240))
            val at = clock.plusSeconds(later)
            val id = "sms-${next++}"
            val event = ParsedEvent(
                id = "pe-$id", rawSmsId = id, bank = bank, messageFamily = family, direction = MoneyDirection.OUTGOING,
                amount = amount?.let { Money.of(BigDecimal(it), currency) }, purchaseChannel = null, sourceAccountRef = source,
                destinationAccountRef = destination, cardRef = card, merchant = "M$id", counterparty = null,
                occurredAt = at.takeIf { random.nextInt(6) == 0 }, bankNetworkType = network, confidence = Confidence(1.0), parseStatus = status,
            )
            val details = ParsedEventDetails(
                transactionReference = reference, loanType = LoanType.PERSONAL.takeIf { loan },
                occurredAtLocal = LocalDateTime.ofInstant(at, ZoneOffset.ofHours(3)).takeIf { random.nextBoolean() },
            )
            return Triple(event, details, at)
        }
    }

    private companion object {
        const val SEEDS = 150
        val T0: Instant = Instant.parse("2026-08-01T09:00:00Z")
        val AT: Instant = Instant.parse("2026-09-01T00:00:00Z")
        /** AlJazira has a fixed zone; SNB keeps the stored zone (`BankTransactionTimePolicy`). */
        val ACCOUNTS = listOf("1111", "2222", "3333").map { AccountReference(Bank.BANK_ALJAZIRA, it) } +
            listOf("5555", "6666").map { AccountReference(Bank.fromId("BANK_SNB"), it) }
        val CARD = CardReference(Bank.BANK_ALJAZIRA, "7271")
        val LOAN = LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)
    }
}

private typealias ScopeMessage = Triple<ParsedEvent, ParsedEventDetails, Instant>
