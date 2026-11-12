package com.baraa.masroof.integration.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.analytics.*
import com.baraa.masroof.application.analytics.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class RecurringFeedbackPersistenceTest {
    @Test fun explicitFeedbackSurvivesArchiveRestoreAndLegacyRejectionsNeverBecomeLabels() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.nio.file.Files.createTempDirectory("recurring-feedback").toFile()
        val context = object : android.content.ContextWrapper(app) {
            override fun getDatabasePath(name: String) = java.io.File(directory, name)
        }
        val database = AnalyticsDatabase.open(context)
        try {
            val payload = RecurringFeedbackPayload("series", listOf("a", "b", "c"), "NONE")
            val row = RecurringFeedbackRow("series", "NOT_RECURRING", AnalyticsJson.format.encodeToString(payload), 100)
            database.analytics().feedback(row)
            val archive = AnalyticsArchiveStore.export(database)
            AnalyticsBackup.validate(archive)
            assertEquals(4, AnalyticsArchiveStore.validate(archive)!!.version)
            AnalyticsArchiveStore.restore(database, null)
            assertTrue(database.analytics().feedback().isEmpty())
            AnalyticsArchiveStore.restore(database, archive)
            assertEquals(listOf(row), database.analytics().feedback())
            AnalyticsArchiveStore.restore(database, """{"version":3,"candidates":[{"id":"legacy","status":"REJECTED","payload":"{}"}]}""")
            assertTrue(database.analytics().feedback().isEmpty())
            assertEquals("REJECTED", database.analytics().candidates().single().status)
        } finally { database.close(); directory.deleteRecursively() }
    }

    @Test fun schemaThreeMigratesWithoutInventingFeedbackOrChangingLegacyDecisions() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.nio.file.Files.createTempDirectory("recurring-upgrade").toFile()
        val context = object : android.content.ContextWrapper(app) {
            override fun getDatabasePath(name: String) = java.io.File(directory, name)
        }
        val schema = Json.parseToJsonElement(java.io.File("../data/schemas/com.baraa.masroof.data.analytics.AnalyticsDatabase/3.json").readText())
            .jsonObject.getValue("database").jsonObject
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(AnalyticsDatabase.NAME), null).use { sqlite ->
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                sqlite.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", entity.getValue("tableName").jsonPrimitive.content))
                entity["indices"]?.jsonArray?.forEach { index ->
                    sqlite.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", entity.getValue("tableName").jsonPrimitive.content))
                }
            }
            sqlite.execSQL("INSERT INTO recurring_candidate VALUES ('legacy','REJECTED','{}')")
            sqlite.version = 3
        }
        val database = AnalyticsDatabase.open(context)
        try {
            assertTrue(database.analytics().feedback().isEmpty())
            assertEquals("REJECTED", database.analytics().candidates().single().status)
            assertEquals(4, database.openHelper.readableDatabase.version)
        } finally { database.close(); directory.deleteRecursively() }
    }
}
