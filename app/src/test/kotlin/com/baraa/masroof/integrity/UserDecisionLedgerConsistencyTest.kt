package com.baraa.masroof.integrity

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.GoldenLedgerCorpus
import com.baraa.masroof.application.dashboard.GoldenLedgerRunner
import com.baraa.masroof.application.dashboard.LedgerSnapshotCorpus
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.MigrationOpenPathContract
import com.baraa.masroof.data.room.UserDecisionBackfill
import com.baraa.masroof.data.room.UserDecisionBackfillContract
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import java.io.File
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 2.2: after the backfill, the decision store and the ledger agree on the snapshot corpus (golden worlds,
 * all banks, legacy states), the committed v18 fixture and the every-trace v19 file, both upgraded to v20.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserDecisionLedgerConsistencyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalZone: TimeZone = TimeZone.getDefault()
    private val fixture = {
        File(if (File("src/test").isDirectory) "." else "app", "src/test/resources/testdata/ledger_fixtures/v18/masroof.db").inputStream()
    }

    @Before
    fun pinZone() = TimeZone.setDefault(TimeZone.getTimeZone("Asia/Riyadh"))

    @After
    fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun decisionStoreAndLedgerAgreeOnTheCorpus() = runBlocking {
        val kinds = mutableListOf<String>()
        // The corpus worlds hold v19-style legacy traces; the backfill runs on them as MIGRATION_19_20 would.
        GoldenLedgerCorpus.loadAll().forEach { scenario ->
            DashboardLedgerWorld(context).use { world ->
                GoldenLedgerRunner.prepare(world, scenario)
                kinds += backfilledAndAgreeing(world)
            }
        }
        LedgerSnapshotCorpus.newAllBanksWorld(context).use { world ->
            LedgerSnapshotCorpus.populateAllBanks(world)
            LedgerSnapshotCorpus.populateExtras(world)
            kinds += backfilledAndAgreeing(world)
        }
        DashboardLedgerWorld(context).use { world ->
            LedgerSnapshotCorpus.populateLegacy(world)
            world.reconciliation.repairLegacyTransfersDetailed()
            kinds += backfilledAndAgreeing(world)
        }
        assertEquals(listOf("FINANCIAL_TYPE", "NON_FINANCIAL"), kinds.sorted())
    }

    @Test
    fun decisionStoreAndLedgerAgreeOnTheUpgradedV18FixtureAndV19File() {
        val v18 = MigrationOpenPathContract.copyFixture(context, "consistency-v18.db", fixture)
        val v19 = UserDecisionBackfillContract.v19File(context, "consistency-v19.db", fixture)
        val kinds = listOf(v18, v19).flatMap { file ->
            val db = MasroofDatabase.builder(context, MasroofDatabase::class.java, file.name).allowMainThreadQueries().build()
            try {
                assertAgrees(db.openHelper.writableDatabase)
            } finally {
                db.close()
                context.deleteDatabase(file.name)
            }
        }
        val all = setOf("FINANCIAL_TYPE", "EXTERNAL_TRANSFER", "SELF_TRANSFER_PAIR", "NON_FINANCIAL", "RESTORE", "CORRECTION",
            "BANK_SELECTION", "RECLASSIFY")
        assertEquals(all, kinds.toSet())
    }

    private fun backfilledAndAgreeing(world: DashboardLedgerWorld): List<String> {
        val db = world.db.openHelper.writableDatabase
        UserDecisionBackfill.run(db)
        return assertAgrees(db)
    }

    /** Checks the store against the ledger and the legacy stores; returns every decision kind found. */
    private fun assertAgrees(db: SupportSQLiteDatabase): List<String> {
        val decisions = db.rows("SELECT * FROM user_decision ORDER BY sequence")
        val outcomes = decisions.filter { it["kind"] in OUTCOME_KINDS }
        val latest = outcomes.flatMap { row -> listOfNotNull(row["rawSmsId"], row["pairedRawSmsId"]).map { it to row } }
            .associate { it }
        for ((rawSmsId, row) in latest) {
            val posted = db.rows(
                "SELECT f.* FROM financial_transaction_raw_sms_link l JOIN financial_transaction f ON f.id = l.transactionId " +
                    "WHERE l.rawSmsId = ?", rawSmsId,
            ).singleOrNull()
            when (row["kind"]) {
                in POSTING_KINDS -> assertEquals("posting columns of $rawSmsId",
                    posted?.let { listOf(it["type"], it["sourceContainerId"], it["destinationContainerId"], it["occurredAtEpochMillis"], it["occurredAtZone"]) }
                        ?: List(5) { null },
                    listOf(row["transactionType"], row["sourceContainerId"], row["destinationContainerId"], row["occurredAtEpochMillis"], row["occurredAtZone"]))
                in NOT_POSTED_KINDS -> assertNull("$rawSmsId is not financial but posted", posted)
            }
        }
        for (review in db.rows("SELECT * FROM review_item WHERE status = 'RESOLVED' AND resolutionKind IN ($USER_RESOLUTIONS)")) {
            assertNotNull("user-resolved review without a decision: ${review["rawSmsId"]}", latest[review["rawSmsId"]])
        }
        for (correction in db.rows("SELECT * FROM user_correction")) {
            assertTrue("correction without a decision: ${correction["id"]}", decisions.any {
                it["kind"] == "CORRECTION" && it["rawSmsId"] == correction["targetRawSmsId"] &&
                    it["decidedAtEpochMillis"] == correction["createdAtEpochMillis"]
            })
        }
        for (review in db.rows("SELECT * FROM review_item WHERE instr(reasons, 'user_selected_bank:') > 0")) {
            assertTrue("bank selection without a decision: ${review["rawSmsId"]}",
                decisions.any { it["kind"] == "BANK_SELECTION" && it["rawSmsId"] == review["rawSmsId"] })
        }
        return decisions.map { it.getValue("kind")!! }
    }

    private fun SupportSQLiteDatabase.rows(sql: String, vararg args: Any): List<Map<String, String?>> =
        query(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)) })
            }
        }

    private companion object {
        val POSTING_KINDS = setOf("FINANCIAL_TYPE", "EXTERNAL_TRANSFER", "SELF_TRANSFER_PAIR", "RECLASSIFY")
        val NOT_POSTED_KINDS = setOf("NON_FINANCIAL", "IGNORE", "RESTORE_REVERTED")
        val OUTCOME_KINDS = POSTING_KINDS + NOT_POSTED_KINDS + "RESTORE"
        const val USER_RESOLUTIONS = "'USER_FINANCIAL_TYPE', 'USER_EXTERNAL_TRANSFER', 'USER_SELF_TRANSFER_PAIR', 'USER_NON_FINANCIAL'"
    }
}
