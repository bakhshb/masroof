package com.baraa.masroof.application.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.NextVersionTestDatabase
import com.baraa.masroof.testsupport.TestStorageContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import com.baraa.masroof.testsupport.NoOpAppLog

/** A restore accepts only known (version, identity hash) pairs and migrates the staged copy; device twin `BackupMigrationDeviceTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupOlderSchemaImportTest {
    private val context: Context = TestStorageContext(ApplicationProvider.getApplicationContext(), "backup-older-schema")
    private val fixtures = File(if (File("src/test").isDirectory) "." else "app", "src/test/resources/testdata/ledger_fixtures/v1")
    private val database = { File(fixtures, "masroof.db").inputStream() }
    private val plaintext = { File(fixtures, "masroof-v1-backup.zip").inputStream() }

    @Test
    fun plaintextArchive_importsUnderTheNextVersionWithEveryRowIntact() =
        BackupMigrationContract.archiveImportsUnderTheNextVersion(context, plaintext, database)

    @Test
    fun encryptedEnvelope_importsUnderTheNextVersionWithEveryRowIntact() =
        BackupMigrationContract.archiveImportsUnderTheNextVersion(
            context, { File(fixtures, "masroof-v1-backup.masroof").inputStream() }, database, BackupMigrationContract.PASSPHRASE,
        )

    @Test
    fun failedMigration_leavesTheLiveDatabaseByteIdentical() =
        BackupMigrationContract.failedMigrationLeavesTheLiveDatabaseUntouched(context, plaintext)

    @Test
    fun encryptedEnvelope_verifiesUnderTheNextVersion() = runBlocking {
        val live = MasroofDatabase.builder(context, MasroofDatabase::class.java, "verify-live.db").build()
        try {
            val service = DatabaseBackupService(context, live, { error("verify must not close") }, "test",
                schema = BackupMigrationContract.nextVersionSchema(context),
                appLogService = NoOpAppLog,)
            assertEquals(BackupVerificationOutcome.VERIFIED, service.verify(
                Uri.fromFile(File(fixtures, "masroof-v1-backup.masroof")), BackupMigrationContract.PASSPHRASE.toCharArray()))
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
            // Above the target, in both places.
            rewritten(manifestVersion = NextVersionTestDatabase.VERSION + 1, sql = listOf("PRAGMA user_version = ${NextVersionTestDatabase.VERSION + 1}")),
            // A known next-version pair in the manifest over a version 1 file.
            rewritten(manifestVersion = NextVersionTestDatabase.VERSION, manifestHash = next.identityHashes.getValue(NextVersionTestDatabase.VERSION)),
            // A format this version does not write.
            rewritten(format = BackupPackageFormat.FORMAT_VERSION + 1),
        )
        for (archive in cases) BackupMigrationContract.assertRejectedUntouched(context, { archive.inputStream() }, next)
        // Production knows no next version: the archive relabelled as the test-only next version is refused too.
        BackupMigrationContract.assertRejectedUntouched(context, { cases[3].inputStream() }, BackupSchema.PRODUCTION)
        // The schemas before the restart at version 1 are unknown, whatever hash they carried.
        for ((version, hash) in mapOf(18 to "71f1193767b3aa12bcb6cc6e7ce77147", 22 to MasroofDatabase.IDENTITY_HASH)) {
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

    /** The committed plaintext archive with its manifest changed and its database file edited by [sql]. */
    private fun rewritten(
        manifestVersion: Int = MasroofDatabase.VERSION,
        manifestHash: String = MasroofDatabase.IDENTITY_HASH,
        sql: List<String> = emptyList(),
        format: Int = BackupPackageFormat.FORMAT_VERSION,
    ): ByteArray {
        val entries = entries(plaintext().use { it.readBytes() }).toMutableMap()
        val manifest = BackupPackageCodec.decodeManifest(entries.getValue(BackupPackageFormat.MANIFEST_ENTRY).decodeToString())
        entries[BackupPackageFormat.MANIFEST_ENTRY] =
            BackupPackageCodec.encodeManifest(manifest.copy(formatVersion = format, roomVersion = manifestVersion, identityHash = manifestHash)).toByteArray()
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
