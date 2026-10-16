package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.BetaEnvironment
import com.baraa.masroof.lab.finance.posting.PostingKey
import com.baraa.masroof.lab.finance.value.Environment
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BetaLedgerMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BetaDatabase::class.java)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @After fun cleanup() { BetaContainer.resetOffline(context) }

    private fun versionFourFixture() {
        BetaContainer.resetOffline(context)
        helper.createDatabase(BetaDatabase.NAME, 4).apply {
            execSQL("INSERT INTO beta_shell_marker(id,note) VALUES ('v4','preserved')")
            execSQL("INSERT INTO beta_asset(environment,bankId,localId,kind,last4,parentLocalId,creditCardRole) VALUES ('BETA','BANK_ALJAZIRA','stable','ACCOUNT','1234',NULL,NULL)")
            execSQL("INSERT INTO beta_asset_alias(environment,bankId,assetLocalId,kind,value,evidenceId) VALUES ('BETA','BANK_ALJAZIRA','stable','LAST4','1234','manual-owned-confirmation')")
            execSQL("INSERT INTO beta_bank_selection(bankId,state) VALUES ('BANK_ALJAZIRA','DISABLED')")
            execSQL("INSERT INTO beta_raw_evidence VALUES ('raw','namespace','provider','EXPLICIT_SAMPLE','synthetic sender','synthetic preserved body','2026-10-17T00:00:00Z','USER_SUPPLIED','2026-10-17T00:00:00Z','fixture','','')")
            execSQL("INSERT INTO beta_evidence_outcome VALUES ('raw','REVIEW','fixture','reason','baseline-preserved','candidate-preserved','2026-10-17T00:00:00Z',2)")
            execSQL("INSERT INTO beta_evidence_retry VALUES ('raw','INTERPRETATION',2,'code','2026-10-17T00:00:00Z')")
            execSQL("INSERT INTO beta_evidence_decision VALUES ('raw',3,'KEEP_FOR_REVIEW','2026-10-17T00:00:00Z')")
            close()
        }
    }

    @Test fun versionFourAddsEmptyLedgerAndPreservesEveryExistingDataFamily() {
        versionFourFixture()
        helper.runMigrationsAndValidate(BetaDatabase.NAME, 5, true).apply {
            for (table in listOf("beta_ledger_current", "beta_ledger_history", "beta_ledger_evidence_claim", "beta_ledger_proof_claim")) {
                query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
            for (table in listOf("beta_shell_marker", "beta_asset", "beta_asset_alias", "beta_bank_selection", "beta_raw_evidence", "beta_evidence_outcome", "beta_evidence_retry", "beta_evidence_decision")) {
                query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            }
            query("SELECT body FROM beta_raw_evidence WHERE id='raw'").use { assertTrue(it.moveToFirst()); assertEquals("synthetic preserved body", it.getString(0)) }
            query("SELECT baselineJson,candidateJson,attempts FROM beta_evidence_outcome WHERE rawId='raw'").use {
                assertTrue(it.moveToFirst()); assertEquals("baseline-preserved", it.getString(0)); assertEquals("candidate-preserved", it.getString(1)); assertEquals(2, it.getInt(2))
            }
            query("SELECT revision,decision FROM beta_evidence_decision WHERE rawId='raw'").use { assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0)); assertEquals("KEEP_FOR_REVIEW", it.getString(1)) }
            query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            close()
        }
        BetaContainer(context).use { beta ->
            assertEquals(1, beta.markerCount()); assertEquals("stable", beta.assets().single().id.localId)
            assertNull(beta.financialPosting(PostingKey(Environment.BETA, "not-created")))
        }
    }

    @Test fun versionOneUpgradesThroughAllSchemasWithoutInventingFinancialRows() {
        BetaContainer.resetOffline(context)
        helper.createDatabase(BetaDatabase.NAME, 1).apply { execSQL("INSERT INTO beta_shell_marker(id,note) VALUES ('v1','preserved')"); close() }
        helper.runMigrationsAndValidate(BetaDatabase.NAME, 5, true).apply {
            query("SELECT note FROM beta_shell_marker WHERE id='v1'").use { assertTrue(it.moveToFirst()); assertEquals("preserved", it.getString(0)) }
            query("SELECT COUNT(*) FROM beta_ledger_current").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            close()
        }
    }

    @Test fun oldVersionFourBetaBackupRestoresAndMigratesWithoutAProductionDatabase() {
        versionFourFixture()
        val backup = File(context.filesDir, BetaEnvironment.BACKUP_DATABASE_FILE_NAME)
        context.getDatabasePath(BetaDatabase.NAME).copyTo(backup, overwrite = true)
        File(context.filesDir, BetaEnvironment.BACKUP_PREFS_FILE_NAME).writeText("${BetaContainer.PREFERENCE_PROBE_KEY}=v4-backup")
        context.deleteDatabase(BetaDatabase.NAME)
        assertTrue(BetaContainer.restoreOffline(context))
        BetaContainer(context).use { beta ->
            assertEquals("v4-backup", beta.preferenceProbe()); assertEquals(1, beta.markerCount())
            assertEquals("stable", beta.assets().single().id.localId)
            assertNull(beta.financialPosting(PostingKey(Environment.BETA, "absent")))
        }
    }
}
