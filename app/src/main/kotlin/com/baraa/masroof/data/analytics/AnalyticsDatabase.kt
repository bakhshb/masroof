package com.baraa.masroof.data.analytics

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.baraa.masroof.data.room.PreservingOpenHelperFactory

@Serializable @Entity(tableName = "analysis_snapshot", indices = [Index("issuedAt")])
data class AnalysisSnapshotRow(@PrimaryKey val id: String, val sourceHash: String, val issuedAt: Long, val payload: String)
@Serializable @Entity(tableName = "analysis_model")
data class AnalysisModelRow(@PrimaryKey val id: String, val payload: String)
@Serializable @Entity(tableName = "recurring_candidate")
data class RecurringCandidateRow(@PrimaryKey val id: String, val status: String, val payload: String)
@Serializable @Entity(tableName = "recurring_feedback")
data class RecurringFeedbackRow(@PrimaryKey val seriesKey: String, val kind: String, val payload: String, val decidedAt: Long)
@Serializable @Entity(tableName = "analysis_alert")
data class AnalysisAlertRow(@PrimaryKey val id: String, val kind: String, val title: String, val amount: Long,
    val firstSeen: Long, val observedAt: Long, val confirmed: Boolean = false, val read: Boolean = false,
    val snoozedUntil: Long = 0, val resolved: Boolean = false, val lastNotified: Long = 0,
    val sourceHash: String = "", val evidenceId: String? = null, val readAt: Long = 0)
@Serializable @Entity(tableName = "analysis_preferences")
data class AnalysisPreferencesRow(@PrimaryKey val id: Int = 1, val budget: Long? = null,
    val phone: Boolean = false, val budgetAlerts: Boolean = true, val paymentAlerts: Boolean = true,
    val dueAlerts: Boolean = true, val reminderDays: Int = 3, val weekly: Boolean = false,
    val cycleSummary: Boolean = true, val enabledAt: Long = 0, val gapDays: String = "")

@Serializable @Entity(tableName = "analysis_coverage")
data class AnalysisCoverageRow(@PrimaryKey val id: String, val fromDay: Long, val throughDay: Long,
    val reason: String, val observedAt: Long, val repairedAt: Long? = null)

@Serializable @Entity(tableName = "analysis_forecast_comparison", indices = [Index("periodStart")])
data class ForecastComparisonRow(@PrimaryKey val id: String, val periodStart: Long, val periodEnd: Long,
    val issuedAt: Long, val sourceHash: String, val modelVersion: String, val evaluationVersion: String,
    val personalTotal: Long, val baselineTotal: Long, val actual: Long? = null,
    val actualSourceHash: String? = null, val evaluatedAt: Long? = null, val originDay: Long? = null)

