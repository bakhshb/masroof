package com.baraa.masroof.data.learning

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Room
import com.baraa.masroof.data.room.PreservingOpenHelperFactory
import java.io.File

/**
 * The derived learning cache (`learning.db`). It opens through [PreservingOpenHelperFactory], so the framework never
 * deletes a corrupt file on its own. Recovery is explicit: an operation that finds the file corrupt reports it to
 * [onCorruption] (the caller logs it), then [invalidate]s the cache and rethrows; the next operation opens an empty
 * file, which learning refills from the user's decisions. Parsing and posting never depend on it.
 */
class LearningStore(private val context: Context, private val onCorruption: (SQLiteDatabaseCorruptException) -> Unit) : AutoCloseable {
    private var instance: LearningDatabase? = null

    @Synchronized private fun database(): LearningDatabase = instance ?: Room.databaseBuilder(context, LearningDatabase::class.java, NAME)
        .addMigrations(LearningDatabase.MIGRATION_1_2).openHelperFactory(PreservingOpenHelperFactory()).build().also { instance = it }

    private suspend fun <T> use(block: suspend (LearningDao) -> T): T = try {
        block(database().learning())
    } catch (corrupt: SQLiteDatabaseCorruptException) {
        onCorruption(corrupt)
        invalidate()
        throw corrupt
    }

    suspend fun record(row: EngineInterpretationRow) = use { it.record(row) }
    suspend fun saveFeedback(labels: List<LearningLabelRow>, feedback: List<LearningFeedbackRow>) = use { it.replaceFeedback(labels, feedback) }
    suspend fun feedback() = use { it.feedback() }
    suspend fun labels() = use { it.labels() }
    suspend fun interpretations() = use { it.interpretations() }
    suspend fun history(rawId: String) = use { it.history(rawId) }
    @Synchronized override fun close() { instance?.close(); instance = null }
    /** Deletes the derived cache; the next operation starts an empty one. */
    @Synchronized fun invalidate() { close(); context.deleteDatabase(NAME); File(context.filesDir, "learning").deleteRecursively() }
    companion object { const val NAME = "learning.db" }
}
