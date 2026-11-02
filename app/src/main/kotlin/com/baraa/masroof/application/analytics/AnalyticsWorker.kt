package com.baraa.masroof.application.analytics

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.baraa.masroof.R
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.MainActivity
import com.baraa.masroof.data.analytics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class AnalyticsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val container = (applicationContext as MasroofApplication).container
        if (container.awaitStartupMaintenance() != com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome.READY) Result.retry()
        else { container.analytics.refresh(); Result.success() }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { Result.retry() }
    companion object {
        const val NAME = "masroof:analytics"
        fun scheduleDaily(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AnalyticsWorker>(6, TimeUnit.HOURS).setInitialDelay(6, TimeUnit.HOURS).addTag(NAME).build())
        }
    }
}


object AnalyticsNotifications {
    private const val CHANNEL = "masroof_analysis"
    fun cancel(context: Context, id: String) { NotificationManagerCompat.from(context).cancel("masroof-analysis", id.hashCode()) }
    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == "masroof-analysis" }.forEach { manager.cancel(it.tag, it.id) }
    }
    suspend fun publish(context: Context, database: AnalyticsDatabase, snapshot: AnalysisSnapshot, prefs: AnalysisPreferencesRow, now: Long) {
        if (!prefs.phone || snapshot.quality.blocked) { clear(context); return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.lab_analysis_channel), NotificationManager.IMPORTANCE_DEFAULT))
        for (row in database.analytics().alerts()) {
            if (row.resolved || row.read || row.snoozedUntil > now) manager.cancel("masroof-analysis", row.id.hashCode())
            if (!AnalysisAlertPolicy.eligibleForPhone(row, snapshot, prefs, now)) continue
            val intent = Intent(context, MainActivity::class.java).putExtra("lab_analysis_alert", row.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, row.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val title = context.getString(when (row.kind) {
                "DUE" -> R.string.lab_analysis_due_alert
                "PAYMENT" -> R.string.lab_analysis_payment_alert
                "ACTUAL" -> R.string.lab_analysis_actual_alert
                else -> R.string.lab_analysis_budget_alert
            })
            val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(row.title.ifBlank { context.getString(R.string.lab_analysis_title) })
                .setContentIntent(pending).setOnlyAlertOnce(true).setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            try { manager.notify("masroof-analysis", row.id.hashCode(), notification) } catch (_: SecurityException) { continue }
            database.analytics().alerts(listOf(row.copy(lastNotified = now)))
        }
    }
}

