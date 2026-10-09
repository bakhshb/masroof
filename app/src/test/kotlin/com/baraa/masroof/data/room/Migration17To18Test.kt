package com.baraa.masroof.data.room

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Migration17To18Test {
    @Test
    fun migrate17To18_keepsRawSmsAndAddsAliasTable() {
        val schema17 = java.io.File("schemas/com.baraa.masroof.data.room.MasroofDatabase/17.json")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-17-18.db"
        context.deleteDatabase(dbName)
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(17) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            applyExportedSchema(db, schema17)
                            db.execSQL(
                                """
                                INSERT INTO raw_sms (
                                    id, sender, body, receivedAtEpochMillis, deviceMessageId, bodyHash, dedupeKey
                                ) VALUES (
                                    'raw-1', 'AlJazira', 'body', 1720000000000, NULL, 'hash', 'dedupe-1'
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        openHelper.writableDatabase.close()

        val room = Room.databaseBuilder(context, MasroofDatabase::class.java, dbName)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            db.query("SELECT deviceMessageId, body FROM raw_sms WHERE id = 'raw-1'").use { cursor ->
                check(cursor.moveToFirst())
                assertNull(cursor.getString(0))
                assertEquals("body", cursor.getString(1))
            }
            db.execSQL(
                """
                INSERT INTO raw_sms_provider_alias (providerMessageId, rawSmsId)
                VALUES ('42', 'raw-1')
                """.trimIndent(),
            )
            db.query("SELECT deviceMessageId FROM raw_sms WHERE id = 'raw-1'").use { cursor ->
                check(cursor.moveToFirst())
                assertNull(cursor.getString(0))
            }
            db.query(
                "SELECT rawSmsId FROM raw_sms_provider_alias WHERE providerMessageId = '42'",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("raw-1", cursor.getString(0))
            }
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun applyExportedSchema(db: SupportSQLiteDatabase, schemaFile: java.io.File) {
        val root = Json.parseToJsonElement(schemaFile.readText()).jsonObject
        val database = root.getValue("database").jsonObject
        for (entityEl in database.getValue("entities").jsonArray) {
            val entity = entityEl.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            db.execSQL(
                entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName),
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
