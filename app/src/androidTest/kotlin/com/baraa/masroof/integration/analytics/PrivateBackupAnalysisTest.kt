package com.baraa.masroof.integration.analytics

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.analytics.*
import com.baraa.masroof.application.backup.*
import com.baraa.masroof.testsupport.TestStorageContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Opt-in private probe. No private backup or password is checked into the project. */
@RunWith(AndroidJUnit4::class)
class PrivateBackupAnalysisTest {
    @Test fun approvedPrivateBackupRunsThroughProductionAnalysisWithoutChangingTheLedger() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateBackup") == "true")
        val host = instrumentation.targetContext
        val backup = File(host.filesDir, "private-backup.masroof")
        val passwordFile = File(host.filesDir, "private-backup-passphrase")
        assumeTrue(backup.isFile && passwordFile.isFile)
        val context = TestStorageContext(host, "private-learning-probe")
        val staging = File(context.cacheDir, "staging").apply { mkdirs() }
        var container: AppContainer? = null
        var workflow: AnalyticsWorkflow? = null
        try {
            val password = passwordFile.readText().trimEnd('\r', '\n').toCharArray()
            passwordFile.delete()
            val plaintext = ByteArrayOutputStream()
            try { backup.inputStream().use { BackupEnvelope.decrypt(it, plaintext, password, 128L * 1024 * 1024) } }
            finally { password.fill('\u0000') }
            BackupArchiveValidator().extractInto(ByteArrayInputStream(plaintext.toByteArray()), staging)
            val manifest = BackupPackageCodec.decodeManifest(File(staging, "manifest.json").readText())
            assertEquals(18, manifest.roomVersion)
            val preferences = BackupPackageCodec.decodePreferences(File(staging, "preferences.json").readText())
            context.getDatabasePath("masroof.db").parentFile!!.mkdirs()
            File(staging, "masroof.db").copyTo(context.getDatabasePath("masroof.db"), overwrite = true)
            AnalyticsBackup.restore(context, preferences.labAnalysis)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${host.packageName} android.permission.READ_SMS")).use { it.readBytes() }
            val c = AppContainer(context).also { container = it }
            c.onboardingPreferencesRepository.apply {
                setOnboardingStarted(preferences.onboardingStarted)
                setOnboardingCompleted(preferences.onboardingCompleted)
                setHistoricalImportStartEpochMillis(preferences.historicalImportStartEpochMillis)
                setHistoricalImportCompleted(preferences.historicalImportCompleted)
            }
            c.runStartupMaintenance()
            c.awaitPostStartupBackgroundWorkForTesting()
            c.stopAnalytics()
            suspend fun ledgerFingerprint(): ByteArray = c.database.withTransaction {
                val digest = MessageDigest.getInstance("SHA-256")
                c.database.openHelper.readableDatabase.query("SELECT * FROM financial_transaction ORDER BY id").use { cursor ->
                    while (cursor.moveToNext()) for (column in 0 until cursor.columnCount) {
                        digest.update((if (cursor.isNull(column)) "<null>" else cursor.getString(column)).toByteArray())
                        digest.update(0.toByte())
                    }
                }
                digest.digest()
            }
            val before = ledgerFingerprint()
            val clock = Clock.fixed(Instant.ofEpochMilli(manifest.exportedAtEpochMillis), ZoneId.of("Asia/Riyadh"))
            val analysis = AnalyticsWorkflow(c, context, clock).also { workflow = it }
            analysis.refresh()
            val snapshot = requireNotNull(analysis.state.value.snapshot)
            val replay = analysis.replayForecasts()
            assertTrue("Analysis must not change the financial ledger", MessageDigest.isEqual(before, ledgerFingerprint()))
            File(host.filesDir, "private-probe-summary.json").writeText(
                """{"forecastMethod":"${snapshot.method}","forecastReason":"${snapshot.modelReason}","coveredDays":${snapshot.quality.coveredDays}","recurringCandidates":${snapshot.candidates.size},"spendingObservations":${analysis.state.value.observations.size},"replayEligible":${replay.count { it.forecast.total != null }},"ledgerUnchanged":true}""")
        } finally {
            workflow?.close()
            container?.close()
            File(host.filesDir, "private-learning-probe").deleteRecursively()
            backup.delete()
            passwordFile.delete()
        }
    }
}
