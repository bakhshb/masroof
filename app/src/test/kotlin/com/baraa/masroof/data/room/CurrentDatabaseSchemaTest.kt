package com.baraa.masroof.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.TestStorageContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CurrentDatabaseSchemaTest {
    private val raw = RawSms("retained", "TEST", "Synthetic evidence", Instant.parse("2026-10-15T10:00:00Z"),
        "retained", SmsBodyHasher.sha256Hex("Synthetic evidence"))

    @Test fun currentVersionReopensWithoutChangingStoredEvidence() = runBlocking {
        val context = context()
        try {
            var c = AppContainer(context)
            try { c.storage.rawSmsRepository.insertIfAbsent(raw) } finally { c.close() }
            c = AppContainer(context)
            try {
                assertEquals(raw, c.storage.rawSmsRepository.getById(raw.id))
                assertEquals(MasroofDatabase.VERSION, c.storage.database.openHelper.readableDatabase.version)
            } finally { c.close() }
        } finally { context.getDatabasePath(MasroofDatabase.NAME).parentFile?.deleteRecursively() }
    }

    @Test fun olderDatabaseIsRejectedWithoutMigrationOrDataLoss() = runBlocking {
        val context = context()
        try {
            var c = AppContainer(context)
            try { c.storage.rawSmsRepository.insertIfAbsent(raw) } finally { c.close() }
            val file = context.getDatabasePath(MasroofDatabase.NAME)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                // v17 has the same financial tables but no provider-alias table.
                it.execSQL("DROP TABLE raw_sms_provider_alias")
                it.version = 17
            }
            val before = file.readBytes()
            c = AppContainer(context)
            try {
                val failure = runCatching { c.storage.rawSmsRepository.getById(raw.id) }.exceptionOrNull()
                assertNotNull("Older database was silently accepted or migrated", failure)
                assertTrue(failure!!.message.orEmpty().contains("migration", ignoreCase = true))
            } finally { c.close() }
            assertArrayEquals(before, file.readBytes())
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(17, db.version)
                db.rawQuery("SELECT COUNT(*) FROM raw_sms WHERE id = ?", arrayOf(raw.id)).use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
            }
        } finally { context.getDatabasePath(MasroofDatabase.NAME).parentFile?.deleteRecursively() }
    }

    private fun context() = TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "current-schema-${java.util.UUID.randomUUID()}")
}
