package com.baraa.masroof.instrumentation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.baraa.masroof.application.backup.BackupImportOutcome
import com.baraa.masroof.application.backup.DatabaseBackupService
import com.baraa.masroof.application.backup.DatabaseRestoreRecovery
import com.baraa.masroof.application.ingestion.BankSmsCaptureResult
import com.baraa.masroof.application.ingestion.CaptureBankSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.application.ingestion.ProcessingRecovery
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.maintenance.MaintenancePreferences
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.sms.LiveSmsIntake
import com.baraa.masroof.application.sms.LiveSmsProcessingWorker
import com.baraa.masroof.application.sms.LiveSmsWorkScheduler
import com.baraa.masroof.application.sms.WorkManagerLiveSmsWorkScheduler
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.application.transaction.TransactionIgnoreService
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.preferences.SharedPrefsAppLocaleRepository
import com.baraa.masroof.data.preferences.SharedPrefsOnboardingPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsThemePreferencesRepository
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomProcessingRetryRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.LoanRegistryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private const val OTP_CODE: String = "482913"
private const val PURCHASE_AT: String = "2026-08-03T14:32:00Z"
private const val REVIEW_AT: String = "2026-08-03T15:00:00Z"
private const val OTP_AT: String = "2026-08-03T16:00:00Z"
private const val ORIGINAL_AT: String = "2026-08-05T10:00:00Z"
private const val PACKAGE_AT: String = "2026-08-06T11:00:00Z"
private const val REVIEW_BODY: String =
    "تنبيه بنك الجزيرة: حدث تحديث في خدماتك. راجع التطبيق للتفاصيل."
private val PURCHASE_BODY: String = """
    شراء عبر الانترنت
    بطاقة: 7271
    لدى: Keeta
    بمبلغ: 51.99 SAR
    في: 14:32 03-08-2026
""".trimIndent()
private val OTP_BODY: String =
    "رمز التحقق لعملية شراء عبر الانترنت: $OTP_CODE\nبمبلغ: 250.00 SAR\nلدى: TEST_STORE\nلا تشارك الرمز مع أحد"
private val SQLITE_HEADER: ByteArray = byteArrayOf(
    0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
    0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00,
)

/**
 * Emulator instrumentation for M13. This is not a physical handset.
 *
 * The SMS database file is private to the test, matching [LiveRetryRestartTest],
 * so the application process cannot post the same message. Journeys use
 * [LiveSmsIntake], a durable [RawSms] row, [WorkManagerLiveSmsWorkScheduler]'s
 * work request, [LiveSmsProcessingWorker], the AlJazira parser, reconciliation,
 * and review. [androidx.test.core.app.ActivityScenario] recreate is not a
 * process-death test and is not used here.
 *
 * Process death closes the database connection. Resume calls
 * [LiveSmsIntake.schedulePendingProcessing], the sweep
 * [com.baraa.masroof.MasroofApplication] startup runs, and a new
 * [LiveSmsProcessingWorker]. Work that already started stays in a device-local
 * queue of production work requests, which is the WorkManager retry of an
 * unfinished worker.
 */
