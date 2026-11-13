package com.baraa.masroof.integration.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.application.backup.BackupMigrationContract
import com.baraa.masroof.testsupport.TestStorageContext
import org.junit.Test
import org.junit.runner.RunWith

/** Native SQLite run of the restore-migration contract on the committed version 1 archives (shared from test resources). */
@RunWith(AndroidJUnit4::class)
class BackupMigrationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = TestStorageContext(instrumentation.targetContext, "backup-migration")
    private fun fixture(name: String) = { instrumentation.context.assets.open("ledger_fixtures/v1/$name") }

    @Test
    fun encryptedEnvelope_importsUnderATestOnlyNextVersionWithEveryRowIntact() =
        BackupMigrationContract.archiveImportsUnderTheNextVersion(
            context, fixture("masroof-v1-backup.masroof"), fixture("masroof.db"), BackupMigrationContract.PASSPHRASE,
        )

    @Test
    fun failedMigration_leavesTheLiveDatabaseByteIdentical() =
        BackupMigrationContract.failedMigrationLeavesTheLiveDatabaseUntouched(context, fixture("masroof-v1-backup.zip"))
}
