package com.baraa.masroof.application.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.logging.AppLogService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    private fun coordinator(reparse: suspend () -> Unit): ParsedEventFactsBackfillCoordinator {
        val prefs = context.getSharedPreferences(MaintenancePreferences.PREFS_NAME, Context.MODE_PRIVATE)
        return ParsedEventFactsBackfillCoordinator(
            prefs = prefs,
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