@Dao interface AnalyticsDao {
    @Query("SELECT * FROM recurring_feedback ORDER BY seriesKey") suspend fun feedback(): List<RecurringFeedbackRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun feedback(row: RecurringFeedbackRow)
    @Query("DELETE FROM recurring_feedback") suspend fun clearFeedback()
    @Query("DELETE FROM recurring_feedback WHERE seriesKey=:seriesKey") suspend fun deleteFeedback(seriesKey: String)
    @Query("SELECT * FROM analysis_snapshot ORDER BY rowid DESC LIMIT 1") suspend fun latest(): AnalysisSnapshotRow?
    @Query("SELECT * FROM analysis_snapshot ORDER BY rowid ASC") suspend fun snapshots(): List<AnalysisSnapshotRow>
    @Query("SELECT * FROM analysis_snapshot ORDER BY rowid DESC LIMIT 20") suspend fun recentSnapshots(): List<AnalysisSnapshotRow>
    @Query("SELECT * FROM analysis_model") suspend fun models(): List<AnalysisModelRow>
    @Query("SELECT * FROM recurring_candidate") suspend fun candidates(): List<RecurringCandidateRow>
    @Query("SELECT * FROM analysis_alert") suspend fun alerts(): List<AnalysisAlertRow>
    @Query("SELECT * FROM analysis_preferences WHERE id=1") suspend fun preferences(): AnalysisPreferencesRow?
    @Query("SELECT * FROM analysis_coverage ORDER BY fromDay,id") suspend fun coverage(): List<AnalysisCoverageRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun coverage(values: List<AnalysisCoverageRow>)
    @Query("SELECT * FROM analysis_forecast_comparison ORDER BY periodStart,issuedAt,id") suspend fun comparisons(): List<ForecastComparisonRow>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun comparison(value: ForecastComparisonRow): Long
    @Query("UPDATE analysis_forecast_comparison SET actual=:actual, actualSourceHash=:sourceHash, evaluatedAt=:at WHERE id=:id")
    suspend fun evaluateComparison(id: String, actual: Long?, sourceHash: String, at: Long?)
    @Query("DELETE FROM analysis_coverage") suspend fun clearCoverage()
    @Query("DELETE FROM analysis_forecast_comparison") suspend fun clearComparisons()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun snapshot(value: AnalysisSnapshotRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun model(value: AnalysisModelRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun candidates(values: List<RecurringCandidateRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun alerts(values: List<AnalysisAlertRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun preferences(value: AnalysisPreferencesRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun initializePreferences(value: AnalysisPreferencesRow)
    @Query("DELETE FROM analysis_snapshot") suspend fun clearSnapshots()
    @Query("DELETE FROM analysis_model") suspend fun clearModels()
    @Query("DELETE FROM recurring_candidate") suspend fun clearCandidates()
    @Query("DELETE FROM analysis_alert") suspend fun clearAlerts()
    @Query("DELETE FROM analysis_preferences") suspend fun clearPreferences()
    /** Every refresh adds a snapshot; only the newest [keep] are ever read (see [recentSnapshots]). */
    @Query("DELETE FROM analysis_snapshot WHERE rowid NOT IN (SELECT rowid FROM analysis_snapshot ORDER BY rowid DESC LIMIT :keep)")
    suspend fun pruneSnapshots(keep: Int)
    /** Every refresh adds a model; keeps the newest [keep] Huber models and the newest [keep] baselines. */
    @Query("DELETE FROM analysis_model WHERE rowid NOT IN (" +
        "SELECT rowid FROM analysis_model WHERE id NOT LIKE 'baseline:%' ORDER BY rowid DESC LIMIT :keep) " +
        "AND rowid NOT IN (SELECT rowid FROM analysis_model WHERE id LIKE 'baseline:%' ORDER BY rowid DESC LIMIT :keep)")
    suspend fun pruneModels(keep: Int)
}

@Database(entities = [AnalysisSnapshotRow::class, AnalysisModelRow::class, RecurringCandidateRow::class,
    AnalysisAlertRow::class, AnalysisPreferencesRow::class, AnalysisCoverageRow::class, ForecastComparisonRow::class,
    RecurringFeedbackRow::class], version = 4, exportSchema = true)
abstract class AnalyticsDatabase : RoomDatabase() {
    abstract fun analytics(): AnalyticsDao
    companion object {
        const val NAME = "analytics.db"
        val MIGRATION_1_2 = object : Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS analysis_coverage (id TEXT NOT NULL PRIMARY KEY, fromDay INTEGER NOT NULL, throughDay INTEGER NOT NULL, reason TEXT NOT NULL, observedAt INTEGER NOT NULL, repairedAt INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS analysis_forecast_comparison (id TEXT NOT NULL PRIMARY KEY, periodStart INTEGER NOT NULL, periodEnd INTEGER NOT NULL, issuedAt INTEGER NOT NULL, sourceHash TEXT NOT NULL, modelVersion TEXT NOT NULL, evaluationVersion TEXT NOT NULL, personalTotal INTEGER NOT NULL, baselineTotal INTEGER NOT NULL, actual INTEGER, actualSourceHash TEXT, evaluatedAt INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_analysis_forecast_comparison_periodStart ON analysis_forecast_comparison(periodStart)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2,3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analysis_forecast_comparison ADD COLUMN originDay INTEGER")
                // The old policy proves this date; never infer it from a device timezone.
                db.execSQL("UPDATE analysis_forecast_comparison SET originDay = periodStart + 7 WHERE evaluationVersion = 'published-day8-v1' AND periodEnd > periodStart + 7")
            }
        }
        val MIGRATION_3_4 = object : Migration(3,4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS recurring_feedback (seriesKey TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL, decidedAt INTEGER NOT NULL)")
            }
        }
        fun open(context: Context): AnalyticsDatabase = Room.databaseBuilder(context, AnalyticsDatabase::class.java, NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).openHelperFactory(PreservingOpenHelperFactory()).build()
    }
}

@Serializable data class AnalyticsArchive(val version: Int = 4, val snapshots: List<AnalysisSnapshotRow> = emptyList(),
    val models: List<AnalysisModelRow> = emptyList(), val candidates: List<RecurringCandidateRow> = emptyList(),
    val alerts: List<AnalysisAlertRow> = emptyList(), val preferences: AnalysisPreferencesRow? = null,
    val coverage: List<AnalysisCoverageRow> = emptyList(), val comparisons: List<ForecastComparisonRow> = emptyList(),
    val feedback: List<RecurringFeedbackRow> = emptyList())

object AnalyticsJson {
    val format = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

/** Only the caller's isolated context is accepted; no financial database is opened here. */
object AnalyticsArchiveStore {
    fun validate(raw: String?): AnalyticsArchive? {
        if (raw == null) return null
        require(raw.length <= 128 * 1024 * 1024) { "Analysis archive too large" }
        val archive = AnalyticsJson.format.decodeFromString<AnalyticsArchive>(raw)
        require(archive.version in 1..4)
        require(archive.feedback.map { it.seriesKey }.distinct().size == archive.feedback.size)
        archive.feedback.forEach { require(it.seriesKey.isNotBlank() && it.decidedAt >= 0 && it.kind in setOf("CONFIRMED", "CONFIRM_PENDING", "NOT_RECURRING", "DISMISSED")) }
        require(archive.snapshots.map { it.id }.distinct().size == archive.snapshots.size)
        require(archive.models.map { it.id }.distinct().size == archive.models.size)
        require(archive.candidates.map { it.id }.distinct().size == archive.candidates.size)
        require(archive.alerts.map { it.id }.distinct().size == archive.alerts.size)
        archive.preferences?.let { require(it.id == 1 && (it.budget == null || it.budget > 0) && it.reminderDays in 1..30) }
        archive.snapshots.forEach { require(it.sourceHash.length == 64 && it.issuedAt >= 0) }
        require(archive.coverage.map { it.id }.distinct().size == archive.coverage.size)
        require(archive.comparisons.map { it.id }.distinct().size == archive.comparisons.size)
        archive.coverage.forEach {
            require(it.fromDay <= it.throughDay && it.throughDay - it.fromDay <= 36600 && it.observedAt >= 0)
            require(it.reason in setOf("LEGACY", "PERMISSION", "PROCESSING", "SCAN_RECEIPT", "CHECKPOINT"))
            require(it.repairedAt == null || it.repairedAt >= it.observedAt)
            java.time.LocalDate.ofEpochDay(it.fromDay); java.time.LocalDate.ofEpochDay(it.throughDay)
        }
        archive.comparisons.forEach {
            require(it.periodEnd - it.periodStart in 1..35 && it.issuedAt >= 0 && it.sourceHash.matches(Regex("[0-9a-f]{64}")))
            require(it.modelVersion.isNotBlank() && it.evaluationVersion.isNotBlank())
            require(it.originDay == null || it.originDay in (it.periodStart + 7) until it.periodEnd)
            if (it.evaluationVersion == "published-first-valid-v2") require(it.originDay != null)
            if (it.evaluationVersion == "published-day8-v1") require(it.originDay == null || it.originDay == it.periodStart + 7)
            require((it.actual == null) == (it.evaluatedAt == null))
            require(it.actualSourceHash == null || it.actualSourceHash.matches(Regex("[0-9a-f]{64}")))
        }
        return archive.copy(comparisons = archive.comparisons.map { row ->
            if (row.originDay == null && row.evaluationVersion == "published-day8-v1" && row.periodEnd > row.periodStart + 7)
                row.copy(originDay = row.periodStart + 7) else row
        })
    }
    suspend fun export(database: AnalyticsDatabase): String = database.withTransaction {
        val dao = database.analytics()
        AnalyticsJson.format.encodeToString(AnalyticsArchive(snapshots = dao.snapshots(), models = dao.models(),
            candidates = dao.candidates(), alerts = dao.alerts(), preferences = dao.preferences(),
            coverage = dao.coverage(), comparisons = dao.comparisons(), feedback = dao.feedback()))
    }
    suspend fun restore(database: AnalyticsDatabase, raw: String?) {
        val archive = validate(raw) ?: AnalyticsArchive()
        database.withTransaction {
            val dao = database.analytics()
            dao.clearSnapshots(); dao.clearModels(); dao.clearCandidates(); dao.clearAlerts(); dao.clearPreferences()
            dao.clearCoverage(); dao.clearComparisons(); dao.clearFeedback()
            archive.snapshots.forEach { dao.snapshot(it) }; archive.models.forEach { dao.model(it) }
            dao.candidates(archive.candidates); dao.alerts(archive.alerts)
            archive.preferences?.let { dao.preferences(it) }
            dao.coverage(archive.coverage)
            archive.comparisons.forEach { dao.comparison(it) }
            archive.feedback.forEach { dao.feedback(it) }
        }
    }
}
