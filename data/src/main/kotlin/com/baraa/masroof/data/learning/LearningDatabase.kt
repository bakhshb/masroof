package com.baraa.masroof.data.learning

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Advisory provenance. Financial authority remains in the unchanged fourteen-table DB. */
@Entity(tableName="engine_interpretation",indices=[Index("rawId")])
data class EngineInterpretationRow(
    @PrimaryKey val id: String,val rawId: String,val bankId: String?,val engineVersion: String,val rulesVersion: String,
    val activeModelVersion: String?,val origin: String,val family: String,val status: String,val blockers: String,
    val bodyHash: String,val receivedAt: Long,val interpretedAt: Long,
    @ColumnInfo(defaultValue="0") val assistRequested: Boolean = false,
    @ColumnInfo(defaultValue="'LEGACY_UNKNOWN'") val assistOutcome: String = "LEGACY_UNKNOWN",
    val score: Double? = null, val margin: Double? = null, val coverage: Double? = null,
    val template: String? = null,
)
@Entity(tableName="learning_feedback", indices=[Index("rawId")])
data class LearningFeedbackRow(@PrimaryKey val decisionId: String, val rawId: String, val sourceAt: Long,
    val provenance: String, val state: String, val reason: String?, val bankId: String?, val template: String?)
@Entity(tableName="learning_label",indices=[Index("rawId"),Index("bankId","template")])
data class LearningLabelRow(
    @PrimaryKey val decisionId: String,val rawId: String,val bankId: String,val family: String,val template: String,
    val featuresJson: String,val featuresVersion: String,val sourceAt: Long,val provenance: String,val bodyHash: String,
)
@Dao
interface LearningDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun record(row: EngineInterpretationRow)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun labels(rows: List<LearningLabelRow>)
    @Query("DELETE FROM learning_label") suspend fun clearLabels()
    @Transaction suspend fun replaceLabels(rows: List<LearningLabelRow>) {clearLabels();if(rows.isNotEmpty()) labels(rows)}
    @Query("SELECT * FROM learning_label ORDER BY sourceAt,decisionId") suspend fun labels(): List<LearningLabelRow>
    @Query("SELECT * FROM engine_interpretation WHERE rawId=:rawId ORDER BY interpretedAt") suspend fun history(rawId: String): List<EngineInterpretationRow>
    @Query("SELECT * FROM engine_interpretation ORDER BY interpretedAt,id") suspend fun interpretations(): List<EngineInterpretationRow>
    @Query("SELECT * FROM learning_feedback ORDER BY sourceAt,decisionId") suspend fun feedback(): List<LearningFeedbackRow>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun feedback(rows: List<LearningFeedbackRow>)
    @Query("DELETE FROM learning_feedback") suspend fun clearFeedback()
    @Transaction suspend fun replaceFeedback(labels: List<LearningLabelRow>, feedback: List<LearningFeedbackRow>) {
        replaceLabels(labels); clearFeedback(); if(feedback.isNotEmpty()) feedback(feedback)
    }
}
@Database(entities=[EngineInterpretationRow::class,LearningLabelRow::class,LearningFeedbackRow::class],version=2,exportSchema=true)
abstract class LearningDatabase : RoomDatabase() {
    abstract fun learning(): LearningDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE engine_interpretation ADD COLUMN assistRequested INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE engine_interpretation ADD COLUMN assistOutcome TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'")
                db.execSQL("ALTER TABLE engine_interpretation ADD COLUMN score REAL")
                db.execSQL("ALTER TABLE engine_interpretation ADD COLUMN margin REAL")
                db.execSQL("ALTER TABLE engine_interpretation ADD COLUMN coverage REAL")
                db.execSQL("ALTER TABLE engine_interpretation ADD COLUMN template TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS learning_feedback (decisionId TEXT NOT NULL PRIMARY KEY, rawId TEXT NOT NULL, sourceAt INTEGER NOT NULL, provenance TEXT NOT NULL, state TEXT NOT NULL, reason TEXT, bankId TEXT, template TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_feedback_rawId ON learning_feedback(rawId)")
            }
        }
    }
}
