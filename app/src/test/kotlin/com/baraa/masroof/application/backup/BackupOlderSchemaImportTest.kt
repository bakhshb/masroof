package com.baraa.masroof.application.backup

import com.baraa.masroof.data.patterns.LegacyMessagePatternFile
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.MigrationOpenPathContract
import com.baraa.masroof.data.room.NextVersionTestDatabase
import com.baraa.masroof.data.room.UserDecisionBackfillContract
import com.baraa.masroof.testsupport.TestStorageContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.testsupport.NoOpAppLog
import com.baraa.masroof.testsupport.storedPatterns
import org.junit.Assert.assertNull
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.analytics.RecurringAnswerUpgradeContract
import com.baraa.masroof.data.analytics.AnalyticsArchive
import com.baraa.masroof.data.analytics.AnalyticsArchiveStore
import com.baraa.masroof.data.analytics.AnalyticsJson
import kotlinx.serialization.encodeToString

/** A restore accepts only known (version, identity hash) pairs and migrates the staged copy; device twin `BackupMigrationDeviceTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupOlderSchemaImportTest {
    private val context: Context = TestStorageContext(ApplicationProvider.getApplicationContext(), "backup-older-schema")
    private val fixtures = File(if (File("src/test").isDirectory) "." else "app", "src/test/resources/testdata/ledger_fixtures/v18")
    private val database = { File(fixtures, "masroof.db").inputStream() }
    private val plaintext = { File(fixtures, "masroof-v18-backup.zip").inputStream() }

    @Test
    fun v18PlaintextArchive_importsUnderTheNextVersionWithEveryRowIntact() =
        BackupMigrationContract.v18ArchiveImportsUnderTheNextVersion(context, plaintext, database)

    @Test
    fun v18EncryptedEnvelope_importsUnderTheNextVersionWithEveryRowIntact() =
        BackupMigrationContract.v18ArchiveImportsUnderTheNextVersion(
            context, { File(fixtures, "masroof-v18-backup.masroof").inputStream() }, database, BackupMigrationContract.PASSPHRASE,
        )

    /** A v18 backup migrates on its staged copy like a live v18 file, so every legacy decision trace is backfilled. */
    @Test
    fun v18ArchiveWithEveryLegacyTrace_importsUnderProductionWithItsDecisionsBackfilled() = runBlocking {
        val v18 = UserDecisionBackfillContract.legacyTracesFile(context, "backup-traces.db", database)
        assertFalse("the v18 file is self-contained", File(v18.path + "-wal").let { it.isFile && it.length() > 0 })
        val expected = MigrationOpenPathContract.readOnly(v18, MigrationOpenPathContract::rows)
        val archive = File(context.cacheDir, "traces.zip")
        archive.writeBytes(rewritten(18, MasroofDatabase.IDENTITY_HASHES.getValue(18), database = v18.readBytes(),
            patterns = patternArchive("V18")))
        context.deleteDatabase("backup-traces.db")
        val live = MasroofDatabase.builder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).build()
        try {
            val outcome = DatabaseBackupService(context, live, { live.close() }, "test", restartProcess = {}, appLogService = NoOpAppLog)
                .importFrom(Uri.fromFile(archive), confirmLegacyPlaintext = true)
            assertEquals(BackupImportOutcome.SuccessNeedsRestart, outcome)
            val restored = MigrationOpenPathContract.readOnly(context.getDatabasePath(MasroofDatabase.NAME)) {
                assertEquals(MasroofDatabase.VERSION, it.version)
                MigrationOpenPathContract.rows(it)
            }
            assertEquals(expected - "user_decision", restored - "user_decision" - "pattern_configuration")
            assertEquals(UserDecisionBackfillContract.EXPECTED.map(UserDecisionBackfillContract::dump), restored.getValue("user_decision"))
            assertEquals(patternArchive("V18"), storedPatterns(context.getDatabasePath(MasroofDatabase.NAME)))
        } finally {
            if (live.isOpen) live.close()
            archive.delete()
            context.deleteDatabase(MasroofDatabase.NAME)
        }
    }

    /** A v18 archive carries its patterns in preferences.json; import puts them in the restored database, byte for byte. */
    @Test
    fun olderArchivesCarryingPatterns_importThemIntoTheDatabase() = runBlocking {
        val archives = mapOf(
            "V18" to rewritten(18, MasroofDatabase.IDENTITY_HASHES.getValue(18), patterns = patternArchive("V18")),
            // An archive without patterns restores without a row, so bundled seeding runs on the next start.
            null to rewritten(18, MasroofDatabase.IDENTITY_HASHES.getValue(18)),
        )
        val expected = MigrationOpenPathContract.copyFixture(context, "older-fixture.db", database)
            .let { MigrationOpenPathContract.readOnly(it, MigrationOpenPathContract::rows) }
            .also { context.deleteDatabase("older-fixture.db") }
        for ((bank, bytes) in archives) {
            val archive = File(context.cacheDir, "older.zip").apply { writeBytes(bytes) }
            val live = MasroofDatabase.builder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).build()
            MessagePatternStore(live).restoreRaw(patternArchive("LIVE"))
            try {
                val outcome = DatabaseBackupService(context, live, { live.close() }, "test", restartProcess = {}, appLogService = NoOpAppLog)
                    .importFrom(Uri.fromFile(archive), confirmLegacyPlaintext = true)
                assertEquals(BackupImportOutcome.SuccessNeedsRestart, outcome)
                val livePath = context.getDatabasePath(MasroofDatabase.NAME)
                assertEquals(bank?.let(::patternArchive), storedPatterns(livePath))
                assertFalse(LegacyMessagePatternFile.of(context).exists())
                val restored = MigrationOpenPathContract.readOnly(livePath, MigrationOpenPathContract::rows)
                assertEquals(expected - "user_decision", restored - "user_decision" - "pattern_configuration")
            } finally {
                if (live.isOpen) live.close()
                archive.delete()
                context.deleteDatabase(MasroofDatabase.NAME)
            }
        }
    }

    /**
     * Plan 5.2: a v18 archive whose analysis still holds recurring answers restores them into `analytics.db`, and the
     * restored database's first open copies them into its `user_decision`; a later export carries them there only.
     */
    @Test
    fun v18ArchiveWithRecurringAnswers_putsThemInTheRestoredDecisionStore() = runBlocking<Unit> {
        val answers = RecurringAnswerUpgradeContract.ANSWERS
        val analysis = AnalyticsJson.format.encodeToString(AnalyticsArchive(feedback = answers))
        val archive = File(context.cacheDir, "recurring.zip").apply {
            writeBytes(rewritten(18, MasroofDatabase.IDENTITY_HASHES.getValue(18), labAnalysis = analysis))
        }
        val live = MasroofDatabase.builder(context, MasroofDatabase::class.java, MasroofDatabase.NAME).build()
        val exported = File(context.cacheDir, "recurring-export.masroof")
        try {
            val outcome = DatabaseBackupService(context, live, { live.close() }, "test", restartProcess = {}, appLogService = NoOpAppLog)
                .importFrom(Uri.fromFile(archive), confirmLegacyPlaintext = true)
            assertEquals(BackupImportOutcome.SuccessNeedsRestart, outcome)
            assertEquals(answers, RecurringAnswerUpgradeContract.readAnalytics(context))
            val c = AppContainer(context)
            try {
                assertEquals(answers, c.storage.recurringAnswerStore.effective())
                assertEquals(emptyList<Any>(), RecurringAnswerUpgradeContract.readAnalytics(context))
                assertTrue(DatabaseBackupService(context, c.storage.database, { error("export must not close") }, "test",
                    kdfIterations = 2, appLogService = NoOpAppLog).exportTo(Uri.fromFile(exported), "secret".toCharArray()).isSuccess)
            } finally { c.close() }
            val plain = ByteArrayOutputStream().also { out -> exported.inputStream().use { BackupEnvelope.decrypt(it, out, "secret".toCharArray()) } }
            val package_ = entries(plain.toByteArray())
            val preferences = BackupPackageCodec.decodePreferences(package_.getValue(BackupPackageFormat.PREFERENCES_ENTRY).decodeToString(), BackupPackageFormat.FORMAT_VERSION)
            assertEquals(emptyList<Any>(), AnalyticsArchiveStore.validate(preferences.labAnalysis)?.feedback.orEmpty())
            val inside = File(context.cacheDir, "exported.db").apply { writeBytes(package_.getValue(BackupPackageFormat.DATABASE_ENTRY)) }
            val recurring = MigrationOpenPathContract.readOnly(inside, MigrationOpenPathContract::rows).getValue("user_decision")
                .filter { it.split("|")[3] == "RECURRING" }
            assertEquals(RecurringAnswerUpgradeContract.DECISIONS.filter { it.split("|")[3] == "RECURRING" }, recurring)
            inside.delete()
        } finally {
            if (live.isOpen) live.close()
            archive.delete()
            exported.delete()
            context.getDatabasePath(MasroofDatabase.NAME).parentFile?.deleteRecursively()
        }
    }

    @Test
    fun failedMigration_leavesTheLiveDatabaseByteIdentical() =
        BackupMigrationContract.failedMigrationLeavesTheLiveDatabaseUntouched(context, plaintext)

    @Test
    fun encryptedV18Envelope_verifiesUnderTheNextVersion() = runBlocking {
        val live = MasroofDatabase.builder(context, MasroofDatabase::class.java, "verify-live.db").build()
        try {
            val service = DatabaseBackupService(context, live, { error("verify must not close") }, "test",
                schema = BackupMigrationContract.nextVersionSchema(context),
                appLogService = NoOpAppLog,)
            assertEquals(BackupVerificationOutcome.VERIFIED, service.verify(
                Uri.fromFile(File(fixtures, "masroof-v18-backup.masroof")), BackupMigrationContract.PASSPHRASE.toCharArray()))
        } finally {
            live.close()
            context.deleteDatabase("verify-live.db")
        }
    }

    @Test
    fun unknownPairsAreRejectedBeforeTheLiveDatabaseIsTouched() {
        val next = BackupMigrationContract.nextVersionSchema(context)
        val unknown = "0".repeat(32)
        val cases = listOf(
            // An unknown hash at a known version, in the manifest or only in the database file.
            rewritten(manifestHash = unknown),
            rewritten(sql = listOf("UPDATE room_master_table SET identity_hash = '$unknown'")),
            // Above the target, and below the baseline, in both places.
            rewritten(manifestVersion = NextVersionTestDatabase.VERSION + 1, sql = listOf("PRAGMA user_version = ${NextVersionTestDatabase.VERSION + 1}")),
            rewritten(manifestVersion = MasroofDatabase.BASELINE_VERSION - 1, sql = listOf("PRAGMA user_version = ${MasroofDatabase.BASELINE_VERSION - 1}")),
            // A known next-version pair in the manifest over a v18 file.
            rewritten(manifestVersion = NextVersionTestDatabase.VERSION, manifestHash = next.identityHashes.getValue(NextVersionTestDatabase.VERSION)),
        )
        for (archive in cases) BackupMigrationContract.assertRejectedUntouched(context, { archive.inputStream() }, next)
        // Production knows no next version: the v18 archive relabelled as the test-only next version is refused too.
        BackupMigrationContract.assertRejectedUntouched(context, { cases.last().inputStream() }, BackupSchema.PRODUCTION)
        // A format names what the archive holds: the legacy one is a v18 database, the current one a later database,
        // and only the legacy one may carry patterns beside it. Any other combination is refused, in production too.
        val v18 = MasroofDatabase.IDENTITY_HASHES.getValue(MasroofDatabase.BASELINE_VERSION)
        val mismatched = listOf(
            rewritten(MasroofDatabase.BASELINE_VERSION, v18, format = BackupPackageFormat.FORMAT_VERSION),
            rewritten(MasroofDatabase.BASELINE_VERSION, v18, format = BackupPackageFormat.FORMAT_VERSION + 1),
            rewritten(MasroofDatabase.BASELINE_VERSION, v18, format = BackupPackageFormat.FORMAT_VERSION, patterns = patternArchive("MISMATCH")),
        )
        for (archive in mismatched) BackupMigrationContract.assertRejectedUntouched(context, { archive.inputStream() }, BackupSchema.PRODUCTION)
        // v19 to v21 never shipped (owner, 2026-11-12): their pairs, in the manifest and the file, are unknown.
        for ((version, hash) in MigrationOpenPathContract.RETIRED_IDENTITY_HASHES) {
            val retired = rewritten(manifestVersion = version, manifestHash = hash,
                sql = listOf("PRAGMA user_version = $version", "UPDATE room_master_table SET identity_hash = '$hash'"))
            BackupMigrationContract.assertRejectedUntouched(context, { retired.inputStream() }, BackupSchema.PRODUCTION)
        }
    }

    @Test
    fun exportStillWritesTheCurrentVersionAndHash() = runBlocking {
        val live = MasroofDatabase.builder(context, MasroofDatabase::class.java, "export-live.db").build()
        val archive = File(context.cacheDir, "export.masroof")
        try {
            val service = DatabaseBackupService(context, live, { error("export must not close") }, "test",
                kdfIterations = 2, schema = BackupMigrationContract.nextVersionSchema(context),
                appLogService = NoOpAppLog,)
            assertTrue(service.exportTo(Uri.fromFile(archive), "secret".toCharArray()).isSuccess)
            val plain = ByteArrayOutputStream().also { out -> archive.inputStream().use { BackupEnvelope.decrypt(it, out, "secret".toCharArray()) } }
            val manifest = BackupPackageCodec.decodeManifest(entries(plain.toByteArray()).getValue(BackupPackageFormat.MANIFEST_ENTRY).decodeToString())
            assertEquals(MasroofDatabase.VERSION to MasroofDatabase.IDENTITY_HASH, manifest.roomVersion to manifest.identityHash)
        } finally {
            live.close()
            context.deleteDatabase("export-live.db")
            archive.delete()
        }
    }

    /** The committed plaintext archive with its manifest pair changed, and its database file replaced by [database] and/or edited by [sql]. */
    private fun rewritten(
        manifestVersion: Int = MasroofDatabase.VERSION,
        manifestHash: String = MasroofDatabase.IDENTITY_HASH,
        sql: List<String> = emptyList(),
        database: ByteArray? = null,
        patterns: String? = null,
        labAnalysis: String? = null,
        // The format that goes with the manifest's schema, unless a case names a mismatch.
        format: Int = if (manifestVersion == MasroofDatabase.BASELINE_VERSION) BackupPackageFormat.LEGACY_FORMAT_VERSION else BackupPackageFormat.FORMAT_VERSION,
    ): ByteArray {
        val entries = entries(plaintext().use { it.readBytes() }).toMutableMap()
        val preferences = BackupPackageCodec.decodePreferences(entries.getValue(BackupPackageFormat.PREFERENCES_ENTRY).decodeToString(), BackupPackageFormat.LEGACY_FORMAT_VERSION)
        entries[BackupPackageFormat.PREFERENCES_ENTRY] =
            BackupPackageCodec.encodePreferences(preferences.copy(messagePatterns = patterns, labAnalysis = labAnalysis ?: preferences.labAnalysis)).toByteArray()
        val manifest = BackupPackageCodec.decodeManifest(entries.getValue(BackupPackageFormat.MANIFEST_ENTRY).decodeToString())
        entries[BackupPackageFormat.MANIFEST_ENTRY] =
            BackupPackageCodec.encodeManifest(manifest.copy(formatVersion = format, roomVersion = manifestVersion, identityHash = manifestHash)).toByteArray()
        if (database != null) entries[BackupPackageFormat.DATABASE_ENTRY] = database
        if (sql.isNotEmpty()) {
            val file = File(context.cacheDir, "rewritten.db").also { it.writeBytes(entries.getValue(BackupPackageFormat.DATABASE_ENTRY)) }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db -> sql.forEach(db::execSQL) }
            entries[BackupPackageFormat.DATABASE_ENTRY] = file.readBytes()
            file.delete()
        }
        return ByteArrayOutputStream().also { writeZip(it, entries.toList()) }.toByteArray()
    }

    private fun entries(zip: ByteArray): Map<String, ByteArray> = ZipInputStream(zip.inputStream()).use { input ->
        generateSequence { input.nextEntry }.associate { it.name to input.readBytes() }
    }
}
