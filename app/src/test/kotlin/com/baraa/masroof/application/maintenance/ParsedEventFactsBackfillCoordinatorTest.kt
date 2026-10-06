package com.baraa.masroof.application.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.logging.AppLogService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ParsedEventFactsBackfillCoordinatorTest {

    private lateinit var context: Context
    private lateinit var appLogService: AppLogService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        appLogService = AppLogService(context)
        context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun runIfNeeded_reparsesOncePerSchemaVersion() = runBlocking {
        var reparseCount = 0
        val coordinator = coordinator { reparseCount++ }

        coordinator.runIfNeeded(currentSchemaVersion = 11)
        coordinator.runIfNeeded(currentSchemaVersion = 11)

        assertEquals(1, reparseCount)
    }

    @Test
    fun runIfNeeded_skipsWhenAlreadyAtCurrentVersion() = runBlocking {
        context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 11)
            .commit()

        var reparseCount = 0
        val coordinator = coordinator { reparseCount++ }

        coordinator.runIfNeeded(currentSchemaVersion = 11)

        assertEquals(0, reparseCount)
    }

    @Test
    fun runIfNeeded_runsAgainWhenSchemaAdvances() = runBlocking {
        var reparseCount = 0
        val coordinator = coordinator {
            reparseCount++
        }

        coordinator.runIfNeeded(currentSchemaVersion = 11)
        coordinator.runIfNeeded(currentSchemaVersion = 12)

        assertEquals(2, reparseCount)
    }

    @Test
    fun runIfNeeded_doesNotMarkCompleteWhenReparseFails() = runBlocking {
        val prefs = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val coordinator = ParsedEventFactsBackfillCoordinator(
            prefs = prefs,
            appLogService = appLogService,
            reparseAllStoredEvents = {
                ReparseAllStoredEventsResult(refreshedCount = 0, failedCount = 2)
            },
        )

        coordinator.runIfNeeded(currentSchemaVersion = 11)
        coordinator.runIfNeeded(currentSchemaVersion = 11)

        assertEquals(0, prefs.getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0))
    }

    @Test
    fun runIfNeeded_retriesStoredRawSmsWithoutParsedEvent() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.baraa.masroof.data.room.MasroofDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val rawRepo = com.baraa.masroof.data.repository.RoomRawSmsRepository(db.rawSmsDao())
            val parsedRepo = com.baraa.masroof.data.repository.RoomParsedEventRepository(db.parsedEventDao())
            val body = "شراء عبر الانترنت\nبطاقة: 7271\nلدى: Keeta\nبمبلغ: 51.99 SAR\nفي: 14:32 03-08-2026"
            rawRepo.insertIfAbsent(
                com.baraa.masroof.domain.model.RawSms(
                    id = "android-sms:backlog",
                    sender = "AlJazira",
                    body = body,
                    receivedAt = java.time.Instant.parse("2026-08-03T14:32:00Z"),
                    deviceMessageId = "backlog",
                    bodyHash = com.baraa.masroof.sms.hash.SmsBodyHasher.sha256Hex(body),
                ),
            )
            val reprocessor = StoredSmsReprocessor(
                rawSmsRepository = rawRepo,
                processRawSms = com.baraa.masroof.application.ingestion.ProcessRawSmsUseCase(
                    rawSmsRepository = rawRepo,
                    parsedEventRepository = parsedRepo,
                    bankSmsRegistry = com.baraa.masroof.bank.BankSmsRegistry(
                        listOf(com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter()),
                    ),
                ),
                refreshDerivedState = {},
            )
            val prefs = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            ParsedEventFactsBackfillCoordinator(
                prefs = prefs,
                appLogService = appLogService,
                reparseAllStoredEvents = { reprocessor.reprocessAll() },
            ).runIfNeeded(currentSchemaVersion = 14)

            assertEquals(
                com.baraa.masroof.domain.model.MessageFamily.PURCHASE,
                parsedRepo.findByRawSmsId("android-sms:backlog")!!.event.messageFamily,
            )
            assertEquals(1, db.rawSmsDao().count())
            assertEquals(14, prefs.getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0))
        } finally {
            db.close()
        }
    }

    @Test
    fun pendingRequirement_followsSchemaPolicyAndClearsAfterSuccess() = runBlocking<Unit> {
        val coordinator = coordinator {}
        recordLastReparsedVersion(11)

        assertEquals(MaintenanceRequirement.BACKGROUND, coordinator.pendingRequirement(currentSchemaVersion = 14))

        recordLastReparsedVersion(9)
        assertEquals(MaintenanceRequirement.BLOCKING, coordinator.pendingRequirement(currentSchemaVersion = 14))

        assertEquals(BackfillOutcome.COMPLETED, coordinator.runIfNeeded(currentSchemaVersion = 14))
        assertNull(coordinator.pendingRequirement(currentSchemaVersion = 14))
        assertEquals(BackfillOutcome.UP_TO_DATE, coordinator.runIfNeeded(currentSchemaVersion = 14))
    }

    @Test
    fun runIfNeeded_failedRowsStayPendingAndRetry() = runBlocking<Unit> {
        var attempts = 0
        val coordinator = ParsedEventFactsBackfillCoordinator(
            prefs = prefs(),
            appLogService = appLogService,
            reparseAllStoredEvents = {
                attempts++
                ReparseAllStoredEventsResult(refreshedCount = 3, failedCount = if (attempts == 1) 1 else 0)
            },
        )

        assertEquals(BackfillOutcome.INCOMPLETE, coordinator.runIfNeeded(currentSchemaVersion = 14))
        assertEquals(MaintenanceRequirement.BLOCKING, coordinator.pendingRequirement(currentSchemaVersion = 14))
        assertEquals(BackfillOutcome.COMPLETED, coordinator.runIfNeeded(currentSchemaVersion = 14))
        assertEquals(2, attempts)
        assertEquals(14, prefs().getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0))
    }

    @Test
    fun runIfNeeded_thrownReparseIsIncompleteAndUnrecorded() = runBlocking<Unit> {
        val signal = MaintenanceCompletionSignal()
        val coordinator = ParsedEventFactsBackfillCoordinator(
            prefs = prefs(),
            appLogService = appLogService,
            reparseAllStoredEvents = { error("database unavailable") },
            completionSignal = signal,
        )
        val completion = async(start = CoroutineStart.UNDISPATCHED) { signal.completions.first() }

        assertEquals(BackfillOutcome.INCOMPLETE, coordinator.runIfNeeded(currentSchemaVersion = 14))
        assertEquals(0, prefs().getInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, 0))
        withTimeout(1_000) { completion.await() }
    }

    @Test
    fun runIfNeeded_signalsCompletionOnlyWhenItReparsed() = runBlocking<Unit> {
        val signal = MaintenanceCompletionSignal()
        val coordinator = ParsedEventFactsBackfillCoordinator(
            prefs = prefs(),
            appLogService = appLogService,
            reparseAllStoredEvents = { successResult() },
            completionSignal = signal,
        )
        val emissions = mutableListOf<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { signal.completions.toList(emissions) }

        coordinator.runIfNeeded(currentSchemaVersion = 14)
        coordinator.runIfNeeded(currentSchemaVersion = 14)
        yield()

        assertEquals(1, emissions.size)
        collector.cancel()
    }

    @Test
    fun runIfNeeded_concurrentRunsReparseOnce() = runBlocking<Unit> {
        var reparseCount = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = coordinator {
            reparseCount++
            started.complete(Unit)
            release.await()
        }

        val first = async { coordinator.runIfNeeded(currentSchemaVersion = 14) }
        started.await()
        val second = async { coordinator.runIfNeeded(currentSchemaVersion = 14) }
        yield()
        release.complete(Unit)

        assertEquals(BackfillOutcome.COMPLETED, first.await())
        assertEquals(BackfillOutcome.UP_TO_DATE, second.await())
        assertEquals(1, reparseCount)
    }

    private fun prefs() = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private fun recordLastReparsedVersion(version: Int) {
        prefs().edit().putInt(MaintenancePreferences.KEY_LAST_REPARSED_SCHEMA_VERSION, version).commit()
    }

    private fun coordinator(reparse: suspend () -> Unit): ParsedEventFactsBackfillCoordinator {
        return ParsedEventFactsBackfillCoordinator(
            prefs = prefs(),
            appLogService = appLogService,
            reparseAllStoredEvents = {
                reparse()
                successResult()
            },
        )
    }

    private fun successResult(refreshedCount: Int = 1) =
        ReparseAllStoredEventsResult(refreshedCount = refreshedCount, failedCount = 0)
}
