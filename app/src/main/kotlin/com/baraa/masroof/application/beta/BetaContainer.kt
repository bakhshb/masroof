package com.baraa.masroof.application.beta

import android.content.Context
import androidx.room.Room
import com.baraa.masroof.data.room.MasroofDatabase
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.runBlocking

/**
 * Empty Beta composition root.
 *
 * Opens [BetaEnvironment.DATABASE_NAME] only. It does not read [com.baraa.masroof.application.AppContainer]
 * and it does not expose production repositories.
 */
class BetaContainer(
    context: Context,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = ReentrantLock()
    private var closed = false
    private var database = openDatabase()
    private val preferences = appContext.getSharedPreferences(
        BetaEnvironment.PREFS_NAME,
        Context.MODE_PRIVATE,
    ).also { BetaEnvironment.requireBetaPreferenceName(BetaEnvironment.PREFS_NAME) }

    fun evidenceCount(): Int = lock.withLock {
        check(!closed)
        runBlocking { database.rawSmsDao().count() }
    }

    internal fun putPreferenceProbe(value: String) = lock.withLock {
        check(!closed)
        preferences.edit().putString(PREFERENCE_PROBE_KEY, value).commit()
    }

    internal fun preferenceProbe(): String? = lock.withLock {
        check(!closed)
        preferences.getString(PREFERENCE_PROBE_KEY, null)
    }

    fun backup() = lock.withLock {
        check(!closed)
        database.close()
        try {
            copyLiveDatabaseToBackup()
            exportPreferences()
        } finally {
            database = openDatabase()
        }
    }

    fun restore(): Boolean = lock.withLock {
        check(!closed)
        database.close()
        try {
            restoreOffline(appContext)
        } finally {
            database = openDatabase()
        }
    }

    fun reset() = lock.withLock {
        check(!closed)
        database.close()
        try {
            resetOffline(appContext)
        } finally {
            database = openDatabase()
        }
    }

    fun backupDatabaseFile(): File = backupDatabase(appContext)

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            database.close()
        }
    }

    private fun openDatabase(): MasroofDatabase {
        val name = BetaEnvironment.DATABASE_NAME
        BetaEnvironment.requireBetaDatabaseName(name)
        val path = appContext.getDatabasePath(name)
        check(path.name == name) { "Resolved beta database path is ${path.name}" }
        return Room.databaseBuilder(appContext, MasroofDatabase::class.java, name)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .build()
    }

    private fun copyLiveDatabaseToBackup() {
        val live = appContext.getDatabasePath(BetaEnvironment.DATABASE_NAME)
        val backup = backupDatabase(appContext)
        BetaEnvironment.requireBetaDatabaseName(live.name)
        BetaEnvironment.requireBackupDatabaseName(backup.name)
        check(live.absolutePath != backup.absolutePath)
        backup.parentFile?.mkdirs()
        live.copyTo(backup, overwrite = true)
    }

    private fun exportPreferences() {
        val destination = backupPreferences(appContext)
        BetaEnvironment.requireBackupPreferenceName(destination.name)
        val lines = preferences.all.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, value) ->
                check(value is String) { "Beta preferences store strings only" }
                "$key=$value"
            }
        destination.writeText(lines)
    }

    companion object {
        internal const val PREFERENCE_PROBE_KEY: String = "beta_preference_probe"

        fun restoreOffline(context: Context): Boolean {
            val appContext = context.applicationContext
            val backup = backupDatabase(appContext)
            val prefsBackup = backupPreferences(appContext)
            if (!backup.isFile || !prefsBackup.isFile) return false
            BetaEnvironment.requireBackupDatabaseName(backup.name)
            BetaEnvironment.requireBetaDatabaseName(BetaEnvironment.DATABASE_NAME)
            appContext.deleteDatabase(BetaEnvironment.DATABASE_NAME)
            backup.copyTo(appContext.getDatabasePath(BetaEnvironment.DATABASE_NAME), overwrite = true)
            importPreferences(appContext, prefsBackup)
            return true
        }

        fun resetOffline(context: Context) {
            val appContext = context.applicationContext
            BetaEnvironment.requireBetaDatabaseName(BetaEnvironment.DATABASE_NAME)
            BetaEnvironment.requireBetaPreferenceName(BetaEnvironment.PREFS_NAME)
            appContext.deleteDatabase(BetaEnvironment.DATABASE_NAME)
            appContext.getSharedPreferences(BetaEnvironment.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            backupDatabase(appContext).delete()
            backupPreferences(appContext).delete()
        }

        private fun importPreferences(context: Context, file: File) {
            BetaEnvironment.requireBackupPreferenceName(file.name)
            val preferences = context.getSharedPreferences(BetaEnvironment.PREFS_NAME, Context.MODE_PRIVATE)
            val editor = preferences.edit().clear()
            file.readLines().forEach { line ->
                if (line.isEmpty()) return@forEach
                editor.putString(line.substringBefore('='), line.substringAfter('='))
            }
            editor.commit()
        }

        private fun backupDatabase(context: Context): File =
            File(context.filesDir, BetaEnvironment.BACKUP_DATABASE_FILE_NAME)

        private fun backupPreferences(context: Context): File =
            File(context.filesDir, BetaEnvironment.BACKUP_PREFS_FILE_NAME)
    }
}