@RunWith(AndroidJUnit4::class)
class SmsPersistenceRestartTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val openSessions = mutableListOf<DeviceSmsSession>()

    @After
    fun releaseSessions() {
        openSessions.forEach { session -> session.closeAndDelete() }
        openSessions.clear()
    }

    @Test(timeout = 120_000)
    fun intakePostsOneMovement_requiresReview_andLeavesNonFinancialUnposted() = runBlocking {
        assertNotRobolectric()
        val session = track(DeviceSmsSession(context, "m13-journeys.db"))
        session.openFresh()
        assertEquals(EvidenceCounts.EMPTY, session.counts())

        val purchase = session.ingest(PURCHASE_BODY, PURCHASE_AT)
        session.assertWorkPayloadHasNoMessageText(purchase.rawSmsId)
        assertEquals(EvidenceCounts(rawSms = 1, parsedEvents = 0, transactions = 0, reviews = 0), session.counts())
        assertEquals(ListenableWorker.Result.success(), session.runWorker(purchase.rawSmsId))
        val posted = session.postedPurchase(purchase.rawSmsId)
        assertEquals(EvidenceCounts(rawSms = 1, parsedEvents = 1, transactions = 1, reviews = 0), session.counts())
        assertNull(session.reviewRepo.findByRawSmsId(purchase.rawSmsId))

        val review = session.ingest(REVIEW_BODY, REVIEW_AT)
        assertEquals(ListenableWorker.Result.success(), session.runWorker(review.rawSmsId))
        assertRequiredReview(session, review.rawSmsId)
        assertEquals(EvidenceCounts(rawSms = 2, parsedEvents = 2, transactions = 1, reviews = 1), session.counts())
        assertEquals(posted.transactionId, session.ftRepo.findByRawSmsId(purchase.rawSmsId)!!.id)

        val otp = session.ingest(OTP_BODY, OTP_AT)
        assertEquals(ListenableWorker.Result.success(), session.runWorker(otp.rawSmsId))
        assertTerminalNonFinancial(session, otp.rawSmsId)
        assertEquals(EvidenceCounts(rawSms = 3, parsedEvents = 3, transactions = 1, reviews = 1), session.counts())
        assertEquals(0, session.startupSweep())
        assertTrue(session.retryRepo.listRetryableRawSmsIds(ProcessingRetryMode.LIVE).isEmpty())

        session.destroyConnection()
        session.reopen()
        assertEquals(0, session.startupSweep())
        assertEquals(ListenableWorker.Result.success(), session.runWorker(purchase.rawSmsId))
        assertEquals(ListenableWorker.Result.success(), session.runWorker(review.rawSmsId))
        assertEquals(ListenableWorker.Result.success(), session.runWorker(otp.rawSmsId))
        assertEquals(EvidenceCounts(rawSms = 3, parsedEvents = 3, transactions = 1, reviews = 1), session.counts())
        assertEquals(posted.transactionId, session.ftRepo.findByRawSmsId(purchase.rawSmsId)!!.id)
        assertEquals(listOf(purchase.rawSmsId), session.ftRepo.listRawSmsIds(posted.transactionId))
        assertRequiredReview(session, review.rawSmsId)
        assertTerminalNonFinancial(session, otp.rawSmsId)
        assertFalse(session.queueText().contains(OTP_CODE))
        assertFalse(session.queueText().contains("Keeta"))
    }

    @Test(timeout = 120_000)
    fun processDeathAfterCapture_startupSweepPostsExactlyOnce() = runBlocking {
        assertNotRobolectric()
        val session = track(DeviceSmsSession(context, "m13-after-capture.db"))
        session.openFresh()
        val raw = purchaseSms(PURCHASE_AT)
        val death = runCatching {
            session.intake(DeathBeforeEnqueue()).ingest(raw)
        }.exceptionOrNull()
        assertTrue(death is ProcessDeath)
        assertEquals("raw_sms_capture", (death as ProcessDeath).stage)
        assertEquals(listOf(raw.id), session.rawRepo.listIdsAwaitingProcessing())
        assertEquals(EvidenceCounts(rawSms = 1, parsedEvents = 0, transactions = 0, reviews = 0), session.counts())
        assertTrue(session.scheduler.read().isEmpty())

        session.destroyConnection()
        session.reopen()
        assertEquals(1, session.startupSweep())
        assertEquals(listOf(raw.id), session.scheduler.read())
        assertEquals(ListenableWorker.Result.success(), session.runWorker(raw.id))
        val posted = session.postedPurchase(raw.id)
        assertEquals(1, session.counts().transactions)
        assertEquals(listOf(raw.id), session.ftRepo.listRawSmsIds(posted.transactionId))
        assertTrue(session.scheduler.read().isEmpty())
    }

    @Test(timeout = 120_000)
    fun processDeathAfterParsedEvent_unfinishedWorkerPostsExactlyOnce() = runBlocking {
        assertNotRobolectric()
        val session = track(DeviceSmsSession(context, "m13-after-parse.db"))
        session.openFresh()
        val captured = session.ingest(PURCHASE_BODY, PURCHASE_AT)
        val death = runCatching {
            session.runWorker(captured.rawSmsId, halt = Halt.AFTER_PARSED_EVENT)
        }.exceptionOrNull()
        assertTrue(death is ProcessDeath)
        assertEquals("parsed_event", (death as ProcessDeath).stage)
        assertEquals(ParseStatus.SUCCESS, session.parsedRepo.findByRawSmsId(captured.rawSmsId)!!.event.parseStatus)
        assertNull(session.ftRepo.findByRawSmsId(captured.rawSmsId))
        assertEquals(listOf(captured.rawSmsId), session.scheduler.read())

        session.destroyConnection()
        session.reopen()
        assertEquals(0, session.startupSweep())
        assertEquals(listOf(captured.rawSmsId), session.scheduler.read())
        assertEquals(ListenableWorker.Result.success(), session.runWorker(captured.rawSmsId))
        val posted = session.postedPurchase(captured.rawSmsId)
        assertEquals(1, session.counts().transactions)
        assertEquals(listOf(captured.rawSmsId), session.ftRepo.listRawSmsIds(posted.transactionId))
    }

    @Test(timeout = 120_000)
    fun processDeathAfterReconciliation_replayDoesNotDoublePost() = runBlocking {
        assertNotRobolectric()
        val session = track(DeviceSmsSession(context, "m13-after-reconcile.db"))
        session.openFresh()
        val captured = session.ingest(PURCHASE_BODY, PURCHASE_AT)
        val death = runCatching {
            session.runWorker(captured.rawSmsId, halt = Halt.AFTER_RECONCILIATION)
        }.exceptionOrNull()
        assertTrue(death is ProcessDeath)
        assertEquals("reconciliation", (death as ProcessDeath).stage)
        val posted = session.postedPurchase(captured.rawSmsId)
        assertEquals(1, session.counts().transactions)

        session.destroyConnection()
        session.reopen()
        assertEquals(0, session.startupSweep())
        assertEquals(listOf(captured.rawSmsId), session.scheduler.read())
        assertEquals(ListenableWorker.Result.success(), session.runWorker(captured.rawSmsId))
        assertEquals(posted.transactionId, session.ftRepo.findByRawSmsId(captured.rawSmsId)!!.id)
        assertEquals(1, session.counts().transactions)
        assertEquals(listOf(captured.rawSmsId), session.ftRepo.listRawSmsIds(posted.transactionId))
    }

    @Test(timeout = 120_000)
    fun processDeathAfterReviewUpdate_requiredReviewStaysUnposted() = runBlocking {
        assertNotRobolectric()
        val session = track(DeviceSmsSession(context, "m13-after-review.db"))
        session.openFresh()
        val captured = session.ingest(REVIEW_BODY, REVIEW_AT)
        val death = runCatching {
            session.runWorker(captured.rawSmsId, halt = Halt.AFTER_REVIEW_UPDATE)
        }.exceptionOrNull()
        assertTrue(death is ProcessDeath)
        assertEquals("review_update", (death as ProcessDeath).stage)
        val reviewId = assertRequiredReview(session, captured.rawSmsId)
        assertEquals(0, session.counts().transactions)

        session.destroyConnection()
        session.reopen()
        assertEquals(0, session.startupSweep())
        assertEquals(ListenableWorker.Result.success(), session.runWorker(captured.rawSmsId))
        assertEquals(reviewId, assertRequiredReview(session, captured.rawSmsId))
        assertEquals(0, session.counts().transactions)
        assertNull(session.ftRepo.findByRawSmsId(captured.rawSmsId))
        assertEquals(1, session.counts().reviews)
    }

    @Test(timeout = 120_000)
    fun userNonFinancialResolution_survivesRestart_andStaysUnposted() = runBlocking {
        assertNotRobolectric()
        val session = track(DeviceSmsSession(context, "m13-user-resolution.db"))
        session.openFresh()
        val captured = session.ingest(PURCHASE_BODY, PURCHASE_AT)
        assertEquals(ListenableWorker.Result.success(), session.runWorker(captured.rawSmsId))
        val posted = session.postedPurchase(captured.rawSmsId)
        val ignored = TransactionIgnoreService(
            financialTransactionRepository = session.ftRepo,
            reviewRepository = session.reviewRepo,
            clock = session.clock,
            appLogService = session.appLog,
        ).ignore(posted.transactionId)
        assertTrue(ignored is com.baraa.masroof.application.transaction.IgnoreResult.Success)
        assertNull(session.ftRepo.findByRawSmsId(captured.rawSmsId))
        val review = session.reviewRepo.findByRawSmsId(captured.rawSmsId)
        assertNotNull(review)
        assertEquals(ReviewStatus.RESOLVED, review!!.status)
        assertEquals(ReviewResolutionKind.USER_NON_FINANCIAL, review.resolutionKind)
        assertNull(review.resolvedTransactionId)

        session.destroyConnection()
        session.reopen()
        assertEquals(0, session.startupSweep())
        assertEquals(ListenableWorker.Result.success(), session.runWorker(captured.rawSmsId))
        assertEquals(0, session.counts().transactions)
        val survived = session.reviewRepo.findByRawSmsId(captured.rawSmsId)
        assertNotNull(survived)
        assertEquals(review.id, survived!!.id)
        assertEquals(ReviewStatus.RESOLVED, survived.status)
        assertEquals(ReviewResolutionKind.USER_NON_FINANCIAL, survived.resolutionKind)
        assertNull(session.ftRepo.findByRawSmsId(captured.rawSmsId))
        assertTrue(session.retryRepo.listRetryableRawSmsIds(ProcessingRetryMode.LIVE).isEmpty())
    }

    @Test(timeout = 120_000)
    fun liveRetry_survivesWorkerTermination_andStaysWhileReconcileIsIncomplete() = runBlocking {
        assertNotRobolectric()
        val session = track(DeviceSmsSession(context, "m13-live-retry.db"))
        session.openFresh()
        val captured = session.ingest(PURCHASE_BODY, PURCHASE_AT)
        val conflicts = AtomicInteger(Int.MAX_VALUE)
        val exhausted = (0 until LiveSmsProcessingWorker.MAX_ATTEMPTS).map { attempt ->
            session.runWorker(captured.rawSmsId, attempt = attempt, ledger = session.conflictLedger(conflicts))
        }
        assertEquals(
            List(LiveSmsProcessingWorker.MAX_ATTEMPTS - 1) { ListenableWorker.Result.retry() } +
                ListenableWorker.Result.failure(),
            exhausted,
        )
        assertNull(session.ftRepo.findByRawSmsId(captured.rawSmsId))
        assertEquals(listOf(captured.rawSmsId), session.retryRepo.listRetryableRawSmsIds(ProcessingRetryMode.LIVE))
        assertTrue(session.scheduler.read().isEmpty())

        session.destroyConnection()
        session.reopen()
        assertEquals(1, session.startupSweep())
        assertEquals(listOf(captured.rawSmsId), session.scheduler.read())
        conflicts.set(Int.MAX_VALUE)
        val stillIncomplete = session.runWorker(
            captured.rawSmsId,
            attempt = 0,
            ledger = session.conflictLedger(conflicts),
        )
        assertEquals(ListenableWorker.Result.retry(), stillIncomplete)
        assertEquals(
            evidence("live retry id=${masked(captured.rawSmsId)}"),
            listOf(captured.rawSmsId),
            session.retryRepo.listRetryableRawSmsIds(ProcessingRetryMode.LIVE),
        )
        assertNull(session.ftRepo.findByRawSmsId(captured.rawSmsId))
        assertEquals(0, session.counts().transactions)

        conflicts.set(0)
        assertEquals(
            ListenableWorker.Result.success(),
            session.runWorker(captured.rawSmsId, ledger = session.conflictLedger(conflicts)),
        )
        val posted = session.postedPurchase(captured.rawSmsId)
        assertEquals(1, session.counts().transactions)
        assertEquals(listOf(captured.rawSmsId), session.ftRepo.listRawSmsIds(posted.transactionId))
        assertTrue(session.retryRepo.listRetryableRawSmsIds(ProcessingRetryMode.LIVE).isEmpty())
    }

    @Test(timeout = 180_000)
    fun interruptedRestore_beforeCommit_restoresOriginalDatabaseAndPreferences() = runBlocking {
        assertNotRobolectric()
        val packageContext = IsolatedRestoreContext(context, "m13-restore-package.db")
        val packageZip = File(context.cacheDir, "m13-restore-package.masroof")
        val packageLedger = exportPostedPackage(packageContext, packageZip, PACKAGE_AT)
        try {
            listOf(
                DatabaseRestoreRecovery.Stage.PREPARED,
                DatabaseRestoreRecovery.Stage.OLD_PARKED,
            ).forEach { stage ->
                val liveContext = IsolatedRestoreContext(context, "m13-restore-${stage.name.lowercase()}.db")
                val original = prepareOriginalLedger(liveContext, ORIGINAL_AT)
                val live = DatabaseRestoreRecovery.liveDatabase(liveContext)
                val restarted = AtomicInteger(0)
                val outcome = runCatching {
                    importPackage(
                        liveContext = liveContext,
                        packageZip = packageZip,
                        onRestart = { restarted.incrementAndGet() },
                        afterStage = { reached ->
                            if (reached == stage) throw DatabaseRestoreRecovery.ProcessTerminated(reached)
                        },
                    )
                }
                val death = outcome.exceptionOrNull()
                assertTrue(death is DatabaseRestoreRecovery.ProcessTerminated)
                assertEquals(stage.name, death!!.message?.substringAfterLast(' '))
                assertEquals(0, restarted.get())
                if (stage == DatabaseRestoreRecovery.Stage.OLD_PARKED) {
                    assertFalse(live.exists())
                } else {
                    assertSqliteDatabase(live)
                    assertEquals(listOf(original.transactionId), transactionIds(live))
                }
                assertTrue(File(live.path + ".restore-journal").isFile)
                assertTrue(File(live.path + ".restore-journal").readText().contains("stage=${stage.name}"))

                DatabaseRestoreRecovery.recover(liveContext)

                assertSqliteDatabase(live)
                assertEquals(listOf(original.transactionId), transactionIds(live))
                assertFalse(transactionIds(live).contains(packageLedger.transactionId))
                assertEquals(listOf(original.rawSmsId), linkedRawSmsIds(live, original.transactionId))
                assertEquals(1, countRows(live, "financial_transaction"))
                assertEquals(1, countRows(live, "raw_sms"))
                assertRestoreArtifactsGone(live)
                assertOriginalPreferences(liveContext)
                assertFalse(live.path == context.getDatabasePath(MasroofDatabase.NAME).path)
                wipeIsolated(liveContext)
            }
        } finally {
            packageZip.delete()
            wipeIsolated(packageContext)
        }
    }

    @Test(timeout = 180_000)
    fun secondImport_ofTheSamePackage_keepsOneLedger() = runBlocking {
        assertNotRobolectric()
        val packageContext = IsolatedRestoreContext(context, "m13-import-package.db")
        val liveContext = IsolatedRestoreContext(context, "m13-import-live.db")
        val packageZip = File(context.cacheDir, "m13-import-package.masroof")
        try {
            val packageLedger = exportPostedPackage(packageContext, packageZip, PACKAGE_AT)
            val original = prepareOriginalLedger(liveContext, ORIGINAL_AT)
            val live = DatabaseRestoreRecovery.liveDatabase(liveContext)

            val firstRestart = AtomicInteger(0)
            val first = importPackage(liveContext, packageZip, onRestart = { firstRestart.incrementAndGet() })
            assertEquals(BackupImportOutcome.SuccessNeedsRestart, first)
            assertEquals(1, firstRestart.get())
            DatabaseRestoreRecovery.recover(liveContext)
            assertEquals(listOf(packageLedger.transactionId), transactionIds(live))
            assertFalse(transactionIds(live).contains(original.transactionId))
            assertEquals(listOf(packageLedger.rawSmsId), linkedRawSmsIds(live, packageLedger.transactionId))
            assertTrue(onboarding(liveContext).getBoolean("historical_import_completed", false))
            assertRestoreArtifactsGone(live)

            val secondRestart = AtomicInteger(0)
            val reopened = openNamedDatabase(liveContext)
            try {
                val second = DatabaseBackupService(
                    appContext = liveContext,
                    database = reopened,
                    closeDatabase = { if (reopened.isOpen) reopened.close() },
                    appVersionName = "m13-emulator",
                    clockEpochMillis = { 1_700_000_000_000L },
                    restartProcess = { secondRestart.incrementAndGet() },
                ).importFrom(Uri.fromFile(packageZip))
                assertEquals(BackupImportOutcome.SuccessNeedsRestart, second)
            } finally {
                if (reopened.isOpen) reopened.close()
            }
            assertEquals(1, secondRestart.get())
            DatabaseRestoreRecovery.recover(liveContext)
            DatabaseRestoreRecovery.recover(liveContext)
            assertEquals(listOf(packageLedger.transactionId), transactionIds(live))
            assertEquals(1, countRows(live, "financial_transaction"))
            assertEquals(1, countRows(live, "raw_sms"))
            assertEquals(listOf(packageLedger.rawSmsId), linkedRawSmsIds(live, packageLedger.transactionId))
            assertRestoreArtifactsGone(live)
            assertSqliteDatabase(live)
        } finally {
            packageZip.delete()
            wipeIsolated(packageContext)
            wipeIsolated(liveContext)
        }
    }

    private fun track(session: DeviceSmsSession): DeviceSmsSession {
        openSessions += session
        return session
    }

    private suspend fun exportPostedPackage(
        packageContext: IsolatedRestoreContext,
        packageZip: File,
        receivedAt: String,
    ): PostedLedger {
        val session = track(DeviceSmsSession(packageContext, MasroofDatabase.NAME, "m13-package-queue.txt"))
        session.openFresh()
        val captured = session.ingest(PURCHASE_BODY, receivedAt)
        assertEquals(ListenableWorker.Result.success(), session.runWorker(captured.rawSmsId))
        val posted = session.postedPurchase(captured.rawSmsId)
        writePreferences(
            packageContext,
            onboardingCompleted = true,
            importCompleted = true,
            languageTag = AppLocale.TAG_EN,
            themeMode = ThemeMode.DARK.name,
            reparsedSchemaVersion = null,
        )
        if (packageZip.exists()) packageZip.delete()
        val exported = DatabaseBackupService(
            appContext = packageContext,
            database = session.database,
            closeDatabase = { error("export must not close the package database") },
            appVersionName = "m13-emulator",
            clockEpochMillis = { 1_700_000_000_000L },
            restartProcess = { error("export must not restart the process") },
        ).exportTo(Uri.fromFile(packageZip))
        assertTrue(exported.exceptionOrNull()?.let { "export failed: ${it.javaClass.simpleName}" } ?: "export failed", exported.isSuccess)
        session.checkpointAndClose()
        return posted
    }

    private suspend fun prepareOriginalLedger(
        liveContext: IsolatedRestoreContext,
        receivedAt: String,
    ): PostedLedger {
        wipeIsolated(liveContext)
        val session = track(DeviceSmsSession(liveContext, MasroofDatabase.NAME, "m13-original-queue.txt"))
        session.openFresh()
        val captured = session.ingest(PURCHASE_BODY, receivedAt)
        assertEquals(ListenableWorker.Result.success(), session.runWorker(captured.rawSmsId))
        val posted = session.postedPurchase(captured.rawSmsId)
        writePreferences(
            liveContext,
            onboardingCompleted = false,
            importCompleted = false,
            languageTag = AppLocale.TAG_AR,
            themeMode = ThemeMode.LIGHT.name,
            reparsedSchemaVersion = 12,
        )
        session.checkpointAndClose()
        return posted
    }

    private suspend fun importPackage(
        liveContext: IsolatedRestoreContext,
        packageZip: File,
        onRestart: () -> Unit = {},
        afterStage: (DatabaseRestoreRecovery.Stage) -> Unit = {},
    ): BackupImportOutcome {
        val liveDatabase = openNamedDatabase(liveContext)
        return try {
            DatabaseBackupService(
                appContext = liveContext,
                database = liveDatabase,
                closeDatabase = { if (liveDatabase.isOpen) liveDatabase.close() },
                appVersionName = "m13-emulator",
                clockEpochMillis = { 1_700_000_000_000L },
                restartProcess = onRestart,
                afterRestoreStage = afterStage,
            ).importFrom(Uri.fromFile(packageZip))
        } finally {
            if (liveDatabase.isOpen) liveDatabase.close()
        }
    }

    private fun openNamedDatabase(target: Context): MasroofDatabase =
        Room.databaseBuilder(target, MasroofDatabase::class.java, MasroofDatabase.NAME)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private suspend fun assertRequiredReview(session: DeviceSmsSession, rawSmsId: String): String {
        val event = session.parsedRepo.findByRawSmsId(rawSmsId)
        assertNotNull(event)
        assertEquals(MessageFamily.UNKNOWN, event!!.event.messageFamily)
        assertEquals(ParseStatus.REVIEW_REQUIRED, event.event.parseStatus)
        assertNull(session.ftRepo.findByRawSmsId(rawSmsId))
        val review = session.reviewRepo.findByRawSmsId(rawSmsId)
        assertNotNull(review)
        assertEquals(ReviewStatus.REQUIRED, review!!.status)
        assertNull(review.resolutionKind)
        assertNull(review.resolvedTransactionId)
        assertTrue(review.reasons.contains(IngestionReviewService.REASON_PARSE_REVIEW_REQUIRED))
        return review.id
    }

    private suspend fun assertTerminalNonFinancial(session: DeviceSmsSession, rawSmsId: String) {
        val event = session.parsedRepo.findByRawSmsId(rawSmsId)
        assertNotNull(event)
        assertEquals(MessageFamily.OTP, event!!.event.messageFamily)
        assertEquals(ParseStatus.NON_FINANCIAL, event.event.parseStatus)
        assertNull(session.ftRepo.findByRawSmsId(rawSmsId))
        assertNull(session.reviewRepo.findByRawSmsId(rawSmsId))
        assertFalse(session.rawRepo.listIdsAwaitingProcessing().contains(rawSmsId))
    }

    private fun assertOriginalPreferences(target: Context) {
        val onboarding = onboarding(target)
        assertTrue(onboarding.contains("onboarding_completed"))
        assertFalse(onboarding.getBoolean("onboarding_completed", true))
        assertFalse(onboarding.getBoolean("historical_import_completed", true))
        assertEquals(
            AppLocale.TAG_AR,
            target.getSharedPreferences(SharedPrefsAppLocaleRepository.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(SharedPrefsAppLocaleRepository.KEY_LANGUAGE_TAG, null),
        )
        assertEquals(
            ThemeMode.LIGHT.name,
            target.getSharedPreferences(SharedPrefsThemePreferencesRepository.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(SharedPrefsThemePreferencesRepository.KEY_THEME_MODE, null),
        )
        assertEquals(
            12,
            target.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, -1),
        )
    }

    private fun writePreferences(
        target: Context,
        onboardingCompleted: Boolean,
        importCompleted: Boolean,
        languageTag: String,
        themeMode: String,
        reparsedSchemaVersion: Int?,
    ) {
        val onboardingCommitted = onboarding(target).edit()
            .putBoolean("onboarding_started", true)
            .putBoolean("onboarding_completed", onboardingCompleted)
            .putBoolean("historical_import_completed", importCompleted)
            .remove("historical_import_start_epoch_millis")
            .commit()
        check(onboardingCommitted) { "Cannot store onboarding preferences" }
        val localeCommitted = target.getSharedPreferences(SharedPrefsAppLocaleRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SharedPrefsAppLocaleRepository.KEY_LANGUAGE_TAG, languageTag)
            .commit()
        check(localeCommitted) { "Cannot store locale preferences" }
        val themeCommitted = target.getSharedPreferences(
            SharedPrefsThemePreferencesRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit()
            .putString(SharedPrefsThemePreferencesRepository.KEY_THEME_MODE, themeMode)
            .commit()
        check(themeCommitted) { "Cannot store theme preferences" }
        val maintenance = target.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (reparsedSchemaVersion == null) {
            maintenance.remove(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION)
        } else {
            maintenance.putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, reparsedSchemaVersion)
        }
        check(maintenance.commit()) { "Cannot store maintenance preferences" }
    }

    private fun onboarding(target: Context): SharedPreferences =
        target.getSharedPreferences(SharedPrefsOnboardingPreferencesRepository.PREFS_NAME, Context.MODE_PRIVATE)

    private fun wipeIsolated(target: IsolatedRestoreContext) {
        val live = DatabaseRestoreRecovery.liveDatabase(target)
        DatabaseRestoreRecovery.deleteRestoreArtifacts(live)
        live.delete()
        listOf("-wal", "-shm", "-journal").forEach { suffix -> File(live.path + suffix).delete() }
        listOf(
            SharedPrefsOnboardingPreferencesRepository.PREFS_NAME,
            SharedPrefsAppLocaleRepository.PREFS_NAME,
            SharedPrefsThemePreferencesRepository.PREFS_NAME,
            MaintenancePreferences.PREFS_NAME,
        ).forEach { name ->
            target.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun assertRestoreArtifactsGone(live: File) {
        assertFalse(File(live.path + ".restore-journal").exists())
        assertFalse(File(live.path + ".restore-journal.tmp").exists())
        assertFalse(DatabaseRestoreRecovery.incomingFile(live).exists())
        assertFalse(File(live.path + ".rollback").exists())
        assertFalse(File(live.path + ".prefs-original").exists())
        assertFalse(File(live.path + ".prefs-incoming").exists())
    }

    private fun assertSqliteDatabase(file: File) {
        assertTrue(file.isFile)
        val header = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { input ->
            assertEquals(SQLITE_HEADER.size, input.read(header))
        }
        assertTrue(header.contentEquals(SQLITE_HEADER))
    }

    private fun transactionIds(file: File): List<String> =
        queryStrings(file, "SELECT id FROM financial_transaction ORDER BY id")

    private fun linkedRawSmsIds(file: File, transactionId: String): List<String> =
        queryStrings(
            file,
            "SELECT rawSmsId FROM financial_transaction_raw_sms_link WHERE transactionId = ? ORDER BY rawSmsId",
            transactionId,
        )

    private fun countRows(file: File, table: String): Int {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                check(cursor.moveToFirst())
                return cursor.getInt(0)
            }
        }
    }

    private fun queryStrings(file: File, sql: String, vararg args: String): List<String> {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, args).use { cursor ->
                val values = mutableListOf<String>()
                while (cursor.moveToNext()) values += cursor.getString(0)
                return values
            }
        }
    }

    private fun assertNotRobolectric() {
        assertFalse(
            "M13 evidence is emulator instrumentation on API ${Build.VERSION.SDK_INT}, not Robolectric",
            Build.FINGERPRINT.equals("robolectric", ignoreCase = true),
        )
    }

    private fun evidence(detail: String): String =
        "emulator instrumentation API ${Build.VERSION.SDK_INT}: $detail"

    private fun masked(id: String): String = AppLogFormatting.maskId(id)
}

