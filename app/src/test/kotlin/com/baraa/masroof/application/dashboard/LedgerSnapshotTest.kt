package com.baraa.masroof.application.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone

/**
 * Full ledger snapshot (plan 0.2): every transaction row and link, review item, commitment and ownership
 * over the whole synthetic corpus, committed as `testdata/ledger_snapshot/full_ledger.golden.txt`.
 * Any row change fails this test. To accept an intended change, rerun with
 * `MASROOF_UPDATE_LEDGER_FIXTURES=1 ./gradlew :app:testDebugUnitTest --tests '*LedgerSnapshotTest'`
 * and review the diff of the golden file; the variable is off by default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerSnapshotTest {
    private val originalZone: TimeZone = TimeZone.getDefault()

    @Before
    fun pinZone() = TimeZone.setDefault(TimeZone.getTimeZone("Asia/Riyadh"))

    @After
    fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun ledgerMatchesCommittedSnapshot() = runBlocking {
        val actual = buildSnapshot()
        val file = goldenFile()
        if (System.getenv(UPDATE_ENV) == "1") {
            file.parentFile.mkdirs()
            file.writeText(actual)
        }
        assertEquals("ledger snapshot changed; regenerate only for an approved change", file.readText(), actual)
    }

    @Test
    fun snapshotCoversAllSevenBanks() = runBlocking {
        newAllBanksWorld().use { world ->
            LedgerSnapshotCorpus.populateAllBanks(world)
            val banks = world.parsedRepo.listAll().map { it.event.bank.id }.toSet()
            assertEquals(LedgerSnapshotCorpus.BANK_IDS.toSet(), banks)
        }
    }

    @Test
    fun scopedReconcileProducesTheSameLedgerAsFullReconcile() = runBlocking {
        val full = DashboardLedgerWorld(context()).use { world ->
            LedgerSnapshotCorpus.populateLegacy(world)
            world.reconciliation.reconcileStoredEventsDetailed()
            LedgerSnapshotSerializer.serialize(world)
        }
        val scoped = DashboardLedgerWorld(context()).use { world ->
            LedgerSnapshotCorpus.populateLegacy(world)
            world.reconciliation.reconcileAffectedRawSmsIds(world.rawRepo.listIdsByReceivedAt())
            LedgerSnapshotSerializer.serialize(world)
        }
        assertTrue("legacy corpus must post rows", full.contains("tx|"))
        assertEquals(full, scoped)
    }

    @Test
    fun legacySeedsKeepTodaysStates() = runBlocking {
        DashboardLedgerWorld(context()).use { world ->
            LedgerSnapshotCorpus.populateLegacy(world)
            val seeded = LedgerSnapshotSerializer.serialize(world)
            assertTrue(seeded.contains("|SELF_TRANSFER|"))
            assertEquals(2, seeded.lines().count { it.startsWith("tx|") && it.contains("|FEE|") })
            world.reconciliation.repairLegacyTransfersDetailed()
            val repaired = LedgerSnapshotSerializer.serialize(world)
            assertEquals(1, repaired.lines().count { it.startsWith("tx|") && it.contains("|FEE|") })
            assertTrue(repaired.contains("|LOAN_REPAYMENT|"))
        }
    }

    private suspend fun buildSnapshot(): String = buildString {
        GoldenLedgerCorpus.loadAll().forEach { scenario ->
            DashboardLedgerWorld(context()).use { world ->
                GoldenLedgerRunner.prepare(world, scenario)
                section("golden:${scenario.id}", world)
            }
        }
        newAllBanksWorld().use { world ->
            LedgerSnapshotCorpus.populateAllBanks(world)
            section("all_banks", world)
        }
        DashboardLedgerWorld(context()).use { world ->
            LedgerSnapshotCorpus.populateLegacy(world)
            section("legacy:seeded", world)
            world.reconciliation.repairLegacyTransfersDetailed()
            section("legacy:after_repair_and_full_reconcile", world)
        }
    }

    private suspend fun StringBuilder.section(name: String, world: DashboardLedgerWorld) {
        appendLine("## $name")
        append(LedgerSnapshotSerializer.serialize(world))
    }

    private fun newAllBanksWorld() = LedgerSnapshotCorpus.newAllBanksWorld(context())

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun goldenFile(): File =
        File(if (File("src/test").isDirectory) "." else "app", "src/test/resources/testdata/ledger_snapshot/full_ledger.golden.txt")

    private companion object {
        const val UPDATE_ENV = "MASROOF_UPDATE_LEDGER_FIXTURES"
    }
}
