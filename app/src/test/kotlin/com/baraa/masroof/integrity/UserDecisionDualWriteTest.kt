package com.baraa.masroof.integrity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.review.ExplicitBankSelectionWorkflow
import com.baraa.masroof.application.review.ExternalTransferDirection
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.review.ReviewWorkflowResult
import com.baraa.masroof.application.transaction.IgnoreResult
import com.baraa.masroof.application.transaction.ReclassificationResult
import com.baraa.masroof.application.transaction.RestoreResult
import com.baraa.masroof.application.transaction.TransactionIgnoreService
import com.baraa.masroof.application.transaction.TransactionReclassificationService
import com.baraa.masroof.application.transaction.TransactionRestoreService
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomTransactionIgnoreRepository
import com.baraa.masroof.data.repository.RoomTransactionReclassificationRepository
import com.baraa.masroof.data.repository.RoomUserDecisionRepository
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.entity.UserDecisionEntity
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import com.baraa.masroof.testsupport.NoOpAppLog
import com.baraa.masroof.testsupport.engineFixtureAdapter
import com.baraa.masroof.testsupport.noOpProcessingRecovery
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 2.1: every decision kind, made through its real service, appends exactly one `user_decision` row in the
 * same transaction as its existing write. A failure injected at the decision insert (a trigger that aborts after
 * the row is written) leaves no row and every table exactly as before, so the existing write rolled back too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserDecisionDualWriteTest {
    @Test
    fun resolveAsFinancialType_appendsOneRow_andRollsBackWithIt() = eachOutcome { world, ledger ->
        val rawId = unpostedPurchase(world, ledger, "financial-type")
        val action = suspend { ledger.workflow.resolveAsFinancialType(review(world, rawId), FinancialTransactionType.FEE) }
        Case(action, accepted = { it is ReviewWorkflowResult.Success }) {
            row(rawId, "FINANCIAL_TYPE", transactionType = "FEE", source = "account:BANK_ALJAZIRA:3001")
        }
    }

    @Test
    fun resolveTransferAsExternal_appendsOneRow_andRollsBackWithIt() = eachOutcome(transfer = true) { world, ledger ->
        val batch = ledger.openBatch()
        val rawId = (batch.ingestProvider(providerRow("dual-ext", intraOut("2026-09-03 10:38"), "2026-09-03T07:38:00Z"))
            as SmsIngestionResult.Parsed).rawSmsId
        required(world, rawId)
        val action = suspend { ledger.workflow.resolveTransferAsExternal(review(world, rawId), ExternalTransferDirection.OUTGOING) }
        Case(action, accepted = { it is ReviewWorkflowResult.Success }) {
            row(rawId, "EXTERNAL_TRANSFER", transactionType = "SELF_TRANSFER",
                source = "account:BANK_ALJAZIRA:3001", destination = "account:BANK_ALJAZIRA:3002")
        }
    }

    @Test
    fun resolveSelfTransferPair_appendsOneRowNamingBothLegs_andRollsBackWithIt() = eachOutcome(transfer = true) { world, ledger ->
        val batch = ledger.openBatch()
        for (row in ambiguousTie("dual")) assertTrue(batch.ingestProvider(row) is SmsIngestionResult.Parsed)
        batch.finish()
        val outgoing = rawIdFor(world, MessageFamily.TRANSFER_OUT)
        val incoming = rawIdFor(world, MessageFamily.TRANSFER_IN)
        val action = suspend { ledger.workflow.resolveSelfTransferPair(review(world, outgoing), review(world, incoming)) }
        Case(action, accepted = { it is ReviewWorkflowResult.Success }) {
            row(outgoing, "SELF_TRANSFER_PAIR", paired = incoming, transactionType = "SELF_TRANSFER",
                source = "account:BANK_ALJAZIRA:3001", destination = "account:BANK_ALJAZIRA:3002")
        }
    }

    @Test
    fun resolveAsNonFinancial_appendsOneRow_andRollsBackWithIt() = eachOutcome { world, ledger ->
        val rawId = reviewRequiredNotice(ledger, "non-financial")
        val action = suspend { ledger.workflow.resolveAsNonFinancial(review(world, rawId)) }
        Case(action, accepted = { it is ReviewWorkflowResult.Success }) { row(rawId, "NON_FINANCIAL") }
    }

    @Test
    fun applyCorrection_appendsOneRowWithTheCorrection_andRollsBackWithIt() = eachOutcome { world, ledger ->
        val rawId = reviewRequiredNotice(ledger, "correction")
        val action = suspend {
            ledger.workflow.applyCorrection(review(world, rawId), correctedType = MessageFamily.PURCHASE,
                correctedAmount = Money.of("12.50", Currency.SAR), correctedMerchant = "TEST_CORRECTION")
        }
        Case(action, accepted = { it is ReviewWorkflowResult.Success }) {
            row(rawId, "CORRECTION").copy(messageFamily = "PURCHASE", amountDecimal = "12.50", amountCurrency = "SAR",
                merchant = "TEST_CORRECTION")
        }
    }

    @Test
    fun reclassify_appendsOneRow_andRollsBackWithIt() = eachOutcome { world, ledger ->
        val rawId = postedPurchase(ledger, "reclassify")
        val transactionId = world.ftRepo.findByRawSmsId(rawId)!!.id
        val action = suspend { reclassification(world, ledger).reclassify(transactionId, FinancialTransactionType.FEE) }
        Case(action, accepted = { it is ReclassificationResult.Success }) {
            row(rawId, "RECLASSIFY", transactionType = "FEE", source = "account:BANK_ALJAZIRA:3001")
        }
    }

    @Test
    fun ignore_appendsOneRow_andRollsBackWithIt() = eachOutcome { world, ledger ->
        val rawId = postedPurchase(ledger, "ignore")
        val transactionId = world.ftRepo.findByRawSmsId(rawId)!!.id
        val action = suspend { ignore(world, ledger).ignore(transactionId) }
        Case(action, accepted = { it == IgnoreResult.Success }) { row(rawId, "IGNORE") }
    }

    @Test
    fun restore_appendsOneRow_andRollsBackWithIt() = eachOutcome { world, ledger ->
        val rawId = postedPurchase(ledger, "restore")
        assertEquals(IgnoreResult.Success, ignore(world, ledger).ignore(world.ftRepo.findByRawSmsId(rawId)!!.id))
        world.db.openHelper.writableDatabase.execSQL("DELETE FROM user_decision")
        val action = suspend { restore(world, ledger).restore(rawId) }
        Case(action, accepted = { it is RestoreResult.Success }) { row(rawId, "RESTORE") }
    }

    @Test
    fun bankSelection_appendsOneRow_andRollsBackWithIt() = eachOutcome { world, ledger ->
        val rawId = reviewRequiredNotice(ledger, "bank-selection")
        val workflow = ExplicitBankSelectionWorkflow(world.reviewRepo, RoomUserDecisionRepository(world.db), world.rawRepo,
            world.parsedRepo, BankSmsRegistry(listOf(engineFixtureAdapter())), ledger.liveProcessing, ledger.clock)
        val action = suspend { workflow.selectAndReparse(rawId, Bank.BANK_ALJAZIRA.id) }
        Case(action, accepted = { true }) { row(rawId, "BANK_SELECTION").copy(bankId = Bank.BANK_ALJAZIRA.id) }
    }

    /** What a case does, whether its outcome is a success, and the one row it must append. */
    private class Case<T>(val action: suspend () -> T, val accepted: (T) -> Boolean, val expected: () -> UserDecisionEntity)

    /** Runs [setUp] twice on fresh worlds: once the action commits one row, once a failing insert rolls it all back. */
    private fun <T> eachOutcome(transfer: Boolean = false, setUp: suspend (DashboardLedgerWorld, IntegrityLedger) -> Case<T>) =
        runBlocking {
            DashboardLedgerWorld(context()).use { world ->
                val case = setUp(world, if (transfer) transferLedger(world) else purchaseLedger(world))
                assertEquals("no decision before the action", emptyList<UserDecisionEntity>(), world.db.userDecisionDao().listAll())
                val result = case.action()
                assertTrue("rejected: $result", case.accepted(result))
                assertEquals(listOf(case.expected()), world.db.userDecisionDao().listAll().map { it.copy(sequence = 0) })
            }
            DashboardLedgerWorld(context()).use { world ->
                val case = setUp(world, if (transfer) transferLedger(world) else purchaseLedger(world))
                world.db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_decision AFTER INSERT ON user_decision BEGIN SELECT RAISE(ABORT, 'injected'); END",
                )
                val before = tables(world.db)
                val outcome = runCatching { case.action() }
                assertTrue("the failed write was reported as a success: $outcome",
                    outcome.isFailure || !case.accepted(outcome.getOrThrow()))
                assertEquals(before, tables(world.db))
                assertEquals(emptyList<UserDecisionEntity>(), world.db.userDecisionDao().listAll())
            }
        }

    private fun row(
        rawSmsId: String,
        kind: String,
        paired: String? = null,
        transactionType: String? = null,
        source: String? = null,
        destination: String? = null,
    ) = UserDecisionEntity(
        rawSmsId = rawSmsId, pairedRawSmsId = paired, kind = kind, decidedAtEpochMillis = NOW.toEpochMilli(),
        transactionType = transactionType, sourceContainerId = source, destinationContainerId = destination,
        messageFamily = null, amountDecimal = null, amountCurrency = null, merchant = null, counterparty = null, bankId = null,
    )

    private suspend fun unpostedPurchase(world: DashboardLedgerWorld, ledger: IntegrityLedger, id: String): String {
        val parsed = ledger.openBatch().ingestProvider(providerRow(id, bakeryPurchaseBody(), "2026-09-05T06:15:00Z"))
        return (parsed as SmsIngestionResult.Parsed).rawSmsId.also { required(world, it) }
    }

    private suspend fun postedPurchase(ledger: IntegrityLedger, id: String): String {
        val batch = ledger.openBatch()
        val parsed = batch.ingestProvider(providerRow(id, bakeryPurchaseBody(), "2026-09-05T06:15:00Z")) as SmsIngestionResult.Parsed
        batch.finish()
        return parsed.rawSmsId
    }

    private suspend fun reviewRequiredNotice(ledger: IntegrityLedger, id: String): String {
        val batch = ledger.openBatch()
        val parsed = batch.ingestProvider(providerRow(id, unknownBankNoticeBody(), "2026-09-04T05:05:00Z"))
        batch.finish()
        return (parsed as SmsIngestionResult.ReviewRequired).rawSmsId
    }

    private suspend fun required(world: DashboardLedgerWorld, rawSmsId: String) {
        world.reviewRepo.upsertRequired(rawSmsId, ReviewKind.NEEDS_REVIEW, listOf("dual_write"), NOW)
    }

    private suspend fun review(world: DashboardLedgerWorld, rawSmsId: String) = world.reviewRepo.findByRawSmsId(rawSmsId)!!.id

    private suspend fun rawIdFor(world: DashboardLedgerWorld, family: MessageFamily) =
        world.parsedRepo.listAll().first { it.event.messageFamily == family }.event.rawSmsId

    private fun reclassification(world: DashboardLedgerWorld, ledger: IntegrityLedger) = TransactionReclassificationService(
        financialTransactionRepository = world.ftRepo,
        effectiveParsedEventProvider = EffectiveParsedEventProvider(world.parsedRepo, ledger.corrections),
        ownershipResolver = OwnershipResolver(world.accounts, world.cards, world.loans),
        ownershipConfirmationService = OwnershipConfirmationService(world.accounts, world.cards, world.loans),
        appLogService = NoOpAppLog,
        persistence = RoomTransactionReclassificationRepository(world.db, world.ftRepo, DatabaseAccessGate()),
        clock = Clock.fixed(NOW, world.zone),
    )

    private fun ignore(world: DashboardLedgerWorld, ledger: IntegrityLedger) =
        TransactionIgnoreService(RoomTransactionIgnoreRepository(world.db), ledger.clock, NoOpAppLog)

    private fun restore(world: DashboardLedgerWorld, ledger: IntegrityLedger) = TransactionRestoreService(
        reviewRepository = world.reviewRepo,
        userDecisionRepository = RoomUserDecisionRepository(world.db),
        financialTransactionRepository = world.ftRepo,
        reconciliation = ledger.productionReconciliation,
        reclassification = reclassification(world, ledger),
        clock = ledger.clock,
        appLogService = NoOpAppLog,
        reviewQueueUpdater = ReviewQueueUpdater(world.reviewRepo, world.ftRepo, ledger.clock),
        processingRecovery = noOpProcessingRecovery(),
    )

    /** Every app table's rows in rowid order. */
    private fun tables(db: MasroofDatabase): Map<String, List<String>> {
        val sql = db.openHelper.readableDatabase
        val names = sql.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'room_%' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_%' ORDER BY name",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return names.associateWith { table ->
            sql.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { cursor.getString(it) ?: "null" })
                }
            }
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private companion object {
        /** `IntegrityLedger.clock` and the fixed clock given to reclassification. */
        val NOW: Instant = Instant.parse("2026-09-15T09:00:00Z")
    }
}
