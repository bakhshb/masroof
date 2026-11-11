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

    @Test
    fun failedMigration_leavesTheLiveDatabaseByteIdentical() =
        BackupMigrationContract.failedMigrationLeavesTheLiveDatabaseUntouched(context, plaintext)

    @Test
    fun encryptedV18Envelope_verifiesUnderTheNextVersion() = runBlocking {
        val live = MasroofDatabase.builder(context, MasroofDatabase::class.java, "verify-live.db").build()
        try {
            val service = DatabaseBackupService(context, live, { error("verify must not close") }, "test",
                schema = BackupMigrationContract.nextVersionSchema(context))
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
            rewritten(sql = "UPDATE room_master_table SET identity_hash = '$unknown'"),
            // Above the target, and below the baseline, in both places.
            rewritten(manifestVersion = NextVersionTestDatabase.VERSION + 1, sql = "PRAGMA user_version = ${NextVersionTestDatabase.VERSION + 1}"),
            rewritten(manifestVersion = MasroofDatabase.BASELINE_VERSION - 1, sql = "PRAGMA user_version = ${MasroofDatabase.BASELINE_VERSION - 1}"),
            // A known next-version pair in the manifest over a v18 file.
            rewritten(manifestVersion = NextVersionTestDatabase.VERSION, manifestHash = next.identityHashes.getValue(NextVersionTestDatabase.VERSION)),
        )
        for (archive in cases) BackupMigrationContract.assertRejectedUntouched(context, { archive.inputStream() }, next)
        // Production knows no next version: the v18 archive relabelled as v19 is refused too.
        BackupMigrationContract.assertRejectedUntouched(context, { cases.last().inputStream() }, BackupSchema.PRODUCTION)
    }

    @Test
    fun exportStillWritesTheCurrentVersionAndHash() = runBlocking {
        val live = MasroofDatabase.builder(context, MasroofDatabase::class.java, "export-live.db").build()
        val archive = File(context.cacheDir, "export.masroof")
        try {
            val service = DatabaseBackupService(context, live, { error("export must not close") }, "test",
                kdfIterations = 2, schema = BackupMigrationContract.nextVersionSchema(context))
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

    /** The committed plaintext archive with its manifest pair and/or its database file changed. */
    private fun rewritten(
        manifestVersion: Int = MasroofDatabase.VERSION,
        manifestHash: String = MasroofDatabase.IDENTITY_HASH,
        sql: String? = null,
    ): ByteArray {
        val entries = entries(plaintext().use { it.readBytes() }).toMutableMap()
        val manifest = BackupPackageCodec.decodeManifest(entries.getValue(BackupPackageFormat.MANIFEST_ENTRY).decodeToString())
        entries[BackupPackageFormat.MANIFEST_ENTRY] =
            BackupPackageCodec.encodeManifest(manifest.copy(roomVersion = manifestVersion, identityHash = manifestHash)).toByteArray()
        if (sql != null) {
            val file = File(context.cacheDir, "rewritten.db").also { it.writeBytes(entries.getValue(BackupPackageFormat.DATABASE_ENTRY)) }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(sql) }
            entries[BackupPackageFormat.DATABASE_ENTRY] = file.readBytes()
            file.delete()
        }
        return ByteArrayOutputStream().also { writeZip(it, entries.toList()) }.toByteArray()
    }

    private fun entries(zip: ByteArray): Map<String, ByteArray> = ZipInputStream(zip.inputStream()).use { input ->
        generateSequence { input.nextEntry }.associate { it.name to input.readBytes() }
    }
}
