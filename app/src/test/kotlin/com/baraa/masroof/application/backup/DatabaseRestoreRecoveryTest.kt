package com.baraa.masroof.application.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocalePreferences
import com.baraa.masroof.application.maintenance.MaintenancePreferences
import com.baraa.masroof.data.preferences.SharedPrefsOnboardingPreferencesRepository
import com.baraa.masroof.data.room.MasroofDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseRestoreRecoveryTest {
    private lateinit var context: Context
    private lateinit var live: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MasroofDatabase.NAME)
        live = context.getDatabasePath(MasroofDatabase.NAME)
        live.parentFile?.mkdirs()
        DatabaseRestoreRecovery.deleteRestoreArtifacts(live)
        context.getSharedPreferences(SharedPrefsOnboardingPreferencesRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(AppLocalePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        DatabaseRestoreRecovery.afterJournalTempDurable = null
        DatabaseRestoreRecovery.deleteRestoreArtifacts(live)
        if (live.exists()) live.delete()
        context.deleteDatabase(MasroofDatabase.NAME)
    }

    @Test
    fun prepared_keepsOriginalAndDropsTheUncommittedIncomingFile() {
        writeDatabase(live, "original")
        writeDatabase(DatabaseRestoreRecovery.incomingFile(live), "incoming")
        DatabaseRestoreRecovery.writeJournal(live, DatabaseRestoreRecovery.Stage.PREPARED, preservedRollback = false)

        DatabaseRestoreRecovery.recover(context)

        assertEquals("original", readMarker(live))
        assertFalse(DatabaseRestoreRecovery.incomingFile(live).exists())
        assertFalse(journal().exists())
        assertFalse(liveWasReplacedWithEmptyFile())
    }

    @Test
    fun prepared_doesNotDeleteAPreexistingRollback() {
        writeDatabase(live, "current")
        val preserved = File(live.path + ".rollback.preserved")
        writeDatabase(preserved, "previous")
        DatabaseRestoreRecovery.writeJournal(live, DatabaseRestoreRecovery.Stage.PREPARED, preservedRollback = true)

        DatabaseRestoreRecovery.recover(context)

        assertEquals("current", readMarker(live))
        assertEquals("previous", readMarker(File(live.path + ".rollback")))
        assertFalse(preserved.exists())
        assertFalse(journal().exists())
    }

    @Test
    fun oldParked_restoresOriginalWhenTheLiveFileIsMissing() {
        val rollback = File(live.path + ".rollback")
        writeDatabase(rollback, "original")
        writeDatabase(DatabaseRestoreRecovery.incomingFile(live), "incoming")
        DatabaseRestoreRecovery.writeJournal(live, DatabaseRestoreRecovery.Stage.OLD_PARKED, preservedRollback = false)

        DatabaseRestoreRecovery.recover(context)

        assertEquals("original", readMarker(live))
        assertFalse(rollback.exists())
        assertFalse(DatabaseRestoreRecovery.incomingFile(live).exists())
        assertFalse(journal().exists())
    }

    @Test
    fun oldParked_movesWalAndShmWithTheOriginalDatabase() {
        writeDatabase(live, "original")
        val db = SQLiteDatabase.openDatabase(live.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.rawQuery("PRAGMA journal_mode = WAL", null).use { cursor -> cursor.moveToFirst() }
        db.execSQL("INSERT INTO marker(value) VALUES (?)", arrayOf("from-wal"))
        val wal = File(live.path + "-wal")
        val shm = File(live.path + "-shm")
        assertTrue(wal.isFile && wal.length() > 0L)
        assertTrue(shm.isFile)
        val heldWal = File(live.parentFile, "held-wal")
        val heldShm = File(live.parentFile, "held-shm")
        val heldMain = File(live.parentFile, "held-main")
        live.copyTo(heldMain, overwrite = true)
        wal.copyTo(heldWal, overwrite = true)
        shm.copyTo(heldShm, overwrite = true)
        db.close()
        if (live.exists()) live.delete()
        File(live.path + "-wal").delete()
        File(live.path + "-shm").delete()
        heldMain.copyTo(live, overwrite = true)
        heldWal.copyTo(wal, overwrite = true)
        heldShm.copyTo(shm, overwrite = true)

        DatabaseRestoreRecovery.parkOriginal(live)
        val rollback = File(live.path + ".rollback")
        assertFalse(live.exists())
        assertFalse(wal.exists())
        assertFalse(shm.exists())
        assertTrue(File(rollback.path + "-wal").isFile)
        assertTrue(File(rollback.path + "-shm").isFile)
        DatabaseRestoreRecovery.writeJournal(live, DatabaseRestoreRecovery.Stage.OLD_PARKED, preservedRollback = false)

        DatabaseRestoreRecovery.recover(context)

        val markers = readMarkers(live)
        assertTrue(markers.contains("original"))
        assertTrue(markers.contains("from-wal"))
        assertFalse(rollback.exists())
        assertFalse(File(rollback.path + "-wal").exists())
        assertFalse(File(rollback.path + "-shm").exists())
        assertTrue(isValidSqlite(live))
        heldWal.delete()
        heldShm.delete()
        heldMain.delete()
    }

    @Test
    fun newInstalled_commitsIncomingPreferencesAndDropsTheOriginalCopy() {
        writeDatabase(live, "imported")
        writeDatabase(File(live.path + ".rollback"), "original")
        onboarding().edit().putBoolean("onboarding_completed", false).commit()
        maintenance().edit().putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 4).commit()
        DatabaseRestoreRecovery.writeSnapshots(
            live = live,
            original = DatabaseRestoreRecovery.capturePreferences(context),
            incoming = DatabaseRestoreRecovery.incomingPreferences(
                BackupPreferencesSnapshot(
                    onboardingStarted = true,
                    onboardingCompleted = true,
                    historicalImportStartEpochMillis = null,
                    historicalImportCompleted = true,
                    languageTag = AppLocale.TAG_EN,
                    themeMode = "SYSTEM",
                ),
            ),
        )
        onboarding().edit().putBoolean("onboarding_completed", false).commit()
        DatabaseRestoreRecovery.writeJournal(
            live,
            DatabaseRestoreRecovery.Stage.NEW_INSTALLED,
            preservedRollback = false,
        )

        DatabaseRestoreRecovery.recover(context)

        assertEquals("imported", readMarker(live))
        assertFalse(File(live.path + ".rollback").exists())
        assertTrue(onboarding().getBoolean("onboarding_completed", false))
        assertEquals(AppLocale.TAG_EN, locale().getString(AppLocalePreferences.KEY_LANGUAGE_TAG, null))
        assertFalse(maintenance().contains(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION))
        assertFalse(journal().exists())
    }

    @Test
    fun newInstalled_invalidLiveDatabase_restoresOriginalDatabaseAndPreferences() {
        live.writeBytes(byteArrayOf(1, 2, 3))
        writeDatabase(File(live.path + ".rollback"), "original")
        onboarding().edit().putBoolean("onboarding_completed", true).commit()
        val original = DatabaseRestoreRecovery.capturePreferences(context).copy(onboardingCompleted = false)
        DatabaseRestoreRecovery.writeSnapshots(
            live = live,
            original = original,
            incoming = original.copy(onboardingCompleted = true),
        )
        DatabaseRestoreRecovery.writeJournal(
            live,
            DatabaseRestoreRecovery.Stage.NEW_INSTALLED,
            preservedRollback = false,
        )

        DatabaseRestoreRecovery.recover(context)

        assertEquals("original", readMarker(live))
        assertFalse(onboarding().getBoolean("onboarding_completed", true))
        assertFalse(journal().exists())
    }

    @Test
    fun committed_removesRollbackWithoutReplacingTheLiveDatabase() {
        writeDatabase(live, "imported")
        writeDatabase(File(live.path + ".rollback"), "original")
        DatabaseRestoreRecovery.writeJournal(live, DatabaseRestoreRecovery.Stage.COMMITTED, preservedRollback = false)

        DatabaseRestoreRecovery.recover(context)

        assertEquals("imported", readMarker(live))
        assertFalse(File(live.path + ".rollback").exists())
        assertFalse(journal().exists())
    }

    @Test
    fun missingJournal_restoresRollbackWhenTheLiveFileIsGone() {
        writeDatabase(File(live.path + ".rollback"), "original")

        DatabaseRestoreRecovery.recover(context)

        assertEquals("original", readMarker(live))
        assertFalse(File(live.path + ".rollback").exists())
    }

    @Test
    fun missingJournal_keepsAValidLiveDatabaseAndItsPreexistingRollback() {
        writeDatabase(live, "current")
        writeDatabase(File(live.path + ".rollback"), "previous")

        DatabaseRestoreRecovery.recover(context)

        assertEquals("current", readMarker(live))
        assertEquals("previous", readMarker(File(live.path + ".rollback")))
    }

    @Test
    fun freshInstall_doesNotCreateADatabaseFile() {
        DatabaseRestoreRecovery.recover(context)

        assertFalse(live.exists())
    }

    @Test
    fun unrecoverableJournal_doesNotReplaceEitherCopy() {
        live.writeText("bad-live")
        val rollback = File(live.path + ".rollback")
        rollback.writeText("bad-rollback")
        DatabaseRestoreRecovery.writeJournal(live, DatabaseRestoreRecovery.Stage.OLD_PARKED, preservedRollback = false)

        try {
            DatabaseRestoreRecovery.recover(context)
            org.junit.Assert.fail("expected incomplete restore")
        } catch (error: DatabaseRestoreRecovery.IncompleteRestoreException) {
            assertTrue(error.message.orEmpty().contains("Parked original"))
        }

        assertEquals("bad-live", live.readText())
        assertEquals("bad-rollback", rollback.readText())
        assertTrue(journal().exists())
    }

    @Test
    fun advancingOldParkedToNewInstalled_interruptionKeepsOriginalDatabaseAndPreferences() {
        val rollback = File(live.path + ".rollback")
        writeDatabase(rollback, "original")
        writeDatabase(live, "imported")
        val original = preferenceSnapshot(onboardingCompleted = false, reparsedSchemaVersion = 7)
        val incoming = preferenceSnapshot(onboardingCompleted = true, reparsedSchemaVersion = null)
        DatabaseRestoreRecovery.writeSnapshots(live, original, incoming)
        applyMixedPreferences(onboardingCompleted = true, reparsedSchemaVersion = 7)
        DatabaseRestoreRecovery.writeJournal(live, DatabaseRestoreRecovery.Stage.OLD_PARKED, preservedRollback = false)
        DatabaseRestoreRecovery.afterJournalTempDurable = { stage ->
            if (stage == DatabaseRestoreRecovery.Stage.NEW_INSTALLED) {
                throw DatabaseRestoreRecovery.ProcessTerminated(stage)
            }
        }

        try {
            DatabaseRestoreRecovery.writeJournal(
                live,
                DatabaseRestoreRecovery.Stage.NEW_INSTALLED,
                preservedRollback = false,
            )
            org.junit.Assert.fail("expected interruption before the journal replace")
        } catch (error: DatabaseRestoreRecovery.ProcessTerminated) {
            assertEquals("NEW_INSTALLED", error.message?.substringAfterLast(' '))
        }

        assertTrue(journal().readText().contains("stage=OLD_PARKED"))
        assertTrue(journalTemp().readText().contains("stage=NEW_INSTALLED"))
        DatabaseRestoreRecovery.afterJournalTempDurable = null

        DatabaseRestoreRecovery.recover(context)

        assertEquals("original", readMarker(live))
        assertFalse(onboarding().getBoolean("onboarding_completed", true))
        assertEquals(7, maintenance().getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, -1))
        assertFalse(rollback.exists())
        assertFalse(journal().exists())
        assertFalse(journalTemp().exists())
    }

    @Test
    fun advancingNewInstalledToCommitted_interruptionKeepsImportedDatabaseAndPreferences() {
        val rollback = File(live.path + ".rollback")
        writeDatabase(live, "imported")
        writeDatabase(rollback, "original")
        val original = preferenceSnapshot(onboardingCompleted = false, reparsedSchemaVersion = 4)
        val incoming = preferenceSnapshot(onboardingCompleted = true, reparsedSchemaVersion = null)
        DatabaseRestoreRecovery.writeSnapshots(live, original, incoming)
        applyMixedPreferences(onboardingCompleted = false, reparsedSchemaVersion = 4)
        DatabaseRestoreRecovery.writeJournal(
            live,
            DatabaseRestoreRecovery.Stage.NEW_INSTALLED,
            preservedRollback = false,
        )
        DatabaseRestoreRecovery.afterJournalTempDurable = { stage ->
            if (stage == DatabaseRestoreRecovery.Stage.COMMITTED) {
                throw DatabaseRestoreRecovery.ProcessTerminated(stage)
            }
        }

        try {
            DatabaseRestoreRecovery.writeJournal(live, DatabaseRestoreRecovery.Stage.COMMITTED, preservedRollback = false)
            org.junit.Assert.fail("expected interruption before the journal replace")
        } catch (error: DatabaseRestoreRecovery.ProcessTerminated) {
            assertEquals("COMMITTED", error.message?.substringAfterLast(' '))
        }

        assertTrue(journal().readText().contains("stage=NEW_INSTALLED"))
        assertTrue(journalTemp().readText().contains("stage=COMMITTED"))
        DatabaseRestoreRecovery.afterJournalTempDurable = null

        DatabaseRestoreRecovery.recover(context)

        assertEquals("imported", readMarker(live))
        assertTrue(onboarding().getBoolean("onboarding_completed", false))
        assertFalse(maintenance().contains(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION))
        assertFalse(rollback.exists())
        assertFalse(journal().exists())
    }

    @Test
    fun corruptJournal_restoresOriginalDatabaseAndPreferencesTogether() {
        val rollback = File(live.path + ".rollback")
        writeDatabase(live, "imported")
        writeDatabase(rollback, "original")
        DatabaseRestoreRecovery.writeSnapshots(
            live,
            preferenceSnapshot(onboardingCompleted = false, reparsedSchemaVersion = 9),
            preferenceSnapshot(onboardingCompleted = true, reparsedSchemaVersion = null),
        )
        applyMixedPreferences(onboardingCompleted = true, reparsedSchemaVersion = 9)
        journal().writeText("stage=NEW_INST")

        DatabaseRestoreRecovery.recover(context)

        assertEquals("original", readMarker(live))
        assertFalse(onboarding().getBoolean("onboarding_completed", true))
        assertEquals(9, maintenance().getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, -1))
        assertFalse(rollback.exists())
        assertFalse(journal().exists())
    }

    @Test
    fun corruptJournal_whenNeitherDatabaseIsValid_failsClosed() {
        live.writeText("bad-live")
        val rollback = File(live.path + ".rollback")
        rollback.writeText("bad-rollback")
        journal().writeText("stage=COMMITTED\npreservedRollback=tru")

        try {
            DatabaseRestoreRecovery.recover(context)
            org.junit.Assert.fail("expected fail closed")
        } catch (error: DatabaseRestoreRecovery.IncompleteRestoreException) {
            assertTrue(error.message.orEmpty().contains("unreadable"))
        }

        assertEquals("bad-live", live.readText())
        assertEquals("bad-rollback", rollback.readText())
        assertFalse(live.readText().startsWith("SQLite format 3"))
    }

    @Test
    fun crashAfterPreservingRollback_beforePrepared_keepsLiveAndPreexistingRollback() {
        writeDatabase(live, "current")
        val rollback = File(live.path + ".rollback")
        writeDatabase(rollback, "previous")
        assertTrue(DatabaseRestoreRecovery.preserveExistingRollback(live))
        assertFalse(rollback.exists())
        assertTrue(File(live.path + ".rollback.preserved").exists())
        assertFalse(journal().exists())

        DatabaseRestoreRecovery.recover(context)

        assertEquals("current", readMarker(live))
        assertEquals("previous", readMarker(rollback))
        assertFalse(File(live.path + ".rollback.preserved").exists())
    }

    @Test
    fun corruptJournal_keepsOriginalLiveDatabaseWithOriginalPreferences() {
        writeDatabase(live, "original")
        DatabaseRestoreRecovery.writeSnapshots(
            live,
            preferenceSnapshot(onboardingCompleted = false, reparsedSchemaVersion = 3),
            preferenceSnapshot(onboardingCompleted = true, reparsedSchemaVersion = null),
        )
        applyMixedPreferences(onboardingCompleted = true, reparsedSchemaVersion = 1)
        journal().writeText("stage=PREPA")
        assertFalse(File(live.path + ".rollback").exists())

        DatabaseRestoreRecovery.recover(context)

        assertEquals("original", readMarker(live))
        assertFalse(onboarding().getBoolean("onboarding_completed", true))
        assertEquals(3, maintenance().getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, -1))
        assertFalse(journal().exists())
        assertFalse(File(live.path + ".rollback").exists())
    }

    @Test
    fun missingJournal_whenLiveFileIsNotSqlite_failsClosed() {
        live.writeText("bad-live")

        try {
            DatabaseRestoreRecovery.recover(context)
            org.junit.Assert.fail("expected fail closed")
        } catch (error: DatabaseRestoreRecovery.IncompleteRestoreException) {
            assertTrue(error.message.orEmpty().contains("neither copy"))
        }

        assertEquals("bad-live", live.readText())
        assertFalse(File(live.path + ".rollback").exists())
    }

    private fun journal(): File = File(live.path + ".restore-journal")

    private fun journalTemp(): File = File(journal().path + ".tmp")

    private fun preferenceSnapshot(
        onboardingCompleted: Boolean,
        reparsedSchemaVersion: Int?,
    ): DatabaseRestoreRecovery.PreferenceSnapshot {
        val captured = DatabaseRestoreRecovery.capturePreferences(context)
        return captured.copy(
            onboardingCompleted = onboardingCompleted,
            reparsedSchemaVersion = reparsedSchemaVersion,
        )
    }

    private fun applyMixedPreferences(onboardingCompleted: Boolean, reparsedSchemaVersion: Int) {
        onboarding().edit().putBoolean("onboarding_completed", onboardingCompleted).commit()
        maintenance().edit()
            .putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, reparsedSchemaVersion)
            .commit()
    }

    private fun onboarding() =
        context.getSharedPreferences(SharedPrefsOnboardingPreferencesRepository.PREFS_NAME, Context.MODE_PRIVATE)

    private fun maintenance() =
        context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private fun locale() =
        context.getSharedPreferences(AppLocalePreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private fun liveWasReplacedWithEmptyFile(): Boolean = live.exists() && live.length() == 0L

    private fun writeDatabase(file: File, marker: String) {
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE marker(value TEXT NOT NULL)")
            db.execSQL("INSERT INTO marker(value) VALUES (?)", arrayOf(marker))
        }
    }

    private fun readMarker(file: File): String = readMarkers(file).single()

    private fun readMarkers(file: File): List<String> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM marker ORDER BY rowid", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        }

    private fun isValidSqlite(file: File): Boolean =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == "ok"
            }
        }
}
