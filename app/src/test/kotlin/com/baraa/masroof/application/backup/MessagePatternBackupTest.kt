package com.baraa.masroof.application.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.patterns.MessagePatternsWorkflow
import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.engine.bank.patterns.*
import com.baraa.masroof.testsupport.TestStorageContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import com.baraa.masroof.testsupport.NoOpAppLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessagePatternBackupTest {
    @Test(timeout = 30_000) fun exportPairsItsFinancialSnapshotWithTheSamePatternGeneration() = runBlocking {
        val context = TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "pattern-export")
        val gate = DatabaseAccessGate()
        val database = Room.databaseBuilder(context, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .allowMainThreadQueries().build()
        val store = MessagePatternStore(database)
        val workflow = MessagePatternsWorkflow(store, gate, Dispatchers.Unconfined, appLogService = NoOpAppLog)
        val original = patternArchive("STC", withBindings = true)
        store.restoreRaw(original)
        val snapshotReady = CountDownLatch(1)
        val continueExport = CountDownLatch(1)
        val secret = "synthetic-pattern-backup".toCharArray()
        try {
            database.openHelper.writableDatabase
            val archive = context.cacheDir.resolve("patterns.masroof")
            val backup = DatabaseBackupService(context, database, { database.close() }, "test",
                clockEpochMillis = {
                    snapshotReady.countDown()
                    check(continueExport.await(10, TimeUnit.SECONDS))
                    1_700_000_000_000L
                }, databaseAccessGate = gate, kdfIterations = 2,
                appLogService = NoOpAppLog,)
            val exporting = async(Dispatchers.IO) { backup.exportTo(Uri.fromFile(archive), secret.copyOf()) }
            assertTrue(snapshotReady.await(10, TimeUnit.SECONDS))
            val bank = store.load().banks.single()
            val saving = async(start = CoroutineStart.UNDISPATCHED) {
                workflow.saveBank(1, bank.copy(patterns = listOf(bank.patterns.single().copy(revision = 2, enabled = false))))
            }
            assertFalse(saving.isCompleted)
            continueExport.countDown()
            assertTrue(exporting.await().isSuccess)
            assertEquals(2L, saving.await().generation)
            val plain = context.cacheDir.resolve("plain.zip")
            archive.inputStream().use { input -> plain.outputStream().use { BackupEnvelope.decrypt(input, it, secret.copyOf()) } }
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(plain.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) { entries[entry.name] = zip.readBytes(); entry = zip.nextEntry }
            }
            assertEquals(setOf("manifest.json", "preferences.json", "masroof.db"), entries.keys)
            // The patterns travel inside the exported database only, at the generation of its financial snapshot.
            val saved = BackupPackageCodec.decodePreferences(requireNotNull(entries["preferences.json"]).toString(Charsets.UTF_8))
            assertNull(saved.messagePatterns)
            val exported = context.cacheDir.resolve("exported.db").apply { writeBytes(requireNotNull(entries["masroof.db"])) }
            SQLiteDatabase.openDatabase(exported.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT generation, document FROM pattern_configuration", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertEquals(original, it.getString(1))
                    assertFalse(it.moveToNext())
                }
            }
            assertEquals(MasroofDatabase.VERSION, BackupPackageCodec.decodeManifest(requireNotNull(entries["manifest.json"]).toString(Charsets.UTF_8)).roomVersion)
        } finally {
            continueExport.countDown()
            database.close()
            secret.fill('\u0000')
            context.getDatabasePath(MasroofDatabase.NAME).parentFile?.deleteRecursively()
        }
        Unit
    }

    /** A restore journal still carries the v18 file; a rollback puts even an invalid one back untouched. */
    @Test fun rollbackRestoresEvenAnInvalidOriginalLegacyFileInsteadOfSilentlyDiscardingIt() {
        val context = TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "pattern-rollback")
        val live = context.getDatabasePath(MasroofDatabase.NAME)
        try {
            SQLiteDatabase.openOrCreateDatabase(live, null).use { db -> db.execSQL("CREATE TABLE original_marker(value TEXT)") }
            MessagePatternBackup.restore(context, "{broken-original", validate = false)
            DatabaseRestoreRecovery.writeSnapshots(live, DatabaseRestoreRecovery.capturePreferences(context),
                DatabaseRestoreRecovery.incomingPreferences(BackupPreferencesSnapshot(true, true, historicalImportCompleted = true,
                    languageTag = "ar", themeMode = "SYSTEM", messagePatterns = patternArchive("IMPORTED"))))
            DatabaseRestoreRecovery.parkOriginal(live)
            live.writeText("corrupt installed database")
            MessagePatternBackup.restore(context, patternArchive("IMPORTED"))
            DatabaseRestoreRecovery.writeJournal(live, DatabaseRestoreRecovery.Stage.NEW_INSTALLED, false)
            DatabaseRestoreRecovery.recover(context)
            assertEquals("{broken-original", MessagePatternBackup.captureOriginal(context))
            SQLiteDatabase.openDatabase(live.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT * FROM original_marker", null).use { assertEquals(0, it.count) }
            }
        } finally {
            DatabaseRestoreRecovery.deleteRestoreArtifacts(live)
            live.parentFile?.deleteRecursively()
        }
    }
}

internal fun patternArchive(bank: String, withBindings: Boolean = false): String {
    val body = "Synthetic event\nAmount:10SAR" + if (withBindings) "\nAcc:321" else ""
    val pattern = SavedMessagePattern.fromChoices("pattern", bank, "Sender $bank", "PURCHASE", body,
        listOf(FieldChoice("amount", body.indexOf("10SAR"), body.indexOf("10SAR") + 5)) +
            if (withBindings) listOf(FieldChoice("account_reference", body.indexOf("321"), body.length)) else emptyList()).let {
        if (!withBindings) it else it.copy(factContract = PatternFactContract(com.baraa.masroof.engine.bank.MessageFamily.PURCHASE,
            com.baraa.masroof.engine.bank.MoneyDirection.OUTGOING, version = 2,
            accountBindings = listOf(PatternAccountBinding(PatternAccountRole.SOURCE, com.baraa.masroof.finance.value.BankId(bank), "4321", "account_reference", "321"))))
    }
    return MessagePatternConfigurationCodec.encode(MessagePatternConfiguration(1,
        listOf(BankMessagePatterns(bank, listOf(pattern.sender), patterns = listOf(pattern)))))
}
