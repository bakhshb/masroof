package com.baraa.masroof.application.backup

import android.content.Context
import android.net.Uri
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.MigrationOpenPathContract
import com.baraa.masroof.data.room.NextVersionTestDatabase
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.baraa.masroof.testsupport.NoOpAppLog

/** Restore of the committed version 1 archives under the test-only next schema, run on the JVM and on a device. */
object BackupMigrationContract {
    const val PASSPHRASE = "synthetic-fixture-passphrase-v1"

    /** The restore target one version after production, with [step] as its migration. */
    fun nextVersionSchema(context: Context, step: Migration = NextVersionTestDatabase.MIGRATION_TO_NEXT) = BackupSchema(
        NextVersionTestDatabase::class.java,
        BackupSchema.PRODUCTION.identityHashes + (NextVersionTestDatabase.VERSION to NextVersionTestDatabase.identityHash(context)),
        listOf(step),
    )

    /** The archive imports; the live file becomes the next version and holds every row of [fixtureDb]. */
    fun archiveImportsUnderTheNextVersion(
        context: Context,
        archive: () -> InputStream,
        fixtureDb: () -> InputStream,
        passphrase: String = "",
    ) {
        reset(context)
        val live = openLive(context)
        val outcome = runBlocking {
            service(context, live, nextVersionSchema(context), close = { live.close() })
                .importFrom(stage(context, archive), passphrase.toCharArray(), confirmLegacyPlaintext = true)
        }
        assertEquals(BackupImportOutcome.SuccessNeedsRestart, outcome)
        val expected = MigrationOpenPathContract.copyFixture(context, "backup-migration-fixture.db", fixtureDb)
            .let { MigrationOpenPathContract.readOnly(it, MigrationOpenPathContract::rows) }
            .also { context.deleteDatabase("backup-migration-fixture.db") }
        val restored = MigrationOpenPathContract.readOnly(context.getDatabasePath(MasroofDatabase.NAME)) {
            assertEquals(NextVersionTestDatabase.VERSION, it.version)
            MigrationOpenPathContract.rows(it)
        }
        assertTrue("fixture has rows", expected.values.sumOf { it.size } > 0)
        MigrationOpenPathContract.assertKeepsEveryRow(expected, restored, MigrationOpenPathContract.NEXT_VERSION_TABLES)
        reset(context)
    }

    /** A throwing migration fails the import with the live files, preferences and journal exactly as before. */
    fun failedMigrationLeavesTheLiveDatabaseUntouched(context: Context, archive: () -> InputStream) {
        val failing = object : Migration(MasroofDatabase.VERSION, NextVersionTestDatabase.VERSION) {
            override fun migrate(db: SupportSQLiteDatabase) = error("synthetic migration failure")
        }
        assertRejectedUntouched(context, archive, nextVersionSchema(context, failing), BackupImportOutcome.Failed)
    }

    /** [archive] is refused with [expected]; the live database is never closed and its files keep their bytes. */
    fun assertRejectedUntouched(
        context: Context,
        archive: () -> InputStream,
        schema: BackupSchema,
        expected: BackupImportOutcome = BackupImportOutcome.InvalidPackage,
    ) {
        reset(context)
        val live = openLive(context)
        val closed = AtomicBoolean(false)
        try {
            live.openHelper.writableDatabase.execSQL("CREATE TABLE restore_probe (marker TEXT NOT NULL)")
            live.openHelper.writableDatabase.execSQL("INSERT INTO restore_probe VALUES ('live-marker')")
            val files = liveFiles(context)
            assertTrue("live WAL holds the probe", files.keys.contains(MasroofDatabase.NAME + "-wal"))
            val preferences = DatabaseRestoreRecovery.capturePreferences(context)
            val outcome = runBlocking {
                service(context, live, schema, close = { closed.set(true); live.close() })
                    .importFrom(stage(context, archive), confirmLegacyPlaintext = true)
            }
            assertEquals(expected, outcome)
            assertFalse(closed.get())
            assertEquals(files, liveFiles(context))
            assertEquals(preferences, DatabaseRestoreRecovery.capturePreferences(context))
            live.openHelper.readableDatabase.query("SELECT marker FROM restore_probe").use {
                assertTrue(it.moveToFirst()); assertEquals("live-marker", it.getString(0))
            }
        } finally {
            live.close()
            reset(context)
        }
    }

    /**
     * The live file, its WAL and every restore artifact (journal, snapshots, incoming), by name and bytes.
     * The `-shm` index is left out: SQLite rebuilds it and it carries no data.
     */
    private fun liveFiles(context: Context): Map<String, List<Byte>> {
        val live = context.getDatabasePath(MasroofDatabase.NAME)
        return live.parentFile!!.listFiles { file -> file.isFile && file.name.startsWith(live.name) && !file.name.endsWith("-shm") }!!
            .associate { it.name to it.readBytes().toList() }
    }

    private fun openLive(context: Context) =
        MasroofDatabase.builder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).allowMainThreadQueries().build()

    private fun service(context: Context, live: MasroofDatabase, schema: BackupSchema, close: () -> Unit) =
        DatabaseBackupService(context, live, close, "test", restartProcess = {}, schema = schema, appLogService = NoOpAppLog)

    private fun stage(context: Context, archive: () -> InputStream): Uri {
        val file = File(context.cacheDir, "backup-migration-archive")
        archive().use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }

    private fun reset(context: Context) {
        val live = context.getDatabasePath(MasroofDatabase.NAME)
        context.deleteDatabase(MasroofDatabase.NAME)
        DatabaseRestoreRecovery.deleteRestoreArtifacts(live)
        File(context.cacheDir, "backup-migration-archive").delete()
    }
}