/** Included in the existing durable financial restore journal, including rollback. */
object AnalyticsBackup {
    fun validateExportSize(directory: java.io.File, limits: com.baraa.masroof.application.backup.BackupArchiveLimits) {
        val names = listOf("masroof.db", "manifest.json", "preferences.json")
        require(names.all { java.io.File(directory, it).isFile }) { "Incomplete backup package" }
        val lengths = names.map { java.io.File(directory, it).length() }
        require(names.size <= limits.maxEntries && lengths.all { it <= limits.maxUncompressedBytesPerEntry } &&
            lengths.fold(0L, Math::addExact) <= limits.maxTotalUncompressedBytes) { "Backup size limit exceeded" }
    }
    fun validate(raw: String?) {
        val archive = AnalyticsArchiveStore.validate(raw) ?: return
        val json = AnalyticsJson.format
        archive.snapshots.forEach { row ->
            val s = json.decodeFromString(AnalysisSnapshot.serializer(), row.payload)
            require(s.sourceHash == row.sourceHash && s.issuedAt == row.issuedAt && s.periodEnd > s.periodStart)
            java.time.LocalDate.ofEpochDay(s.periodStart); java.time.LocalDate.ofEpochDay(s.periodEnd)
            s.quality.historyFrom?.let(java.time.LocalDate::ofEpochDay)
            s.quality.historyThrough?.let(java.time.LocalDate::ofEpochDay)
            s.trainingFrom?.let(java.time.LocalDate::ofEpochDay)
            s.trainingThrough?.let(java.time.LocalDate::ofEpochDay)
            require((s.trainingFrom == null) == (s.trainingThrough == null))
            if (s.trainingFrom != null && s.trainingThrough != null) require(s.trainingFrom <= s.trainingThrough)
            require(s.periodEnd - s.periodStart in 1..35 && s.days.size <= 70)
            require(s.remaining == null || s.remaining >= 0)
            require(s.quality.used >= 0 && s.quality.coveredDays >= 0)
            require((s.wholeDaysLower == null) == (s.wholeDaysUpper == null))
            require(s.budgetLower == null || (s.total != null && s.budgetLower <= s.total))
            s.wholeDaysLower?.let { require(it >= 0 && s.wholeDaysUpper!! >= it) }
            s.wholeDaysFrom?.let { require(it in s.periodStart until s.periodEnd) }
            s.evaluations.forEach { require(it.horizon in 1..31 && it.count >= 8 && it.mae >= 0) }
            require(s.evaluations.map { it.horizon }.distinct().size == s.evaluations.size)
            s.insights?.let { insight ->
                require(insight.components.map { it.key }.toSet() == setOf("PURCHASES", "CASH", "TRANSFERS", "REFUNDS"))
                require(insight.components.size == 4 && insight.components.fold(0L) { sum, c -> Math.addExact(sum, c.amount) } == s.actual)
                require(insight.merchants.size <= 5)
                insight.comparison?.let { c -> require(c.through >= c.from && c.previousThrough - c.previousFrom == c.through - c.from &&
                    c.delta == Math.subtractExact(c.current, c.previous) && c.changes.size <= 3) }
            }
        }
        archive.models.forEach { row ->
            if (row.id.startsWith("baseline:")) {
                val b = json.decodeFromString(StoredBaseline.serializer(), row.payload)
                require(b.kind == "WEEKDAY_MEDIAN" && b.medians.size == 7 && b.medians.all { it >= 0 } && b.from <= b.through)
                require(b.errors.values.flatten().all { it.isFinite() && it >= 0 })
                return@forEach
            }
            val m = json.decodeFromString(StoredHuber.serializer(), row.payload)
            require(m.weights.size == 12 && m.means.size == 12 && m.scales.size == 12)
            require((m.weights + m.means + m.scales).all { it.isFinite() } && m.scales.all { it > 0 })
            require(m.baselineMae.isFinite() && m.modelMae.isFinite())
            require((m.signedErrors + m.wholeDayErrors).all { (h, values) -> h in 1..31 && values.all { it.isFinite() } })
        }
        archive.candidates.forEach { row ->
            val c = json.decodeFromString(AnalysisCandidate.serializer(), row.payload)
            require(c.key == row.id && c.sourceIds.size >= 3 && c.sourceIds.distinct().size == c.sourceIds.size)
            require(c.amount > 0 && c.lower >= 0 && c.upper >= c.lower && c.cadence in setOf("WEEKLY", "MONTHLY"))
            require(row.status in setOf("SUGGESTED", "ACCEPTED", "REJECTED"))
            if (c.seriesKey != null) {
                require(c.seriesKey.matches(Regex("[0-9a-f]{64}")))
            }
        }
        archive.feedback.forEach { row ->
            val payload = json.decodeFromString<RecurringFeedbackPayload>(row.payload).also { it.validate() }
            require(row.seriesKey == payload.seriesKey)
            require(when (row.kind) {
                "NOT_RECURRING" -> payload.label == "NONE"
                "DISMISSED" -> payload.label == null
                else -> payload.label == null || payload.label in setOf("WEEKLY", "MONTHLY")
            })
        }
        archive.alerts.forEach { require(it.kind in setOf("ACTUAL", "BUDGET", "DUE", "PAYMENT") && it.amount >= 0 && it.firstSeen >= 0 && it.observedAt >= it.firstSeen && it.readAt >= 0) }
    }
    fun capture(context: Context): String? {
        if (!context.getDatabasePath(AnalyticsDatabase.NAME).exists()) return null
        return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            val db = AnalyticsDatabase.open(context)
            try { exportable(AnalyticsArchiveStore.export(db)) } finally { db.close() }
        }
    }

    /**
     * Advisory analysis must never block a financial backup. Rows that fail the restore checks
     * (for example a pay period longer than the forecast window) are left out; the rest is kept
     * and the result still passes [validate].
     */
    internal fun exportable(raw: String): String {
        if (runCatching { validate(raw) }.isSuccess) return raw
        val json = AnalyticsJson.format
        val archive = json.decodeFromString(AnalyticsArchive.serializer(), raw)
        val empty = AnalyticsArchive(version = archive.version)
        fun valid(candidate: AnalyticsArchive) = runCatching { validate(json.encodeToString(AnalyticsArchive.serializer(), candidate)) }.isSuccess
        val pruned = empty.copy(
            snapshots = archive.snapshots.distinctBy { it.id }.filter { valid(empty.copy(snapshots = listOf(it))) },
            models = archive.models.distinctBy { it.id }.filter { valid(empty.copy(models = listOf(it))) },
            candidates = archive.candidates.distinctBy { it.id }.filter { valid(empty.copy(candidates = listOf(it))) },
            alerts = archive.alerts.distinctBy { it.id }.filter { valid(empty.copy(alerts = listOf(it))) },
            preferences = archive.preferences?.takeIf { valid(empty.copy(preferences = it)) },
            coverage = archive.coverage.distinctBy { it.id }.filter { valid(empty.copy(coverage = listOf(it))) },
            comparisons = archive.comparisons.distinctBy { it.id }.filter { valid(empty.copy(comparisons = listOf(it))) },
            feedback = archive.feedback.distinctBy { it.seriesKey }.filter { valid(empty.copy(feedback = listOf(it))) },
        )
        return json.encodeToString(AnalyticsArchive.serializer(), pruned).also(::validate)
    }
    fun restore(context: Context, raw: String?) {
        validate(raw)
        if (raw == null && !context.getDatabasePath(AnalyticsDatabase.NAME).exists()) return
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            val db = AnalyticsDatabase.open(context)
            try { AnalyticsArchiveStore.restore(db, raw) } finally { db.close() }
        }
    }
}
