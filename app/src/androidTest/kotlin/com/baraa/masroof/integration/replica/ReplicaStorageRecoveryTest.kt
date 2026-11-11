package com.baraa.masroof.integration.replica

import com.baraa.masroof.testsupport.*
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.testsupport.FinancialHistoryFixture
import com.baraa.masroof.application.backup.BackupImportOutcome
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReplicaStorageRecoveryTest {
    private val host=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun corruptFinancialFileIsPreservedUntilTestStorageIsExplicitlyReset()=runBlocking {
        TestRuntime.reset(host)
        val scoped=TestStorageContext(host)
        val file=scoped.getDatabasePath("masroof.db");file.parentFile!!.mkdirs()
        val bytes=("SQLite format 3\u0000"+"damaged-sqlite-test-file-do-not-delete".repeat(100)).toByteArray();file.writeBytes(bytes)
        val damaged=TestRuntime.container(host)
        assertEquals(com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome.BLOCKED,damaged.maintenance.awaitStartupMaintenance())
        assertArrayEquals(bytes,file.readBytes())
        TestRuntime.reset(host)
        assertFalse(file.exists())
        val c=TestRuntime.container(host);FinancialHistoryFixture.seed(c,1)
        assertEquals(1,c.storage.financialTransactionRepository.listAll().size)
        TestRuntime.reset(host)
    }
    @Test fun originalEncryptedBackupRejectsBadSecretsThenRestoresLedgerAndOwnership()=runBlocking {
        TestRuntime.reset(host)
        var c=TestRuntime.container(host)
        val copy=File(host.filesDir,"replica-financial-backup.mroof")
        try {
            FinancialHistoryFixture.seed(c,4)
            c.analytics.refresh()
            c.analytics.updatePreferences(c.analytics.state.value.preferences.copy(budget = 17100))
            c.analytics.refresh()
            val expected=FinancialHistoryFixture.fingerprint(c)
            assertTrue(c.backup.databaseBackupService.exportTo(Uri.fromFile(copy),"Synthetic backup 42".toCharArray()).isSuccess)
            c.sms.processRawSmsUseCase.ingest(FinancialHistoryFixture.message("after-backup",90))
            val changed=FinancialHistoryFixture.fingerprint(c)
            assertNotEquals(expected,changed)
            assertNotEquals(BackupImportOutcome.SuccessNeedsRestart,c.backup.databaseBackupService.importFrom(Uri.fromFile(copy),"Wrong password".toCharArray()))
            assertEquals(changed,FinancialHistoryFixture.fingerprint(c))
            assertEquals(BackupImportOutcome.SuccessNeedsRestart,c.backup.databaseBackupService.importFrom(Uri.fromFile(copy),"Synthetic backup 42".toCharArray()))
            c=TestRuntime.container(host);c.maintenance.awaitPostStartupBackgroundWorkForTesting()
            c.analytics.refresh()
            assertEquals(17100L,c.analytics.state.value.preferences.budget)
            assertEquals(expected,FinancialHistoryFixture.fingerprint(c))
            assertEquals(4,c.storage.financialTransactionRepository.listAll().size)
            c.sms.processRawSmsUseCase.ingest(FinancialHistoryFixture.message("after-restore",91))
            assertEquals(5,c.storage.financialTransactionRepository.listAll().size)
            File(host.getExternalFilesDir(null),"replica-backup.txt").writeText("Production encrypted export/import reused. Wrong password preserved current data. Restore recovered 4 transactions and ownership. Next import posted a fifth.\n")
        } finally {copy.delete();TestRuntime.reset(host)}
    }
}
