package com.baraa.masroof.data.learning

import android.content.Context
import androidx.room.Room
import java.io.File

class LearningStore(private val context: Context) : AutoCloseable {
    private val databaseLazy = lazy {Room.databaseBuilder(context,LearningDatabase::class.java,NAME)
        .addMigrations(LearningDatabase.MIGRATION_1_2).build()}
    private val database get()=databaseLazy.value
    suspend fun record(row: EngineInterpretationRow) = database.learning().record(row)
    suspend fun saveFeedback(labels: List<LearningLabelRow>, feedback: List<LearningFeedbackRow>) = database.learning().replaceFeedback(labels, feedback)
    suspend fun feedback() = database.learning().feedback()
    suspend fun labels() = database.learning().labels()
    suspend fun interpretations() = database.learning().interpretations()
    suspend fun history(rawId: String) = database.learning().history(rawId)
    override fun close() { if (databaseLazy.isInitialized()) database.close() }
    fun invalidate() { close();context.deleteDatabase(NAME);File(context.filesDir,"learning").deleteRecursively() }
    companion object { const val NAME="learning.db" }
}
