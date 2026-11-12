package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.interpretation.AutomaticInterpretationWriteGuard
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.ExternalTransferDirection
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomCommitmentRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomManualReviewResolutionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomTransactionIgnoreRepository
import com.baraa.masroof.data.repository.RoomTransactionReclassificationRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.repository.RoomUserDecisionRepository
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.mapper.FinancialTransactionMapper
import com.baraa.masroof.domain.ids.ReviewIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.ExchangeRateSource
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
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.NoOpAppLog
import com.baraa.masroof.testsupport.noOpProcessingRecovery
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Differential oracle for plan 3.3a. Random scenarios run on three in-memory databases wired like production (the
 * write guard, the review workflow, reclassify, ignore and restore): the pre-3.3a path with production entry points,
 * the pre-3.3a path with a full pass wherever production runs a scoped one, and the ledger writer. After every step
 * the writer must equal the old path per raw SMS set on row presence, type, amount, currency, containers and
 * exchange-rate pair; on the raw SMS set each commitment resolves to; on review status, kind and resolution per raw
 * SMS; and on whether the pass is incomplete (`summary.failed > 0`) and the failed raw SMS ids (the count itself is
 * only logged, and the old pass counted a failed pair and its follow-up writes separately). Zone, time, id, merchant and counterparty are not compared
 * (owner-accepted re-derivation, docs/ARCHITECTURE.md "Known differences").
 *
 * One exclusion, "Scope": a step may differ from the old scoped path only where the writer equals the old full-pass
 * path and every differing raw SMS lies inside the step's scope (a scoped result never differs from the full one; the
 * old scoped pass was narrower). After the first such step a scenario compares with the full-pass path only.
 *
 * Deleted together with the old path in plan 3.3b.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerWriterDifferentialTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theWriterMatchesTheOldPathOnRandomScenarios() = runBlocking {
        val seeds = System.getenv("MASROOF_DIFFERENTIAL_SEEDS")?.toIntOrNull() ?: SEEDS
        val failures = mutableListOf<String>()
        val shapes = sortedMapOf<String, Int>()
        val only = System.getenv("MASROOF_DIFFERENTIAL_SEED")?.toIntOrNull()
        for (seed in (only?.let { it..it } ?: 0 until seeds)) {
            val scenario = Scenario(Random(seed), shapes)
            World(context, scenario, legacy = true).use { old ->
                World(context, scenario, legacy = true, fullPassesOnly = true).use { oldFull ->
                    World(context, scenario, legacy = false).use { new ->
                        scenario.run(old, oldFull, new)?.let { failures += "seed $seed: $it\n${scenario.log.joinToString("\n")}" }
                        new.ledger.listAll().forEach { tx ->
                            shapes.merge("rows", 1, Int::plus)
                            if (new.ledger.listRawSmsIds(tx.id).size > 1) shapes.merge("rows of several SMS", 1, Int::plus)
                        }
                    }
                }
            }
            if (failures.size >= 3) break
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
        println("differential: $seeds scenarios, $shapes")
        assertTrue("$shapes", (shapes["rows"] ?: 0) > seeds * 2 && (shapes["rows of several SMS"] ?: 0) > seeds / 4)
    }

    /** One database; [legacy] runs the pre-3.3a path, [fullPassesOnly] a full pass wherever production runs a scoped one. */
    private class World(context: Context, scenario: Scenario, legacy: Boolean, private val fullPassesOnly: Boolean = false) : AutoCloseable {
        val db: MasroofDatabase = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java).allowMainThreadQueries().build()
        val raw = RoomRawSmsRepository(db.rawSmsDao())
        val parsed = RoomParsedEventRepository(db.parsedEventDao())
        val ledger = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        val reviews = RoomReviewRepository(db.reviewItemDao())
        val decisions = RoomUserDecisionRepository(db)
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        val loans = RoomLoanRegistryRepository.from(db)
        val commitments = RoomCommitmentRepository.from(db)
        private val clock = com.baraa.masroof.sms.time.InstantClock { AT }
        private val resolver = OwnershipResolver(accounts, cards, loans)
        private val confirmation = OwnershipConfirmationService(accounts, cards, loans)
        private val effective = EffectiveParsedEventProvider(parsed, decisions)
        private val queue = ReviewQueueUpdater(reviews, ledger, clock)
        private val guard = AutomaticInterpretationWriteGuard(
            effective, reviews, decisions, ledger,
            inferenceCurrent = { it.event.rawSmsId !in scenario.staleInference },
            inTransaction = { mutation -> db.withTransaction { mutation() } },
        )
        val service = TransactionReconciliationService(
            rawSmsRepository = raw, financialTransactionRepository = ledger, ownershipResolver = resolver,
            ownershipConfirmationService = confirmation, effectiveParsedEventProvider = effective,
            reviewRepository = reviews, userDecisionRepository = decisions, zoneId = scenario.deviceZone,
            automaticWrite = { expected, write -> guard.write(expected, write) },
            inTransaction = { write -> db.withTransaction { write() } }, useLegacyPath = { legacy },
        )
        val loader = LedgerInputLoader(raw, ledger, reviews, decisions, effective, resolver, scenario.deviceZone)
        val workflow = ReviewWorkflowService(
            reviewRepository = reviews, userCorrectionRepository = RoomUserCorrectionRepository(db.userCorrectionDao()),
            financialTransactionRepository = ledger, rawSmsRepository = raw, ownershipResolver = resolver,
            ownershipConfirmationService = confirmation, effectiveParsedEventProvider = effective, parsedEventRepository = parsed,
            reconciliationService = service, reviewQueueUpdater = queue,
            manualReviewResolutionRepository = RoomManualReviewResolutionRepository(db, ledger),
            userDecisionRepository = decisions, clock = clock, zoneId = scenario.deviceZone, appLogService = NoOpAppLog,
            processingRecovery = noOpProcessingRecovery(), ioDispatcher = Dispatchers.Unconfined,
        )
        val reclassify = TransactionReclassificationService(
            ledger, effective, resolver, confirmation, NoOpAppLog,
            RoomTransactionReclassificationRepository(db, ledger, DatabaseAccessGate()), Clock.fixed(AT, ZoneOffset.UTC),
        )
        val ignore = TransactionIgnoreService(RoomTransactionIgnoreRepository(db), clock, NoOpAppLog)
        val restore = TransactionRestoreService(reviews, decisions, ledger, service, reclassify, clock, NoOpAppLog, queue, noOpProcessingRecovery())

        /** Runs one pass and applies its report to the review queue, as the production callers do. */
        suspend fun reconcile(ids: List<String>?, repair: Boolean = false): String {
            val report = when {
                repair -> service.repairLegacyTransfersDetailed()
                ids == null || fullPassesOnly -> service.reconcileStoredEventsDetailed()
                else -> service.reconcileAffectedRawSmsIds(ids)
            }
            queue.applyReport(report)
            // The count is only logged; the old pass counted a failed pair and its follow-up writes separately.
            return "incomplete=${report.summary.failed > 0} ${report.failedRawSmsIds.sorted()}"
        }

        /** Rows (with their exchange-rate pair), commitments by the raw SMS set of their row, and every review. */
        suspend fun state(): Set<String> {
            val sets = mutableMapOf<String, List<String>>()
            val rows = ledger.listAll().map { tx ->
                val ids = ledger.listRawSmsIds(tx.id).sorted().also { sets[tx.id] = it }
                "row $ids ${tx.type} ${tx.amount.amount.toPlainString()} ${tx.amount.currency} ${tx.sourceContainerId} " +
                    "${tx.destinationContainerId} fx=${tx.appliedExchangeRate?.toPlainString()}/${tx.exchangeRateSource}"
            }
            val links = commitments.listAll().map { "commitment ${it.name} -> ${sets[it.sourceTransactionId] ?: "dangling"}" }
            val queue = reviews.listAll().map { "review ${it.rawSmsId} ${it.status} ${it.kind} ${it.resolutionKind}" }
            return (rows + links + queue).toSet()
        }

        /** Raw SMS a single-leg self-transfer links that is what is left of a stored row of several SMS. */
        suspend fun remnantSingleLegs(): Set<String> = ledger.listAll().mapNotNullTo(mutableSetOf()) { tx ->
            val ids = ledger.listRawSmsIds(tx.id)
            ids.singleOrNull()?.takeIf { tx.type == FinancialTransactionType.SELF_TRANSFER && tx.id != TransactionIdFactory.fromRawSmsIds(ids) }
        }

        /** The raw SMS a scoped pass for [ids] covers: the writer's scope. */
        suspend fun scopeOf(ids: List<String>): Set<String> {
            val inputs = loader.loadScope(ids)
            return inputs.events.map { it.event.rawSmsId }.toSet() + inputs.storedFacts.rows.flatMap { it.rawSmsIds } + ids
        }

        override fun close() = db.close()
    }

    /** A random sequence of steps, applied alike to every world. */
    private class Scenario(private val random: Random, private val shapes: MutableMap<String, Int>) {
        val log = mutableListOf<String>()
        val deviceZone: ZoneId = listOf(ZoneId.of("Asia/Riyadh"), ZoneId.of("UTC"), ZoneId.of("Asia/Dubai")).random(random)
        val staleInference = mutableSetOf<String>()
        private var next = 0
        private var clock = T0
        private val arrived = mutableListOf<String>()
        private var judgedByFullPasses = emptySet<String>()
        /** The writer's scope for this step's raw SMS, read before the step writes. */
        private var stepScope = emptySet<String>()
        /**
         * Raw SMS whose writer row is a stored row the keep rule holds where the old pass changed it, with that row's
         * line frozen when the exclusion first applied: "Fee with another amount" (seeded) and "Stale self-transfer
         * release" (found). The exclusion holds only while the writer's row is exactly that line (see [compare]).
         */
        private val frozen = mutableMapOf<String, String?>()
        /** The raw SMS set of the row each commitment was created on (exclusion "Re-keyed commitment"). */
        private val commitmentSets = mutableMapOf<String, List<String>>()

        private fun count(shape: String) { shapes.merge(shape, 1, Int::plus) }

        suspend fun run(old: World, oldFull: World, new: World): String? {
            val worlds = listOf(old, oldFull, new)
            val initial = ACCOUNTS.associateWith { listOf(OwnershipStatus.OWNED, OwnershipStatus.OWNED, OwnershipStatus.EXTERNAL, null).random(random) }
            for ((account, status) in initial) if (status != null) worlds.forEach { it.accounts.setOwnership(account, status) }
            worlds.forEach { it.cards.setOwnership(CARD, OwnershipStatus.OWNED) }
            log += "device zone $deviceZone, ownership ${initial.mapKeys { "${it.key.bank.id}:${it.key.maskedNumber}" }}"
            if (random.nextInt(3) == 0) seedLegacy(worlds)
            repeat(random.nextInt(4, 14)) { step ->
                val (affected, failed) = when (random.nextInt(20)) {
                    in 0..8 -> arrive(worlds)
                    9 -> changeOwnership(worlds)
                    in 10..13 -> decide(worlds)
                    14 -> commit(worlds)
                    15 -> { log += "legacy repair"; count("legacy repair"); null to worlds.map { it.reconcile(null, repair = true) } }
                    16 -> historicalBatch(worlds)
                    else -> { log += "full"; null to worlds.map { it.reconcile(null) } }
                }
                compare(old, oldFull, new, failed, affected)?.let { return "step $step: $it" }
            }
            log += "final full"
            return compare(old, oldFull, new, worlds.map { it.reconcile(null) }, null)
        }

        /**
         * Lines whose raw SMS all lie in [judgedByFullPasses] are compared with the old full-pass world, every other
         * line with the old scoped world. That set grows only by a step's own scope, and only when the step's whole
         * difference lies inside it (exclusion "Scope"). Lines naming a fee kept with another amount are left out
         * (exclusion "Fee with another amount").
         */
        private suspend fun compare(old: World, oldFull: World, new: World, failed: List<String>, affected: List<String>?): String? {
            fun ids(line: String) = ID.findAll(line).map { it.value }.toSet()
            val newState = new.state()
            val oldState = old.state()
            val fullState = oldFull.state()
            fun rowOf(state: Set<String>, id: String) = state.firstOrNull { it.startsWith("row ") && id in ids(it) }
            fun typeOf(line: String) = line.substringAfter("] ").substringBefore(" ")
            // Exclusion "Stale self-transfer release" (Known differences): the old pass unlinks the leg left alone in a
            // merged self-transfer once its time no longer matches, and its row disappears or is re-derived with
            // another type; the keep rule keeps the stored remnant.
            for (id in new.remnantSingleLegs()) {
                if (id in frozen) continue
                val differs = rowOf(oldState, id) != rowOf(newState, id) || rowOf(fullState, id) != rowOf(newState, id)
                if (!differs) continue
                frozen[id] = rowOf(newState, id)
                log += "  ($id: remnant single leg the old pass released: Stale self-transfer release)"
                count("raw SMS under: Stale self-transfer release")
            }
            // A frozen raw SMS is excluded only while the writer's row is still exactly the stored one and the old
            // world has no row for it, or one of another type; then its row and review lines are left out.
            fun excluded(state: Set<String>): Set<String> = frozen.filterTo(mutableMapOf()) { (id, line) ->
                val ownRow = rowOf(newState, id)
                val otherRow = rowOf(state, id)
                line != null && ownRow == line && (otherRow == null || typeOf(otherRow) != typeOf(line))
            }.keys
            // Exclusion "Re-keyed commitment" (Known differences): the old pass left a commitment naming a row it
            // deleted; the writer moves it to the row that replaced it, on a raw SMS set sharing the original's SMS.
            fun commitmentLine(state: Set<String>, name: String) = state.firstOrNull { it.startsWith("commitment $name ->") }
            fun rekeyed(state: Set<String>): Set<String> = commitmentSets.filterTo(mutableMapOf()) { (name, original) ->
                commitmentLine(state, name)?.endsWith("-> dangling") == true &&
                    commitmentLine(newState, name)?.let { line -> ids(line).any { it in original } } == true
            }.keys.also { names -> names.forEach { count("commitment comparisons under: Re-keyed commitment") } }
            // The writer's commitments resolve wherever the old pass resolves them.
            for (name in commitmentSets.keys) {
                if (commitmentLine(newState, name)?.endsWith("-> dangling") == true &&
                    commitmentLine(oldState, name)?.endsWith("-> dangling") != true
                ) return "commitment $name dangles in the writer only"
            }
            fun Set<String>.visible(other: Set<String>): Set<String> {
                val out = excluded(other)
                val names = rekeyed(other)
                return filterTo(mutableSetOf()) { line ->
                    ids(line).none { it in out } && names.none { line.startsWith("commitment $it ->") }
                }
            }
            val a = oldState.visible(oldState)
            val b = newState.visible(oldState)
            val full = fullState.visible(fullState)
            val bFull = newState.visible(fullState)
            // Inside the judged set each raw SMS must have exactly the old scoped lines or exactly the old full-pass
            // lines (a scoped report leaves an unwritten neighbour's review as the old scoped pass did); outside it
            // every line must be the old scoped one.
            fun agrees(judged: Set<String>): Boolean {
                fun inScope(line: String) = ids(line).let { it.isNotEmpty() && judged.containsAll(it) }
                fun linesOf(state: Set<String>, id: String) = state.filterTo(mutableSetOf()) { inScope(it) && id in ids(it) }
                return b.filterNot(::inScope).toSet() == a.filterNot(::inScope).toSet() && judged.all { id ->
                    linesOf(b, id) == linesOf(a, id) || linesOf(bFull, id) == linesOf(full, id)
                }
            }
            // Against the full-pass world only for failures inside the judged set.
            fun failedInside(judged: Set<String>) = failed[2] == failed[1] && judged.containsAll(ids(failed[2]))
            val failedAgrees = failed[2] == failed[0] || (judgedByFullPasses.isNotEmpty() && failedInside(judgedByFullPasses))
            if ((a == b || agrees(judgedByFullPasses)) && failedAgrees) return null
            if (affected != null) {
                val widened = judgedByFullPasses + stepScope
                if (agrees(widened) && (failed[2] == failed[0] || failedInside(widened))) {
                    log += "  (differs from the old scoped path only inside the step's scope, where it equals the old full-pass path: Scope)"
                    count("steps allowed under Scope")
                    judgedByFullPasses = widened
                    return null
                }
                log += "  scope of the step: ${stepScope.sorted()}"
            }
            return "failed old=${failed[0]} oldFull=${failed[1]} new=${failed[2]}\n" +
                "--- only old\n${(a - b).sorted().joinToString("\n")}\n--- only old, full passes\n${(full - b).sorted().joinToString("\n")}\n" +
                "--- only new (against old)\n${(b - a).sorted().joinToString("\n")}"
        }

        private suspend fun arrive(worlds: List<World>): Pair<List<String>, List<String>> {
            val batch = (0 until listOf(1, 1, 1, 2, 3).random(random)).map { message() }
            log += "arrive ${batch.joinToString { describe(it) }}"
            for (message in batch) worlds.forEach { it.persist(message) }
            val ids = batch.map { it.first.rawSmsId }
            arrived += ids
            stepScope = worlds[2].scopeOf(ids)
            return ids to worlds.map { it.reconcile(ids) }
        }

        /** More than 200 SMS at once: the historical batch, which reads the whole history. */
        private var batched = false

        private suspend fun historicalBatch(worlds: List<World>): Pair<List<String>?, List<String>> {
            // At most one batch, in a fraction of the scenarios: 205 SMS in three worlds is the slowest step.
            if (batched || random.nextInt(5) != 0) return null to worlds.map { it.reconcile(null) }.also { log += "full" }
            batched = true
            val batch = (0 until 205).map { if (it % 20 == 0) transfer() else purchase() }
            log += "historical batch of ${batch.size}"
            count("historical batch")
            for (message in batch) worlds.forEach { it.persist(message) }
            val ids = batch.map { it.first.rawSmsId }
            arrived += ids
            stepScope = worlds[2].scopeOf(ids)
            return ids to worlds.map { it.reconcile(ids) }
        }

        private suspend fun changeOwnership(worlds: List<World>): Pair<List<String>, List<String>> {
            if (random.nextInt(4) == 0) {
                val status = listOf(OwnershipStatus.OWNED, OwnershipStatus.EXTERNAL).random(random)
                log += "loan ownership -> $status"
                count("loan ownership change")
                worlds.forEach { it.loans.setOwnership(LOAN, status) }
                val ids = worlds[2].parsed.listRawSmsIdsReferencingLoan(LOAN)
                stepScope = worlds[2].scopeOf(ids)
                return ids to worlds.map { it.reconcile(ids) }
            }
            val account = ACCOUNTS.random(random)
            val status = listOf(OwnershipStatus.OWNED, OwnershipStatus.EXTERNAL).random(random)
            log += "ownership ${account.bank.id}:${account.maskedNumber} -> $status"
            count("account ownership change")
            worlds.forEach { it.accounts.setOwnership(account, status) }
            val ids = worlds[2].parsed.listRawSmsIdsReferencingAccount(account)
            stepScope = worlds[2].scopeOf(ids)
            return ids to worlds.map { it.reconcile(ids) }
        }

        /** A user decision through the real workflow, on the same raw SMS in every world. */
        private suspend fun decide(worlds: List<World>): Pair<List<String>?, List<String>> {
            val newWorld = worlds[2]
            val open = arrived.filter { newWorld.reviews.findByRawSmsId(it)?.status == ReviewStatus.REQUIRED }
            val posted = arrived.filter { id -> newWorld.ledger.findByRawSmsId(id)?.let { newWorld.ledger.listRawSmsIds(it.id).size == 1 } == true }
            val ignored = arrived.filter { newWorld.decisions.effectiveFor(it).isNonFinancial }
            val kind = random.nextInt(8)
            val subject = when (kind) {
                in 0..4 -> open.randomOrNull(random)
                5, 6 -> posted.randomOrNull(random)
                else -> ignored.randomOrNull(random)
            } ?: return null to worlds.map { it.reconcile(null) }
            val family = newWorld.parsed.findByRawSmsId(subject)?.event?.messageFamily
            val name = when (kind) {
                0 -> "resolve not financial"
                1 -> "resolve external"
                2 -> "resolve financial type"
                3 -> "correct amount"
                4 -> "resolve pair"
                5 -> "reclassify"
                6 -> "ignore"
                else -> "restore"
            }
            val partner = open.filter { it != subject }.firstOrNull { id ->
                newWorld.parsed.findByRawSmsId(id)?.event?.messageFamily.let { it != family && it?.let { f -> f == MessageFamily.TRANSFER_IN || f == MessageFamily.TRANSFER_OUT } == true }
            }
            log += "decide $name on $subject${if (kind == 4) " with $partner" else ""}"
            stepScope = worlds[2].scopeOf(listOfNotNull(subject, partner.takeIf { kind == 4 }))
            count("decision: $name")
            val outcomes = worlds.map { world ->
                val review = ReviewIdFactory.fromRawSmsId(subject)
                val result: Any? = when (kind) {
                    0 -> world.workflow.resolveAsNonFinancial(review)
                    1 -> world.workflow.resolveTransferAsExternal(review,
                        if (family == MessageFamily.TRANSFER_IN) ExternalTransferDirection.INCOMING else ExternalTransferDirection.OUTGOING)
                    2 -> world.workflow.resolveAsFinancialType(review, FinancialTransactionType.EXPENSE)
                    3 -> world.workflow.applyCorrection(review, correctedAmount = Money.of(BigDecimal("33.00"), Currency.SAR))
                    4 -> partner?.let {
                        val (out, inn) = if (family == MessageFamily.TRANSFER_OUT) subject to it else it to subject
                        world.workflow.resolveSelfTransferPair(ReviewIdFactory.fromRawSmsId(out), ReviewIdFactory.fromRawSmsId(inn))
                    }
                    5 -> world.ledger.findByRawSmsId(subject)?.let { world.reclassify.reclassify(it.id, FinancialTransactionType.INCOME) }
                    6 -> world.ledger.findByRawSmsId(subject)?.let { world.ignore.ignore(it.id) }
                    else -> world.restore.restore(subject)
                }
                result?.javaClass?.simpleName ?: "none"
            }
            log += "  outcomes $outcomes"
            // The workflows reconcile what they decide themselves; their outcome must agree too.
            return listOfNotNull(subject, partner.takeIf { kind == 4 }) to outcomes.map { "decision $it" }
        }

        /** A commitment created from the same posted row in every world. */
        private suspend fun commit(worlds: List<World>): Pair<List<String>?, List<String>> {
            val candidates = worlds[2].ledger.listAll().filter { tx -> worlds.all { it.ledger.getById(tx.id) != null } }
            val tx = candidates.randomOrNull(random) ?: return null to worlds.map { it.reconcile(null) }
            val name = "c${next++}"
            log += "commitment $name on ${tx.id.takeLast(6)}"
            commitmentSets[name] = worlds[2].ledger.listRawSmsIds(tx.id)
            count("commitment")
            worlds.forEach {
                runCatching {
                    it.commitments.create(Commitment(name, name, tx.amount, LocalDate.parse("2026-08-01"), null, null, true,
                        sourceTransactionId = tx.id, createdAt = AT, updatedAt = AT))
                }
            }
            return null to worlds.map { it.reconcile(null) }
        }

        /** Legacy rows as older versions left them, the same in every world. */
        private suspend fun seedLegacy(worlds: List<World>) {
            suspend fun store(transaction: FinancialTransaction, messages: List<Triple<ParsedEvent, ParsedEventDetails, Instant>>) {
                for (message in messages) worlds.forEach { it.persist(message) }
                val ids = messages.map { it.first.rawSmsId }
                worlds.forEach { it.ledger.save(transaction.copy(linkedParsedEventIds = messages.map { m -> m.first.id }.sorted()), ids) }
                arrived += ids
            }
            fun row(type: FinancialTransactionType, amount: String, at: Instant, ids: List<String>, currency: Currency = Currency.SAR) =
                FinancialTransaction(TransactionIdFactory.fromRawSmsIds(ids), type, Money.of(BigDecimal(amount), currency), at,
                    "account:BANK_ALJAZIRA:1111", "account:BANK_ALJAZIRA:2222", null, null, null, emptyList(),
                    occurredAtZone = listOf(null, "Asia/Riyadh", "UTC").random(random))
            val shapesSeeded = mutableListOf<String>()
            if (random.nextBoolean()) {
                val p = (0 until 3).map { purchase() }
                store(row(FinancialTransactionType.EXPENSE, "10.00", p[0].third, p.map { it.first.rawSmsId }), p)
                shapesSeeded += "3-SMS expense"
            }
            if (random.nextBoolean()) {
                val out = transfer(MessageFamily.TRANSFER_OUT, ACCOUNTS[0], ACCOUNTS[1], "100.00")
                val inn = transfer(MessageFamily.TRANSFER_IN, ACCOUNTS[0], ACCOUNTS[1], "100.00")
                val stale = transfer(MessageFamily.TRANSFER_OUT, ACCOUNTS[0], ACCOUNTS[1], "100.00", later = 3_600)
                val legs = listOf(out, inn, stale)
                store(row(FinancialTransactionType.SELF_TRANSFER, "100.00", out.third, legs.map { it.first.rawSmsId }), legs)
                shapesSeeded += "3-SMS self-transfer with a stale leg"
            }
            if (random.nextBoolean()) {
                val p = (0 until 2).map { purchase() }
                store(row(FinancialTransactionType.EXPENSE, "10.00", p[0].third, p.map { it.first.rawSmsId }), p)
                shapesSeeded += "2-SMS non-pair row"
            }
            if (random.nextBoolean()) {
                val otp = simple(MessageFamily.OTP, amount = null)
                store(row(FinancialTransactionType.EXPENSE, "10.00", otp.third, listOf(otp.first.rawSmsId)), listOf(otp))
                shapesSeeded += "posted informational SMS"
            }
            if (random.nextBoolean()) {
                val fee = installment()
                val other = random.nextBoolean()
                val amount = if (other) "9.00" else fee.first.amount!!.amount.toPlainString()
                val stored = row(FinancialTransactionType.FEE, amount, fee.third, listOf(fee.first.rawSmsId)).copy(destinationContainerId = null)
                store(stored, listOf(fee))
                if (other) {
                    // Exclusion "Fee with another amount": the old heal takes the derived amount; the keep rule keeps the fee.
                    frozen[fee.first.rawSmsId] = worlds[2].state().first { it.startsWith("row [${fee.first.rawSmsId}]") }
                    count("raw SMS under: Fee with another amount")
                }
                shapesSeeded += "stored FEE for an installment"
            }
            if (random.nextBoolean()) {
                val usd = purchase(currency = Currency.USD)
                val half = random.nextBoolean()
                store(row(FinancialTransactionType.EXPENSE, usd.first.amount!!.amount.toPlainString(), usd.third, listOf(usd.first.rawSmsId), Currency.USD)
                    .copy(sourceContainerId = "card:BANK_ALJAZIRA:7271", destinationContainerId = null, appliedExchangeRate = BigDecimal("3.75"),
                        exchangeRateSource = ExchangeRateSource.SMS.takeIf { !half }), listOf(usd))
                shapesSeeded += if (half) "half exchange-rate pair" else "complete exchange-rate pair"
            }
            if (random.nextBoolean()) {
                val linkless = row(FinancialTransactionType.EXPENSE, "10.00", T0, listOf("none")).copy(id = "transaction:legacy-linkless",
                    appliedExchangeRate = BigDecimal("3.75"), exchangeRateSource = ExchangeRateSource.SMS)
                worlds.forEach { it.db.financialTransactionDao().insertTransactionIfAbsent(FinancialTransactionMapper.toEntity(linkless)) }
                shapesSeeded += "link-less row"
            }
            shapesSeeded.forEach { count("legacy: $it") }
            log += "legacy $shapesSeeded"
        }

        private fun message(): Triple<ParsedEvent, ParsedEventDetails, Instant> =
            when (random.nextInt(12)) {
                in 0..1 -> purchase()
                2 -> purchase(currency = Currency.USD)
                3 -> simple(MessageFamily.REFUND, card = CARD)
                4 -> simple(MessageFamily.OTP, amount = null)
                5 -> installment()
                else -> transfer()
            }.also { if (random.nextInt(12) == 0) staleInference += it.first.rawSmsId.also { id -> log += "  stale inference on $id" } }

        private fun purchase(currency: Currency = Currency.SAR) = simple(MessageFamily.PURCHASE, card = CARD, currency = currency,
            status = if (random.nextInt(6) == 0) ParseStatus.REVIEW_REQUIRED else ParseStatus.SUCCESS)

        private fun installment() = simple(MessageFamily.FINANCING_INSTALLMENT, source = ACCOUNTS[0], loan = true)

        private fun transfer(
            family: MessageFamily = listOf(MessageFamily.TRANSFER_OUT, MessageFamily.TRANSFER_IN).random(random),
            source: AccountReference? = null,
            destination: AccountReference? = null,
            amount: String = listOf("100.00", "100.00", "250.00").random(random),
            later: Long = 0,
        ): Triple<ParsedEvent, ParsedEventDetails, Instant> {
            val bank = BANKS.random(random)
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
        ): Triple<ParsedEvent, ParsedEventDetails, Instant> {
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

        private fun describe(message: Triple<ParsedEvent, ParsedEventDetails, Instant>): String {
            val (event, details, at) = message
            return "${event.rawSmsId} ${event.bank.id} ${event.messageFamily} ${event.amount} ${event.sourceAccountRef?.maskedNumber}->" +
                "${event.destinationAccountRef?.maskedNumber} ${event.bankNetworkType} ref=${details.transactionReference} " +
                "local=${details.occurredAtLocal != null} occurred=${event.occurredAt != null} ${event.parseStatus} at=$at"
        }
    }

    private companion object {
        const val SEEDS = 200
        val T0: Instant = Instant.parse("2026-08-01T09:00:00Z")
        val AT: Instant = Instant.parse("2026-09-01T00:00:00Z")
        /** AlJazira has a fixed zone; SNB keeps the stored zone (`BankTransactionTimePolicy`). */
        val BANKS = listOf(Bank.BANK_ALJAZIRA, Bank.BANK_ALJAZIRA, Bank.fromId("BANK_SNB"))
        val ACCOUNTS = listOf("1111", "2222", "3333").map { AccountReference(Bank.BANK_ALJAZIRA, it) } +
            listOf("5555", "6666").map { AccountReference(Bank.fromId("BANK_SNB"), it) }
        val CARD = CardReference(Bank.BANK_ALJAZIRA, "7271")
        val LOAN = LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)
        val ID = Regex("sms-\\d+")

        suspend fun World.persist(message: Triple<ParsedEvent, ParsedEventDetails, Instant>) {
            val (event, details, at) = message
            raw.insertIfAbsent(RawSms(event.rawSmsId, "AlJazira", "body-${event.rawSmsId}", at, event.rawSmsId, SmsBodyHasher.sha256Hex(event.rawSmsId)))
            parsed.save(event, details)
            if (details.loanType != null) loans.observe(LOAN, event.rawSmsId)
            // Every arrival has a review a later decision can resolve, as the ingestion review service gives it.
            reviews.upsertRequired(event.rawSmsId, com.baraa.masroof.domain.model.ReviewKind.NEEDS_REVIEW, listOf("needs_review"), at)
        }
    }
}