private enum class Halt {
    NONE,
    AFTER_PARSED_EVENT,
    AFTER_RECONCILIATION,
    AFTER_REVIEW_UPDATE,
}

private class ProcessDeath(val stage: String) : Error("process death after $stage")

private class DeathBeforeEnqueue : LiveSmsWorkScheduler {
    override fun schedule(rawSmsId: String): Unit = throw ProcessDeath("raw_sms_capture")
}

/**
 * Production work requests, persisted like WorkManager's own store.
 * A raw sms id is recorded once, matching unique work that keeps the first request.
 */
private class PersistedWorkScheduler(
    private val file: File,
) : LiveSmsWorkScheduler {
    override fun schedule(rawSmsId: String) {
        val request = WorkManagerLiveSmsWorkScheduler.workRequest(rawSmsId)
        val input = request.workSpec.input
        check(input.keyValueMap.keys == setOf(LiveSmsProcessingWorker.KEY_RAW_SMS_ID))
        check(input.getString(LiveSmsProcessingWorker.KEY_RAW_SMS_ID) == rawSmsId)
        check(request.workSpec.workerClassName == LiveSmsProcessingWorker::class.java.name)
        val persisted = input.keyValueMap.values.joinToString() + request.tags.joinToString()
        check(!persisted.contains("Keeta"))
        check(!persisted.contains("482913"))
        check(!persisted.contains("رمز التحقق"))
        if (rawSmsId in read()) return
        file.parentFile?.mkdirs()
        file.appendText(rawSmsId + "\n")
    }

    fun read(): List<String> =
        if (!file.isFile) {
            emptyList()
        } else {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }

    fun remove(rawSmsId: String) {
        val remaining = read().filter { it != rawSmsId }
        if (remaining.isEmpty()) {
            file.delete()
        } else {
            file.writeText(remaining.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    fun clear() {
        file.delete()
    }
}

private class DeviceSmsSession(
    private val context: Context,
    private val dbName: String,
    queueName: String = "$dbName.work-queue.txt",
) {
    val clock: InstantClock = InstantClock { Instant.parse("2026-08-11T12:00:00Z") }
    val appLog: AppLogService = AppLogService(context)
    val scheduler: PersistedWorkScheduler = PersistedWorkScheduler(File(context.filesDir, queueName))
    private val registry = BankSmsRegistry(listOf(AlJaziraSmsAdapter()))
    lateinit var database: MasroofDatabase
        private set
    lateinit var rawRepo: RoomRawSmsRepository
        private set
    lateinit var parsedRepo: RoomParsedEventRepository
        private set
    lateinit var ftRepo: RoomFinancialTransactionRepository
        private set
    lateinit var reviewRepo: RoomReviewRepository
        private set
    lateinit var retryRepo: RoomProcessingRetryRepository
        private set
    private lateinit var cards: RoomCardRegistryRepository
    private lateinit var accounts: RoomAccountRegistryRepository

    suspend fun openFresh() {
        checkpointAndClose()
        deleteDatabaseFiles()
        scheduler.clear()
        database = openRoom()
        bindRepos()
        cards.setOwnership(CardReference(Bank.BANK_ALJAZIRA, "7271"), OwnershipStatus.OWNED)
    }

    fun reopen() {
        check(::database.isInitialized)
        check(!database.isOpen)
        database = openRoom()
        bindRepos()
    }

    fun checkpointAndClose() {
        if (!::database.isInitialized || !database.isOpen) return
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            cursor.moveToFirst()
        }
        database.close()
    }

    fun destroyConnection() {
        checkpointAndClose()
    }

    fun closeAndDelete() {
        checkpointAndClose()
        deleteDatabaseFiles()
        scheduler.clear()
    }

    suspend fun counts(): EvidenceCounts = EvidenceCounts(
        rawSms = database.rawSmsDao().count(),
        parsedEvents = database.parsedEventDao().count(),
        transactions = database.financialTransactionDao().count(),
        reviews = reviewRepo.listAll().size,
    )

    fun intake(workScheduler: LiveSmsWorkScheduler = scheduler): LiveSmsIntake =
        LiveSmsIntake(
            captureBankSms = CaptureBankSmsUseCase(rawRepo, registry),
            scheduler = workScheduler,
            rawSmsRepository = rawRepo,
            reviewRepository = reviewRepo,
            processingRetryRepository = retryRepo,
            appLogService = appLog,
        )

    suspend fun ingest(body: String, receivedAt: String): CapturedSms {
        val raw = purchaseSms(receivedAt, body)
        val result = intake().ingest(raw)
        check(result is BankSmsCaptureResult.Captured) { "Capture did not persist ${masked(raw.id)}" }
        return CapturedSms(raw.id)
    }

    suspend fun startupSweep(): Int = intake().schedulePendingProcessing()

    suspend fun runWorker(
        rawSmsId: String,
        attempt: Int = 0,
        halt: Halt = Halt.NONE,
        ledger: FinancialTransactionRepository = ftRepo,
    ): ListenableWorker.Result {
        val result = worker(rawSmsId, attempt, halt, ledger).doWork()
        if (result == ListenableWorker.Result.success() || result == ListenableWorker.Result.failure()) {
            scheduler.remove(rawSmsId)
        }
        return result
    }

    fun conflictLedger(conflicts: AtomicInteger): FinancialTransactionRepository =
        object : FinancialTransactionRepository by ftRepo {
            override suspend fun save(
                transaction: FinancialTransaction,
                rawSmsIds: Collection<String>,
            ): FinancialTransactionSaveResult {
                if (conflicts.getAndDecrement() > 0) {
                    return FinancialTransactionSaveResult.Conflict(
                        rawSmsId = rawSmsIds.firstOrNull().orEmpty(),
                        existingTransactionId = "device-conflict",
                    )
                }
                return ftRepo.save(transaction, rawSmsIds)
            }
        }

    suspend fun postedPurchase(rawSmsId: String): PostedLedger {
        val event = parsedRepo.findByRawSmsId(rawSmsId)
        checkNotNull(event) { "Parsed event missing for ${masked(rawSmsId)}" }
        check(event.event.parseStatus == ParseStatus.SUCCESS)
        check(event.event.messageFamily == MessageFamily.PURCHASE)
        val transaction = ftRepo.findByRawSmsId(rawSmsId)
        checkNotNull(transaction) { "Transaction missing for ${masked(rawSmsId)}" }
        check(transaction.type == FinancialTransactionType.EXPENSE)
        check(transaction.merchant == "Keeta")
        check(transaction.amount == Money.of("51.99", Currency.SAR))
        check(ftRepo.listRawSmsIds(transaction.id) == listOf(rawSmsId))
        return PostedLedger(rawSmsId = rawSmsId, transactionId = transaction.id)
    }

    fun assertWorkPayloadHasNoMessageText(rawSmsId: String) {
        val request = WorkManagerLiveSmsWorkScheduler.workRequest(rawSmsId)
        val persisted = request.workSpec.input.keyValueMap.values.joinToString() +
            request.tags.joinToString() +
            scheduler.read().joinToString()
        check(!persisted.contains(PURCHASE_BODY))
        check(!persisted.contains(OTP_BODY))
        check(!persisted.contains("Keeta"))
    }

    fun queueText(): String = scheduler.read().joinToString("\n")

    private fun worker(
        rawSmsId: String,
        attempt: Int,
        halt: Halt,
        ledger: FinancialTransactionRepository,
    ): LiveSmsProcessingWorker =
        TestListenableWorkerBuilder<LiveSmsProcessingWorker>(context)
            .setInputData(LiveSmsProcessingWorker.inputFor(rawSmsId))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(LiveSmsProcessingWorker.Factory({ processStored(halt, ledger) }, appLog))
            .build()

    private fun processStored(
        halt: Halt,
        ledger: FinancialTransactionRepository,
    ): ProcessStoredSmsUseCase {
        val parsedForProcess: ParsedEventRepository = if (halt == Halt.AFTER_PARSED_EVENT) {
            object : ParsedEventRepository by parsedRepo {
                override suspend fun save(
                    event: com.baraa.masroof.domain.model.ParsedEvent,
                    details: ParsedEventDetails,
                ) {
                    parsedRepo.save(event, details)
                    throw ProcessDeath("parsed_event")
                }
            }
        } else {
            parsedRepo
        }
        val effectiveLedger: FinancialTransactionRepository = if (halt == Halt.AFTER_RECONCILIATION) {
            object : FinancialTransactionRepository by ledger {
                override suspend fun save(
                    transaction: FinancialTransaction,
                    rawSmsIds: Collection<String>,
                ): FinancialTransactionSaveResult {
                    val saved = ledger.save(transaction, rawSmsIds)
                    if (saved == FinancialTransactionSaveResult.Saved) throw ProcessDeath("reconciliation")
                    return saved
                }
            }
        } else {
            ledger
        }
        val reviewForUpdate: ReviewRepository = if (halt == Halt.AFTER_REVIEW_UPDATE) {
            object : ReviewRepository by reviewRepo {
                override suspend fun upsertRequired(
                    rawSmsId: String,
                    kind: com.baraa.masroof.domain.model.ReviewKind,
                    reasons: List<String>,
                    now: Instant,
                ) = reviewRepo.upsertRequired(rawSmsId, kind, reasons, now).also {
                    throw ProcessDeath("review_update")
                }
            }
        } else {
            reviewRepo
        }
        val loans = EmptyLoanRegistry
        val ingestionReview = IngestionReviewService(reviewRepo, clock)
        return ProcessStoredSmsUseCase(
            rawSmsRepository = rawRepo,
            parsedEventRepository = parsedForProcess,
            bankSmsRegistry = registry,
            ownershipDiscovery = OwnershipDiscoveryService(accounts, cards, loans),
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = parsedRepo,
                rawSmsRepository = rawRepo,
                financialTransactionRepository = effectiveLedger,
                ownershipResolver = OwnershipResolver(accounts, cards, loans),
                effectiveParsedEventProvider = EffectiveParsedEventProvider(
                    parsedRepo,
                    RoomUserCorrectionRepository(database.userCorrectionDao()),
                ),
                reviewRepository = reviewRepo,
            ),
            reviewQueueUpdater = ReviewQueueUpdater(reviewForUpdate, ftRepo, clock),
            ingestionReviewService = ingestionReview,
            appLogService = appLog,
            processingRecovery = ProcessingRecovery(
                processingRetryRepository = retryRepo,
                reviewRepository = reviewRepo,
                ingestionReviewService = ingestionReview,
                clock = clock,
            ),
            reviewRepository = reviewRepo,
        )
    }

    private fun bindRepos() {
        rawRepo = RoomRawSmsRepository(database.rawSmsDao())
        parsedRepo = RoomParsedEventRepository(database.parsedEventDao())
        ftRepo = RoomFinancialTransactionRepository(database.financialTransactionDao(), database.parsedEventDao())
        reviewRepo = RoomReviewRepository(database.reviewItemDao())
        retryRepo = RoomProcessingRetryRepository(database.processingRetryDao())
        cards = RoomCardRegistryRepository.from(database)
        accounts = RoomAccountRegistryRepository.from(database)
    }

    private fun openRoom(): MasroofDatabase =
        Room.databaseBuilder(context, MasroofDatabase::class.java, dbName)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private fun deleteDatabaseFiles() {
        val path = context.getDatabasePath(dbName)
        DatabaseRestoreRecovery.deleteRestoreArtifacts(path)
        path.delete()
        listOf("-wal", "-shm", "-journal").forEach { suffix -> File(path.path + suffix).delete() }
    }

    private fun masked(id: String): String = AppLogFormatting.maskId(id)

    private object EmptyLoanRegistry : LoanRegistryRepository {
        override suspend fun observe(reference: LoanReference, rawSmsId: String) = Unit
        override suspend fun setOwnership(reference: LoanReference, status: OwnershipStatus) = Unit
        override suspend fun resolve(reference: LoanReference): OwnershipStatus = OwnershipStatus.UNKNOWN
        override suspend fun get(reference: LoanReference): LoanRegistryEntry? = null
        override suspend fun listAll(): List<LoanRegistryEntry> = emptyList()
        override suspend fun updateDisplayName(reference: LoanReference, displayName: String?) = Unit
    }
}

private data class EvidenceCounts(
    val rawSms: Int,
    val parsedEvents: Int,
    val transactions: Int,
    val reviews: Int,
) {
    companion object {
        val EMPTY: EvidenceCounts = EvidenceCounts(0, 0, 0, 0)
    }
}

private data class CapturedSms(val rawSmsId: String)

private data class PostedLedger(val rawSmsId: String, val transactionId: String)

private class IsolatedRestoreContext(
    base: Context,
    private val physicalName: String,
) : ContextWrapper(base) {
    override fun getDatabasePath(name: String): File {
        val dir = baseContext.getDatabasePath("m13-anchor.db").parentFile
            ?: error("database directory missing")
        dir.mkdirs()
        val fileName = if (name == MasroofDatabase.NAME) physicalName else name
        return File(dir, fileName)
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences(preferenceName(name), mode)

    override fun deleteDatabase(name: String): Boolean {
        val path = getDatabasePath(name)
        val deleted = path.delete()
        listOf("-wal", "-shm", "-journal").forEach { suffix -> File(path.path + suffix).delete() }
        return deleted
    }

    private fun preferenceName(name: String): String =
        "m13_${physicalName.replace('.', '_')}_$name"
}

private fun purchaseSms(receivedAt: String, body: String = PURCHASE_BODY): com.baraa.masroof.domain.model.RawSms =
    AndroidSmsMapper.toRawSms(
        ProviderSmsRecord(null, "AlJazira", body, Instant.parse(receivedAt)),
    )
