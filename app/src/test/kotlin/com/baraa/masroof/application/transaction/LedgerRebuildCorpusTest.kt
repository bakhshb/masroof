package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.ForeignSarMarketRateProvider
import com.baraa.masroof.application.dashboard.GoldenLedgerCorpus
import com.baraa.masroof.application.dashboard.GoldenLedgerRunner
import com.baraa.masroof.application.dashboard.LedgerSnapshotCorpus
import com.baraa.masroof.application.dashboard.LedgerSnapshotSerializer
import com.baraa.masroof.data.room.UserDecisionBackfill
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import java.math.BigDecimal
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 3.3a: on every snapshot corpus world (built as `LedgerSnapshotTest` builds it, decisions backfilled as on an
 * upgraded device), emptying the ledger and reconciling rebuilds identical rows, and every commitment still names
 * a row. Exchange-rate pairs come back once enrichment runs again, as after any rebuild.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerRebuildCorpusTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalZone: TimeZone = TimeZone.getDefault()

    @Before
    fun pinZone() = TimeZone.setDefault(TimeZone.getTimeZone("Asia/Riyadh"))

    @After
    fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun emptyingAndRebuildingTheLedgerGivesIdenticalRows() = runBlocking {
        var rows = 0
        GoldenLedgerCorpus.loadAll().forEach { scenario ->
            DashboardLedgerWorld(context).use { world ->
                GoldenLedgerRunner.prepare(world, scenario)
                rows += assertRebuilds("golden:${scenario.id}", world)
            }
        }
        LedgerSnapshotCorpus.newAllBanksWorld(context).use { world ->
            LedgerSnapshotCorpus.populateAllBanks(world)
            LedgerSnapshotCorpus.populateExtras(world)
            rows += assertRebuilds("all_banks", world, enrich = true)
        }
        DashboardLedgerWorld(context).use { world ->
            LedgerSnapshotCorpus.populateLegacy(world)
            world.reconciliation.reconcileStoredEventsDetailed()
            // A FEE for an installment without a loan type cannot become a repayment: the keep rule holds it, and
            // nothing rebuilds it from nothing (docs/ARCHITECTURE.md, "Ledger writer").
            rows += assertRebuilds("legacy", world, storedOnly = setOf("android-sms:legacy-fee-no-loan"))
        }
        assertTrue("the corpus must post rows", rows > 20)
    }

    /** [storedOnly] are the raw SMS of the rows that exist only as stored rows; every other row must come back. */
    private suspend fun assertRebuilds(
        name: String,
        world: DashboardLedgerWorld,
        enrich: Boolean = false,
        storedOnly: Set<String> = emptySet(),
    ): Int {
        UserDecisionBackfill.run(world.db.openHelper.writableDatabase)
        assertTrue("$name: first pass", ReconciliationCompletionPolicy.isComplete(world.reconciliation.reconcileStoredEventsDetailed()))
        val before = ledgerLines(world)
        storedOnly.forEach { id -> assertTrue("$name: $id is not stored", before.any { "|$id|" in it }) }
        world.db.openHelper.writableDatabase.apply {
            execSQL("DELETE FROM financial_transaction_raw_sms_link")
            execSQL("DELETE FROM financial_transaction")
        }

        assertTrue("$name: rebuild", ReconciliationCompletionPolicy.isComplete(world.reconciliation.reconcileStoredEventsDetailed()))
        // Only the all-banks extras enriched rates, with this provider (`LedgerSnapshotCorpus.populateExtras`).
        if (enrich) world.exchangeRateEnrichmentWorkflow(ForeignSarMarketRateProvider { _, _ -> BigDecimal("3.75") }).enrichPending()

        assertEquals("$name: rebuilt ledger differs", before.filter { line -> storedOnly.none { "|$it|" in line } }, ledgerLines(world))
        val ids = world.ftRepo.listAll().map { it.id }.toSet()
        world.commitments.listAll().forEach { assertTrue("$name: commitment lost its row", it.sourceTransactionId in ids) }
        return ids.size
    }

    /** The transaction and commitment lines of the full snapshot. */
    private suspend fun ledgerLines(world: DashboardLedgerWorld): List<String> =
        LedgerSnapshotSerializer.serialize(world).lines().filter { it.startsWith("tx|") || it.startsWith("commitment|") }
}
