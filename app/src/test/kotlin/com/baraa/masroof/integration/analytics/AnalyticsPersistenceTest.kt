package com.baraa.masroof.integration.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.testsupport.TestStorageContext
import com.baraa.masroof.application.analytics.*
import com.baraa.masroof.application.backup.*
import com.baraa.masroof.data.analytics.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class AnalyticsPersistenceTest {
    // TestStorageContext resolves its own database path; a wrapper path override would never be read.
    private fun context() = TestStorageContext(ApplicationProvider.getApplicationContext<Context>())
    @Test fun pruningKeepsOnlyTheNewestSnapshotsAndModelsOfEachKind() = runBlocking {
        val c = context(); c.deleteDatabase(AnalyticsDatabase.NAME)
        val db = AnalyticsDatabase.open(c)
        try {
            val dao = db.analytics()
            (1..30).forEach { dao.snapshot(AnalysisSnapshotRow("s$it", "a".repeat(64), it.toLong(), "{}")) }
            (1..8).forEach { dao.model(AnalysisModelRow("huber$it", "{}")); dao.model(AnalysisModelRow("baseline:$it", "{}")) }
            dao.pruneSnapshots(20); dao.pruneModels(5)
            assertEquals((11..30).map { "s$it" }, dao.snapshots().map { it.id })
            assertEquals("s30", dao.latest()?.id)
            assertEquals(((4..8).map { "huber$it" } + (4..8).map { "baseline:$it" }).toSet(), dao.models().map { it.id }.toSet())
        } finally { db.close() }
    }
    @Test fun sidecarReopensAndArchiveRoundTripsWithoutCopyingLedger() = runBlocking {
        val c = context(); c.deleteDatabase(AnalyticsDatabase.NAME)
        val db = AnalyticsDatabase.open(c)
        val snapshot = snapshot()
        val row = AnalysisSnapshotRow("published", snapshot.sourceHash, snapshot.issuedAt, AnalyticsJson.format.encodeToString(snapshot))
        val prefs = AnalysisPreferencesRow(budget = 45000, enabledAt = 1)
        val alert = AnalysisAlertRow("one", "DUE", "Utility", 900, 1, 2, read = true, snoozedUntil = 300)
        try {
            db.analytics().snapshot(row); db.analytics().preferences(prefs); db.analytics().alerts(listOf(alert))
            val raw = AnalyticsArchiveStore.export(db)
            AnalyticsBackup.validate(raw)
            AnalyticsArchiveStore.restore(db, null)
            assertNull(db.analytics().latest())
            AnalyticsArchiveStore.restore(db, raw)
            assertEquals(row, db.analytics().latest()); assertEquals(prefs, db.analytics().preferences())
            assertEquals(listOf(alert), db.analytics().alerts())
            db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%'").use { assertEquals(8, it.count) }
        } finally { db.close() }
        val reopened = AnalyticsDatabase.open(c)
        try { assertEquals(row, reopened.analytics().latest()) } finally { reopened.close() }
        assertTrue(c.getDatabasePath(AnalyticsDatabase.NAME).name == "analytics.db")
        assertFalse(ApplicationProvider.getApplicationContext<Context>().getDatabasePath("analytics.db").exists())
    }
    @Test fun oldPreferencesRemainReadableAndCorruptAnalyticsRejectedBeforeInstall() {
        val original = BackupPreferencesSnapshot(true, true, 1, true, "ar", "DARK")
        val raw = BackupPackageCodec.encodePreferences(original)
        assertNull(BackupPackageCodec.decodePreferences(raw.replace(",\"labAnalysis\":null", ""), BackupPackageFormat.FORMAT_VERSION).labAnalysis)
        val archive = AnalyticsJson.format.encodeToString(AnalyticsArchive(snapshots = listOf(AnalysisSnapshotRow("bad", "a".repeat(64), 1, "{}"))))
        val corrupt = BackupPackageCodec.encodePreferences(original.copy(labAnalysis = archive))
        assertTrue(runCatching { BackupPackageCodec.decodePreferences(corrupt, BackupPackageFormat.FORMAT_VERSION) }.isFailure)
    }
    @Test fun restoreJournalReplaysAnalysisAndRetainsOldBackupCompatibility() = runBlocking {
        val c = context(); c.deleteDatabase(AnalyticsDatabase.NAME)
        val db = AnalyticsDatabase.open(c)
        try { db.analytics().preferences(AnalysisPreferencesRow(budget = 78900, phone = true, enabledAt = 1)) } finally { db.close() }
        val captured = DatabaseRestoreRecovery.capturePreferences(c)
        assertNotNull(captured.labAnalysis)
        val incoming = DatabaseRestoreRecovery.incomingPreferences(BackupPreferencesSnapshot(true, true, null, true, "ar", "LIGHT"))
        assertNull(incoming.labAnalysis)
        AnalyticsBackup.restore(c, null)
        AnalyticsBackup.restore(c, captured.labAnalysis)
        val restored = AnalyticsDatabase.open(c)
        try { assertEquals(78900L, restored.analytics().preferences()!!.budget) } finally { restored.close() }
    }
    @Test fun predictedAlertRequiresTwoFreshObservationsSixHoursApartAndSurvivesReadSnooze() {
        val prefs = AnalysisPreferencesRow(budget = 10000)
        val first = AnalysisAlertPolicy.evaluate(snapshot(), emptyList(), emptyList(), prefs, 10).single()
        assertFalse(first.confirmed)
        val early = AnalysisAlertPolicy.evaluate(snapshot(1 + 5 * HOUR), emptyList(), listOf(first), prefs, 10).single()
        assertFalse(early.confirmed)
        val second = AnalysisAlertPolicy.evaluate(snapshot(1 + 6 * HOUR), emptyList(), listOf(early.copy(read = true, snoozedUntil = 9 * HOUR, lastNotified = 50)), prefs, 10).single()
        assertTrue(second.confirmed); assertTrue(second.read); assertEquals(9 * HOUR, second.snoozedUntil); assertEquals(50L, second.lastNotified)
        val stale = AnalysisAlertPolicy.evaluate(snapshot(1 + 30 * HOUR), emptyList(), listOf(first), prefs, 10).single()
        assertFalse(stale.confirmed)
        val blocked = AnalysisAlertPolicy.evaluate(snapshot().copy(quality = snapshot().quality.copy(blocked = true)), emptyList(), listOf(first), prefs, 10)
        assertTrue(blocked.single().resolved)
        val changed = AnalysisAlertPolicy.evaluate(snapshot(), emptyList(), listOf(first), prefs.copy(budget = 30000), 10)
        assertTrue(changed.single().resolved)
    }
    @Test fun interruptedRestoreRecoversMatchingFinancialFileAndAnalyticsOnCommitAndRollback() {
        val c = context()
        val live = DatabaseRestoreRecovery.liveDatabase(c)
        for (commit in listOf(true, false)) {
            c.deleteDatabase("masroof.db"); c.deleteDatabase(AnalyticsDatabase.NAME)
            live.parentFile!!.mkdirs()
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(live, null).use {
                it.execSQL("CREATE TABLE marker (value INTEGER)"); it.execSQL("INSERT INTO marker VALUES (1)")
            }
            val original = AnalyticsJson.format.encodeToString(AnalyticsArchive(preferences = AnalysisPreferencesRow(budget = 10000, enabledAt = 1)))
            val incoming = AnalyticsJson.format.encodeToString(AnalyticsArchive(preferences = AnalysisPreferencesRow(budget = 25000, enabledAt = 2)))
            AnalyticsBackup.restore(c, original)
            val old = DatabaseRestoreRecovery.capturePreferences(c)
            val new = DatabaseRestoreRecovery.incomingPreferences(BackupPreferencesSnapshot(true, true, null, true, "en", "LIGHT", incoming))
            DatabaseRestoreRecovery.writeSnapshots(live, old, new)
            DatabaseRestoreRecovery.parkOriginal(live)
            if (commit) android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(live, null).use {
                it.execSQL("CREATE TABLE marker (value INTEGER)"); it.execSQL("INSERT INTO marker VALUES (2)")
            }
            DatabaseRestoreRecovery.writeJournal(live, if (commit) DatabaseRestoreRecovery.Stage.NEW_INSTALLED else DatabaseRestoreRecovery.Stage.OLD_PARKED, false)
            // Simulate death after the independent sidecar transaction but before the durable commit marker.
            AnalyticsBackup.restore(c, incoming)
            DatabaseRestoreRecovery.recover(c)
            DatabaseRestoreRecovery.recover(c)
            val db = AnalyticsDatabase.open(c)
            try { assertEquals(if (commit) 25000L else 10000L, runBlocking { db.analytics().preferences()!!.budget }) } finally { db.close() }
            android.database.sqlite.SQLiteDatabase.openDatabase(live.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db2 ->
                db2.rawQuery("SELECT value FROM marker", null).use { it.moveToFirst(); assertEquals(if (commit) 2 else 1, it.getInt(0)) }
            }
        }
    }
    @Test fun onlyConfirmedDuesUseReminderWindowAndActualCrossingKeepsIdentity() {
        val s = snapshot().copy(actual = 15000, dues = listOf(AnalysisDue("one", "One", 500, 13, true), AnalysisDue("estimate", "Estimate", 500, 11, false), AnalysisDue("later", "Later", 500, 14, true)))
        val prefs = AnalysisPreferencesRow(budget = 10000)
        val rows = AnalysisAlertPolicy.evaluate(s, emptyList(), emptyList(), prefs, 10)
        assertEquals(setOf("ACTUAL", "DUE"), rows.map { it.kind }.toSet())
        assertTrue(rows.all { it.confirmed })
        assertEquals(rows.map { it.id }, AnalysisAlertPolicy.evaluate(s.copy(issuedAt = HOUR), emptyList(), rows, prefs, 10).map { it.id })
    }
    @Test fun dueAlertFindsItsCommitmentSourceSoScreensCanNameTheCard() {
        val card = AnalysisDue("credit:aljazira:7271", "7271", 500, 13, true,
            com.baraa.masroof.application.dashboard.CommitmentDashboardSource.CREDIT_CARD)
        val loan = AnalysisDue("loan:aljazira:AUTO", "Auto loan", 900, 12, true,
            com.baraa.masroof.application.dashboard.CommitmentDashboardSource.LOAN, com.baraa.masroof.domain.model.LoanType.AUTO)
        val s = snapshot().copy(dues = listOf(card, loan))
        val rows = AnalysisAlertPolicy.evaluate(s, emptyList(), emptyList(), AnalysisPreferencesRow(), 10)
        val state = AnalysisUiState(snapshot = s, alerts = rows)
        assertEquals(card, state.dueFor(rows.single { it.title == "7271" }))
        assertEquals(loan, state.dueFor(rows.single { it.title == "Auto loan" }))
        val restored = AnalyticsJson.format.decodeFromString<AnalysisDue>(AnalyticsJson.format.encodeToString(loan))
        assertEquals(loan, restored)
        // Snapshots stored before the source was carried still decode, with no source.
        assertNull(AnalyticsJson.format.decodeFromString<AnalysisDue>(
            "{\"id\":\"x\",\"name\":\"X\",\"amount\":1,\"day\":1,\"confirmed\":true}").source)
    }
    @Test fun phonePolicyEnforcesSevenSilentDaysSourceFreshnessAndNotificationDeduplication() {
        val week = 7 * 24 * HOUR
        val s = snapshot(week + 1)
        val prefs = AnalysisPreferencesRow(phone = true, enabledAt = 1)
        val row = AnalysisAlertRow("predicted", "BUDGET", "", 10000, 1, s.issuedAt, confirmed = true, sourceHash = s.sourceHash)
        assertTrue(AnalysisAlertPolicy.eligibleForPhone(row, s, prefs, s.issuedAt))
        assertFalse(AnalysisAlertPolicy.eligibleForPhone(row, s, prefs.copy(enabledAt = HOUR), s.issuedAt))
        assertFalse(AnalysisAlertPolicy.eligibleForPhone(row, s, prefs.copy(phone = false), s.issuedAt))
        assertFalse(AnalysisAlertPolicy.eligibleForPhone(row.copy(lastNotified = 1), s, prefs, s.issuedAt))
        assertFalse(AnalysisAlertPolicy.eligibleForPhone(row.copy(sourceHash = "old"), s, prefs, s.issuedAt))
        assertFalse(AnalysisAlertPolicy.eligibleForPhone(row, s, prefs, s.issuedAt + 7 * HOUR))
    }
    @Test fun refundsMayMakeNetActualsAndForecastsNegativeWithoutCreatingNegativeDomainMoney() {
        val s = snapshot().copy(actual = -5000, remaining = 100, total = -4900, lower = -5000, upper = -4800)
        val row = AnalysisSnapshotRow("refund", s.sourceHash, s.issuedAt, AnalyticsJson.format.encodeToString(s))
        AnalyticsBackup.validate(AnalyticsJson.format.encodeToString(AnalyticsArchive(snapshots = listOf(row))))
        assertEquals("-50.00", analysisAmount(s.actual).amount.toPlainString())
        assertEquals("-49.00", analysisAmount(s.total!!).amount.toPlainString())
    }
    @Test fun invalidAnalysisRowsAreLeftOutOfExportInsteadOfFailingTheBackup() {
        val good = AnalysisAlertRow("good", "DUE", "Utility", 900, 1, 2)
        val bad = AnalysisAlertRow("bad", "UNKNOWN_KIND", "Utility", 900, 1, 2)
        val coverage = AnalysisCoverageRow("long", 10, 5, "LEGACY", 1)
        val raw = AnalyticsJson.format.encodeToString(AnalyticsArchive(alerts = listOf(good, bad), coverage = listOf(coverage)))
        assertTrue(runCatching { AnalyticsBackup.validate(raw) }.isFailure)
        val exported = AnalyticsBackup.exportable(raw)
        AnalyticsBackup.validate(exported)
        val archive = AnalyticsJson.format.decodeFromString<AnalyticsArchive>(exported)
        assertEquals(listOf(good), archive.alerts)
        assertTrue(archive.coverage.isEmpty())
        val valid = AnalyticsJson.format.encodeToString(AnalyticsArchive(alerts = listOf(good)))
        assertEquals("A valid archive is exported unchanged", valid, AnalyticsBackup.exportable(valid))
    }
    @Test fun backupKeepsSnapshotsWithEveryRankedMerchant() {
        val base = snapshot()
        val components = listOf("PURCHASES", "CASH", "TRANSFERS", "REFUNDS").map { kind ->
            com.baraa.masroof.application.dashboard.AnalysisSpendingGroup(kind, if (kind == "PURCHASES") base.actual else 0, emptyList())
        }
        val merchants = (1..12).map { index ->
            com.baraa.masroof.application.dashboard.AnalysisSpendingGroup("merchant:shop-$index", 100L * (13 - index), listOf("t$index"), "Shop $index")
        }
        fun archive(groups: List<com.baraa.masroof.application.dashboard.AnalysisSpendingGroup>): String {
            val s = base.copy(insights = com.baraa.masroof.application.dashboard.AnalysisSpendingInsights(components, groups, null))
            return AnalyticsJson.format.encodeToString(AnalyticsArchive(snapshots = listOf(
                AnalysisSnapshotRow("merchants", s.sourceHash, s.issuedAt, AnalyticsJson.format.encodeToString(s)))))
        }
        val many = archive(merchants)
        AnalyticsBackup.validate(many)
        assertEquals(many, AnalyticsBackup.exportable(many))
        assertTrue(runCatching { AnalyticsBackup.validate(archive(merchants + merchants.first())) }.isFailure)
        assertTrue(runCatching { AnalyticsBackup.validate(archive(listOf(merchants.first().copy(amount = 0)))) }.isFailure)
        assertTrue(runCatching { AnalyticsBackup.validate(archive(listOf(merchants.first().copy(key = "PURCHASES")))) }.isFailure)
    }

    @Test fun exportChecksTheCombinedFinancialAndAnalyticsSizeBeforeOpeningTheDestination() {
        val c = context()
        val directory = java.io.File(c.getDatabasePath("unused").parentFile, "export").apply { mkdirs() }
        java.io.File(directory, "masroof.db").writeText("12345678")
        java.io.File(directory, "preferences.json").writeText("12345678")
        java.io.File(directory, "manifest.json").writeText("1")
        val limits = BackupArchiveLimits(3, 10, 15)
        assertTrue(runCatching { AnalyticsBackup.validateExportSize(directory, limits) }.isFailure)
        java.io.File(directory, "masroof.db").writeText("123")
        AnalyticsBackup.validateExportSize(directory, limits)
    }
    companion object {
        private const val HOUR = 3600_000L
        fun snapshot(at: Long = 1) = AnalysisSnapshot(0, 30, at, "a".repeat(64), "READY", "WEEKDAY_MEDIAN", 100,
            20000, 20100, 18000, 22000, emptyList(), AnalysisQuality(20, emptyMap(), emptyList(), 90, true, 0, false), emptyList(), emptyList(), 3)
    }
}
