package com.baraa.masroof.lab.beta

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import androidx.room.Room
import com.baraa.masroof.lab.beta.data.BetaDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.json.JSONTokener

/** Local Beta snapshots only. A digest detects corruption; it is not authentication. */
internal class BetaBackupRecovery(
    context: Context,
    private val fault: (Stage) -> Unit = {},
) {
    enum class Stage { BACKUP_STAGED, BACKUP_PUBLISHED, RESTORE_VALIDATED, JOURNALED, DATABASE_INSTALLED, PREFERENCES_INSTALLED, COMMITTED }
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, "beta_recovery_v2").also { check(it.isDirectory || it.mkdirs()) }
    private val pointer = AtomicFile(File(root, "backup_pointer"))
    private val journal = AtomicFile(File(root, "restore_journal"))
    private val live get() = context.getDatabasePath(BetaEnvironment.DATABASE_NAME)
    private val preferences get() = context.getSharedPreferences(BetaEnvironment.PREFS_NAME, Context.MODE_PRIVATE)

    fun backupFile(): File = activeBackup()?.let { File(it, DB) }
        ?: File(context.filesDir, BetaEnvironment.BACKUP_DATABASE_FILE_NAME)

    fun hasBackup(): Boolean = activeBackup() != null

    fun backup() {
        // Caller owns the Beta lifetime lock and has closed/checkpointed every live handle.
        recover()
        require(live.isFile)
        publish(live, preferenceValues())
    }

    /** Also used by migration fixtures to package an explicitly supplied old Beta schema. */
    fun publish(database: File, values: Map<String, String>) {
        BetaEnvironment.requireBetaDatabaseName(database.name)
        val folder = newFolder("snapshot-")
        try {
            seal(database, values, folder)
            fault(Stage.BACKUP_STAGED)
            atomicWrite(pointer, folder.name)
            fault(Stage.BACKUP_PUBLISHED)
            cleanupExcept(setOf(folder.name))
        } catch (failure: Exception) {
            // A published generation remains authoritative even if cleanup failed.
            if (activeBackup()?.name != folder.name) folder.deleteRecursively()
            throw failure
        }
    }

    private fun seal(database: File, values: Map<String, String>, folder: File) {
        copySynced(database, File(folder, DB))
        standaloneDatabase(File(folder, DB))
        writeSynced(File(folder, PREFS), encodePreferences(values).toString().toByteArray(Charsets.UTF_8))
        val version = inspectSqlite(File(folder, DB))
        writeSynced(File(folder, MANIFEST), JSONObject().apply {
            put("format", 1); put("environment", "BETA"); put("database", BetaEnvironment.DATABASE_NAME)
            put("schema", version); put("databaseSha256", sha(File(folder, DB))); put("preferencesSha256", sha(File(folder, PREFS)))
        }.toString().toByteArray(Charsets.UTF_8))
        verify(folder)
    }

    fun exportArchive(output: java.io.OutputStream, password: CharArray, archive: BetaEncryptedArchive = BetaEncryptedArchive()) {
        recover()
        val stage = newFolder("archive-")
        try {
            require(live.isFile)
            seal(live, preferenceValues(), stage)
            archive.write(stage, output, password)
        } finally { check(stage.deleteRecursively()) { "Could not clear Beta export stage" } }
    }

    fun importArchive(input: java.io.InputStream, password: CharArray, archive: BetaEncryptedArchive = BetaEncryptedArchive()): Boolean {
        recover()
        val stage = newFolder("archive-")
        try {
            archive.read(input, stage, password)
            verify(stage)
            return restoreFrom(stage)
        } finally { check(stage.deleteRecursively()) { "Could not clear Beta archive stage" } }
    }

    fun restore(): Boolean {
        recover()
        return restoreFrom(activeBackup() ?: return false)
    }

    private fun restoreFrom(backup: File): Boolean {
        verify(backup)
        val transaction = newFolder("restore-")
        var journaled = false
        try {
            copySynced(File(backup, DB), File(transaction, TARGET_DB))
            copySynced(File(backup, PREFS), File(transaction, TARGET_PREFS))
            validateRoom(File(transaction, TARGET_DB))
            fault(Stage.RESTORE_VALIDATED)
            // Keep the original byte-for-byte. No live file is touched before the journal.
            if (live.exists()) copySynced(live, File(transaction, ORIGINAL_DB))
            writeSynced(File(transaction, ORIGINAL_PREFS), encodePreferences(preferenceValues()).toString().toByteArray(Charsets.UTF_8))
            val originalHashes = JSONObject()
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                val original = File(live.path + suffix)
                if (original.isFile) {
                    val saved = File(transaction, ORIGINAL_DB + suffix)
                    if (suffix.isNotEmpty()) copySynced(original, saved)
                    originalHashes.put(suffix, sha(saved))
                }
            }
            atomicWrite(journal, JSONObject().put("folder", transaction.name).put("state", "REPLACING")
                .put("hadDatabase", live.exists()).put("originalHashes", originalHashes)
                .put("preferencesSha256", sha(File(transaction, ORIGINAL_PREFS))).toString())
            journaled = true
            fault(Stage.JOURNALED)
            installDatabase(File(transaction, TARGET_DB))
            fault(Stage.DATABASE_INSTALLED)
            val targetPreferences=readPreferences(File(transaction, TARGET_PREFS)).toMutableMap()
            // A restored snapshot is a new storage generation. Queued workers from either
            // side of the restore must not apply an old request to the restored ledger.
            if(targetPreferences.containsKey("beta_storage_generation"))
                targetPreferences["beta_storage_generation"]=java.util.UUID.randomUUID().toString()
            applyPreferences(targetPreferences)
            fault(Stage.PREFERENCES_INSTALLED)
            atomicWrite(journal, JSONObject().put("folder", transaction.name).put("state", "COMMITTED")
                .put("hadDatabase", File(transaction, ORIGINAL_DB).exists()).put("originalHashes", originalHashes)
                .put("preferencesSha256", sha(File(transaction, ORIGINAL_PREFS))).toString())
            fault(Stage.COMMITTED)
            deleteAtomic(journal)
            transaction.deleteRecursively()
            return true
        } catch (failure: Exception) {
            if (journaled) recover() else transaction.deleteRecursively()
            throw failure
        }
    }

    /** Must run before opening Room. A killed uncommitted restore always rolls back. */
    fun recover() {
        val bytes = readAtomic(journal) ?: return
        val state = strictObject(bytes.toString(Charsets.UTF_8))
        require(state.keys().asSequence().toSet() == setOf("folder", "state", "hadDatabase", "originalHashes", "preferencesSha256"))
        val folder = child(state.getString("folder"), "restore-")
        require(folder.isDirectory) { "Beta recovery original is missing" }
        when (state.getString("state")) {
            "REPLACING" -> {
                require(sha(File(folder, ORIGINAL_PREFS)) == state.getString("preferencesSha256"))
                val hashes = state.getJSONObject("originalHashes")
                require(hashes.keys().asSequence().toSet().all { it in setOf("", "-wal", "-shm", "-journal") })
                require(hashes.has("") == state.getBoolean("hadDatabase"))
                hashes.keys().asSequence().toSet().forEach { suffix ->
                    val saved = File(folder, ORIGINAL_DB + suffix)
                    // A rollback itself can be killed between atomic renames. Moved originals
                    // are validated at their destination and never replaced by an empty database.
                    val retained = if (saved.isFile) saved else File(live.path + suffix)
                    require(sha(retained) == hashes.getString(suffix))
                }
                val originalPreferences = readPreferences(File(folder, ORIGINAL_PREFS))
                if (state.getBoolean("hadDatabase")) {
                    val original = File(folder, ORIGINAL_DB)
                    if (original.isFile) {
                        removeSidecars()
                        move(original, live)
                    }
                    hashes.keys().asSequence().toSet().filter { it.isNotEmpty() }.forEach { suffix ->
                        val saved = File(folder, ORIGINAL_DB + suffix)
                        if (saved.isFile) move(saved, File(live.path + suffix))
                    }
                }
                else removeLiveDatabase()
                applyPreferences(originalPreferences)
            }
            "COMMITTED" -> Unit
            else -> error("Unsupported Beta recovery state")
        }
        deleteAtomic(journal)
        folder.deleteRecursively()
    }

    fun clear() {
        recover()
        deleteAtomic(pointer)
        cleanupExcept(emptySet())
        // Explicit Beta reset erases all retained Beta snapshots, including an
        // abandoned pre-journal stage. Never prune an active recovery original.
        root.listFiles()?.filter { it.isDirectory && (it.name.startsWith("restore-") || it.name.startsWith("archive-")) }
            ?.forEach { check(child(it.name, if (it.name.startsWith("restore-")) "restore-" else "archive-").deleteRecursively()) }
        val installation = File(live.parentFile, "masroof_beta_restore_install.tmp")
        check(!installation.exists() || installation.delete()) { "Could not clear Beta installation stage" }
    }

    private fun verify(folder: File) {
        require(folder.list()?.toSet() == setOf(DB, PREFS, MANIFEST)) { "Unexpected Beta archive entries" }
        val manifestFile = File(folder, MANIFEST)
        require(manifestFile.length() in 1..MAX_METADATA)
        val metadata = strictObject(manifestFile.readText(Charsets.UTF_8))
        require(metadata.keys().asSequence().toSet() == setOf("format", "environment", "database", "schema", "databaseSha256", "preferencesSha256"))
        require(metadata.getInt("format") == 1 && metadata.getString("environment") == "BETA")
        BetaEnvironment.requireBetaDatabaseName(metadata.getString("database"))
        require(com.baraa.masroof.lab.beta.data.BetaSchemaMigrations.readable(metadata.getInt("schema")))
        require(sha(File(folder, DB)) == metadata.getString("databaseSha256")) { "Beta database checksum mismatch" }
        require(sha(File(folder, PREFS)) == metadata.getString("preferencesSha256")) { "Beta preferences checksum mismatch" }
        require(inspectSqlite(File(folder, DB)) == metadata.getInt("schema"))
        readPreferences(File(folder, PREFS))
    }

    @android.annotation.SuppressLint("RestrictedApi")
    private fun validateRoom(file: File) {
        val staged = Room.databaseBuilder(context, BetaDatabase::class.java, file.absolutePath)
            .addMigrations(com.baraa.masroof.lab.beta.data.BetaSchemaMigrations.COMMITMENT_FACTS)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build()
        val reference = Room.databaseBuilder(context, BetaDatabase::class.java, File(file.parentFile, "reference.db").absolutePath)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            val actual = staged.openHelper.writableDatabase
            val expected = reference.openHelper.writableDatabase
            fun tables(db: androidx.sqlite.db.SupportSQLiteDatabase): Set<String> = db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            val names = tables(expected)
            require(tables(actual) == names) { "Beta archive tables differ from the current schema" }
            fun views(db: androidx.sqlite.db.SupportSQLiteDatabase): Set<String> = db.query("SELECT name FROM sqlite_master WHERE type='view'").use { cursor ->
                buildSet {while(cursor.moveToNext()) add(cursor.getString(0))} }
            require(views(actual)==views(expected)) {"Beta archive read projections differ"}
            views(expected).forEach { name -> require(androidx.room.util.ViewInfo.read(actual,name)==androidx.room.util.ViewInfo.read(expected,name)) {"Beta archive read projection differs"} }
            names.forEach { table ->
                require(androidx.room.util.TableInfo.read(actual, table) == androidx.room.util.TableInfo.read(expected, table)) {
                    "Beta archive schema differs from the generated Room schema"
                }
            }
        } finally { staged.close(); reference.close() }
        require(inspectSqlite(file) == BetaDatabase.VERSION)
    }

    private fun inspectSqlite(file: File): Int {
        require(file.isFile && file.length() in 100..MAX_DATABASE) { "Invalid Beta database size" }
        return SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "Broken Beta foreign keys" } }
            require(com.baraa.masroof.lab.beta.data.BetaSchemaMigrations.readable(db.version)) { "Unsupported Beta schema" }
            db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { require(it.moveToFirst() && it.getString(0).isNotBlank()) }
            db.version
        }
    }

    /** Seal only a NEW isolated copy. Published snapshots must never acquire WAL sidecars. */
    private fun standaloneDatabase(file: File) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { while (it.moveToNext()) Unit }
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use {
                require(it.moveToFirst() && it.getString(0).equals("delete", ignoreCase = true))
            }
        }
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val sidecar = File(file.path + suffix)
            check(!sidecar.exists() || sidecar.delete())
        }
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun preferenceValues(): Map<String, String> = preferences.all.mapValues { (_, value) ->
        require(value is String) { "Beta preferences store strings only" }; value
    }

    private fun encodePreferences(values: Map<String, String>) = JSONObject().apply {
        values.toSortedMap().forEach { (key, value) -> require(key.isNotBlank() && key.length <= 200); put(key, value) }
    }

    private fun readPreferences(file: File): Map<String, String> {
        require(file.isFile && file.length() in 2..MAX_METADATA)
        val json = strictObject(file.readText(Charsets.UTF_8))
        require(json.length() <= 1000)
        return json.keys().asSequence().toSet().associateWith { key ->
            require(key.isNotBlank() && key.length <= 200)
            val value = json.get(key); require(value is String); value
        }
    }

    private fun applyPreferences(values: Map<String, String>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) -> editor.putString(key, value) }
        check(editor.commit()) { "Could not commit Beta preferences" }
    }

    private fun strictObject(value: String): JSONObject {
        val parser = JSONTokener(value)
        val parsed = parser.nextValue()
        require(parsed is JSONObject && parser.nextClean() == '\u0000') { "Invalid Beta metadata" }
        return parsed
    }

    private fun installDatabase(source: File) {
        require(source.isFile)
        live.parentFile?.mkdirs()
        val temporary = File(live.parentFile, "masroof_beta_restore_install.tmp")
        copySynced(source, temporary)
        move(temporary, live)
        removeSidecars()
    }

    private fun move(source: File, destination: File) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun removeSidecars() {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val sidecar = File(live.path + suffix)
            check(!sidecar.exists() || sidecar.delete()) { "Could not remove closed Beta sidecar" }
        }
    }

    private fun removeLiveDatabase() {
        BetaEnvironment.requireBetaDatabaseName(live.name)
        check(!live.exists() || context.deleteDatabase(live.name))
    }

    private fun activeBackup(): File? = readAtomic(pointer)?.let {
        child(it.toString(Charsets.UTF_8), "snapshot-").also { folder -> require(folder.isDirectory) }
    }
    private fun child(name: String, prefix: String): File {
        require(name.matches(Regex("${prefix}[a-f0-9-]{36}")))
        return File(root, name).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    }
    private fun newFolder(prefix: String) = child(prefix + UUID.randomUUID(), prefix).also { check(it.mkdir()) }
    private fun cleanupExcept(keep: Set<String>) {
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("snapshot-") && it.name !in keep }
            ?.forEach { check(child(it.name, "snapshot-").deleteRecursively()) }
    }
    private fun copySynced(source: File, destination: File) {
        require(source.isFile && source.length() <= MAX_DATABASE)
        FileOutputStream(destination).use { output -> source.inputStream().use { it.copyTo(output) }; output.fd.sync() }
    }
    private fun writeSynced(file: File, bytes: ByteArray) = FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    private fun atomicWrite(file: AtomicFile, value: String) {
        val output = file.startWrite()
        try { output.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    }
    private fun deleteAtomic(file: AtomicFile) {
        file.delete()
        val base = file.baseFile
        check(listOf(base, File(base.path + ".bak"), File(base.path + ".new")).none { it.exists() }) {
            "Could not clear Beta recovery metadata"
        }
    }
    private fun readAtomic(file: AtomicFile): ByteArray? = try { file.openRead().use { require(it.channel.size() <= MAX_METADATA); it.readBytes() } }
        catch (failure: java.io.FileNotFoundException) {
            val base = file.baseFile
            if (!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) null
            else throw failure
        }
    private fun sha(file: File): String {
        require(file.isFile && file.length() <= MAX_DATABASE)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        private const val DB = "database.db"
        private const val PREFS = "preferences.json"
        private const val MANIFEST = "manifest.json"
        private const val TARGET_DB = "target.db"
        private const val TARGET_PREFS = "target_preferences.json"
        private const val ORIGINAL_DB = "original.db"
        private const val ORIGINAL_PREFS = "original_preferences.json"
        private const val MAX_DATABASE = 512L * 1024 * 1024
        private const val MAX_METADATA = 2L * 1024 * 1024
    }
}
