package com.baraa.masroof.data.room

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.repository.RoomProcessingRetryRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Migration14To15Test {
    @Test
    fun migrate14To15_addsProcessingRetryWithoutTouchingExistingSms() = runBlocking {
        val schema14 = java.io.File("schemas/com.baraa.masroof.data.room.MasroofDatabase/14.json")
        assertTrue(schema14.isFile)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-14-15.db"
        context.deleteDatabase(dbName)

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(14) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            applyExportedSchema(db, schema14, expectedVersion = 14)
                            db.execSQL(
                                """
                                INSERT INTO raw_sms (
                                    id, sender, body, receivedAtEpochMillis, deviceMessageId, bodyHash, dedupeKey
                                ) VALUES (
                                    'sms-legacy', 'AlJazira', 'body', 1, '1', 'hash', 'key'
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        openHelper.writableDatabase.use { db ->
            assertEquals(14, db.version)
        }
        openHelper.close()

        val room = Room.databaseBuilder(context, MasroofDatabase::class.java, dbName)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(MasroofDatabase.VERSION, room.openHelper.writableDatabase.version)
            assertEquals("AlJazira", room.rawSmsDao().getById("sms-legacy")!!.sender)

            val repo = RoomProcessingRetryRepository(room.processingRetryDao())
            assertTrue(repo.listRetryableRawSmsIds().isEmpty())
            repo.markRequired("sms-legacy", Instant.parse("2026-08-11T12:00:00Z"))
            assertEquals(listOf("sms-legacy"), repo.listRetryableRawSmsIds())
            repo.clear("sms-legacy")
            assertTrue(repo.listRetryableRawSmsIds().isEmpty())
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun applyExportedSchema(
        db: SupportSQLiteDatabase,
        schemaFile: java.io.File,
        expectedVersion: Int,
    ) {
        val root = Json.parseToJsonElement(schemaFile.readText()).jsonObject
        val database = root.getValue("database").jsonObject
        assertEquals(expectedVersion, database.getValue("version").jsonPrimitive.content.toInt())
        for (entityEl in database.getValue("entities").jsonArray) {
            val entity = entityEl.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            db.execSQL(
                entity.getValue("createSql").jsonPrimitive.content
                    .replace("\${TABLE_NAME}", tableName),
            )
            for (indexEl in entity["indices"]?.jsonArray.orEmpty()) {
                db.execSQL(
                    indexEl.jsonObject.getValue("createSql").jsonPrimitive.content
                        .replace("\${TABLE_NAME}", tableName),
                )
            }
        }
        for (setupEl in database["setupQueries"]?.jsonArray.orEmpty()) {
            db.execSQL(setupEl.jsonPrimitive.content)
        }
    }
}
