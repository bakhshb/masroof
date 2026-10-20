package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.application.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BetaCommitmentMigrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),BetaDatabase::class.java)
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun seed() {
        helper.createDatabase(BetaDatabase.NAME,14).use {db ->
            db.execSQL("INSERT INTO beta_commitment (id,label,amountDecimal,amountCurrency,dueDay,starts,active,paidMonths,pauseIntervals,sourceTransactionId) VALUES ('rent','Synthetic rent','100','SAR',31,'2026-01',0,'[\"2026-10\"]','[{\"pausedAt\":\"2026-10-01T12:00:00Z\",\"resumedAt\":null}]','original-source')")
            db.execSQL("INSERT INTO beta_audit_log (id,eventKind,environment,targetId,correction,interpretation,transition,operation,payload,createdAt) VALUES ('cfg','FINANCIAL_CONFIGURATION','BETA','periods',0,0,1,'',?, '2026-10-01T12:00:00Z')",arrayOf("{\"version\":1,\"salary\":{\"day\":27,\"zone\":\"Asia/Riyadh\"},\"statements\":[]}"))
        }
    }
    @Test fun immediatePrecedingLabSchemaGainsFactsWithoutRewritingExistingRows() {
        BetaContainer.resetOffline(context)
        try {
            seed()
            helper.runMigrationsAndValidate(BetaDatabase.NAME,15,true,BetaSchemaMigrations.COMMITMENT_FACTS).use {db ->
                db.query("SELECT active,sourceTransactionId,paidMonths,pauseIntervals,recurrence,transactionDate,dueDate FROM beta_commitment").use {c ->
                    assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0));assertEquals("original-source",c.getString(1));assertEquals("[\"2026-10\"]",c.getString(2))
                    assertTrue(c.getString(3).contains("2026-10-01"));assertEquals("MONTHLY",c.getString(4));assertTrue(c.isNull(5));assertTrue(c.isNull(6))
                }
            }
        } finally {BetaContainer.resetOffline(context)}
    }
    @Test fun restoringPreviousLabSnapshotUpgradesOnlyTheStagedCopy()=runBlocking {
        BetaContainer.resetOffline(context)
        try {
            seed()
            val recovery=com.baraa.masroof.lab.beta.BetaBackupRecovery(context)
            recovery.publish(context.getDatabasePath(BetaDatabase.NAME),emptyMap())
            val published=recovery.backupFile();val original=published.readBytes()
            BetaContainer(context).use {beta ->
                beta.putMarker("after-snapshot","must-disappear")
                assertEquals(1,beta.dashboardControls().read().draft.commitments.size)
            }
            assertTrue(recovery.restore())
            assertArrayEquals(original,published.readBytes())
            android.database.sqlite.SQLiteDatabase.openDatabase(published.absolutePath,null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {db -> assertEquals(14,db.version)}
            BetaContainer(context).use {beta ->
                val row=beta.dashboardControls().read().draft.commitments.single()
                assertEquals("original-source",row.sourceTransactionId);assertFalse(row.active)
                assertEquals(BetaCommitmentRecurrence.MONTHLY,row.recurrence)
            }
        } finally {BetaContainer.resetOffline(context)}
    }
    @Test fun realLabEntryRegistersTheSameUpgradeAndRetainsCommitmentMetadata()=runBlocking {
        BetaContainer.resetOffline(context)
        try {
            seed()
            BetaContainer(context).use {beta ->
                beta.putMarker("migration","synthetic")
                val row=beta.dashboardControls().read().draft.commitments.single()
                assertFalse(row.active);assertEquals("original-source",row.sourceTransactionId)
                assertEquals(BetaCommitmentRecurrence.MONTHLY,row.recurrence);assertEquals(1,row.pauseIntervals.size)
            }
        } finally {BetaContainer.resetOffline(context)}
    }
}
