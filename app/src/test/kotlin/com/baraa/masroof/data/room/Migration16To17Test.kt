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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Migration16To17Test {
    @Test
    fun migrate16To17_preservesRowsAndAddsIndexes() {
        val schema16 = java.io.File("schemas/com.baraa.masroof.data.room.MasroofDatabase/16.json")
        assertTrue(schema16.isFile)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-16-17.db"
        context.deleteDatabase(dbName)

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(16) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            applyExportedSchema(db, schema16, expectedVersion = 16)
                            db.execSQL(
                                """
                                INSERT INTO raw_sms (
                                    id, sender, body, receivedAtEpochMillis, deviceMessageId, bodyHash, dedupeKey
                                ) VALUES (
                                    'raw-1', 'Bank', 'body', 1720000000000, NULL, 'hash', 'dedupe-1'
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO financial_transaction (
                                    id, type, amountDecimal, amountCurrency, occurredAtEpochMillis,
                                    sourceContainerId, destinationContainerId, merchant, counterparty,
                                    categoryId, appliedExchangeRate, exchangeRateSource, occurredAtZone
                                ) VALUES (
                                    'tx-1', 'EXPENSE', '10.00', 'SAR', 1720000000000,
                                    NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO user_correction (
                                    id, targetRawSmsId, correctedMessageFamily, correctedAmountDecimal,
                                    correctedAmountCurrency, correctedMerchant, correctedCounterparty,
                                    createdAtEpochMillis
                                ) VALUES (
                                    'corr-1', 'raw-1', NULL, NULL, NULL, NULL, NULL, 1720000001000
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO parsed_event (
                                    id, rawSmsId, bankId, messageFamily, confidenceScore, confidenceReasons,
                                    parseStatus, cardBankId, cardLast4, cardSmsChannel
                                ) VALUES (
                                    'pe-1', 'raw-1', 'aljazira', 'EXPENSE', 1.0, '', 'SUCCESS',
                                    'aljazira', '1234', 'CREDIT'
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
        openHelper.writableDatabase.close()
        openHelper.close()

        val room = Room.databaseBuilder(context, MasroofDatabase::class.java, dbName)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(MasroofDatabase.VERSION, db.version)
            assertEquals(1, count(db, "raw_sms"))
            assertEquals(1, count(db, "financial_transaction"))
            assertEquals(1, count(db, "user_correction"))
            assertEquals(1, count(db, "parsed_event"))

            assertUsesIndex(
                db,
                """
                SELECT * FROM financial_transaction
                WHERE type IN ('EXPENSE') AND occurredAtEpochMillis >= 0
                ORDER BY occurredAtEpochMillis, id
                """.trimIndent(),
                "index_financial_transaction_type_occurredAtEpochMillis",
            )
            assertUsesIndex(
                db,
                """
                SELECT * FROM user_correction
                WHERE targetRawSmsId IN ('raw-1')
                ORDER BY targetRawSmsId ASC, createdAtEpochMillis ASC, id ASC
                """.trimIndent(),
                "index_user_correction_targetRawSmsId_createdAtEpochMillis_id",
            )
            assertUsesIndex(
                db,
                """
                SELECT pe.* FROM parsed_event pe
                INNER JOIN raw_sms rs ON pe.rawSmsId = rs.id
                WHERE rs.receivedAtEpochMillis >= 0 AND rs.receivedAtEpochMillis < 9999999999999
                ORDER BY pe.id
                """.trimIndent(),
                "index_raw_sms_receivedAtEpochMillis",
            )
            assertUsesIndex(
                db,
                """
                SELECT rawSmsId FROM parsed_event
                WHERE cardBankId = 'aljazira' AND cardLast4 = '1234'
                ORDER BY id
                """.trimIndent(),
                "index_parsed_event_cardBankId_cardLast4",
            )
            assertUsesIndex(
                db,
                """
                SELECT * FROM parsed_event
                WHERE id IN (
                    SELECT MAX(id) FROM parsed_event
                    WHERE cardSmsChannel IN ('CREDIT', 'STATEMENT') AND cardLast4 IS NOT NULL
                    GROUP BY cardBankId, cardLast4
                )
                ORDER BY id
                """.trimIndent(),
                "index_parsed_event_cardSmsChannel_cardLast4",
            )
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }

    /**
     * Query plans for the scoped-transfer window and M6 ownership lookups.
     *
     * `datetime(occurredAtLocal)` is not sargable, so that column stays unindexed.
     * `messageFamily` is selected for the transfer window. `(bankId, loanType)` is
     * selected when loan rows are sparse, which matches stored facts. Account endpoint
     * pairs are used. An `occurredAtLocal` index is not, because `datetime()` wraps it.
     */
    @Test
    fun scopedTransferAndOwnershipQueries_followMeasuredPlans() {
        val schema16 = java.io.File("schemas/com.baraa.masroof.data.room.MasroofDatabase/16.json")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-16-17-plans.db"
        context.deleteDatabase(dbName)
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(16) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            applyExportedSchema(db, schema16, expectedVersion = 16)
                            repeat(80) { i ->
                                db.execSQL(
                                    """
                                    INSERT INTO raw_sms (
                                        id, sender, body, receivedAtEpochMillis, deviceMessageId, bodyHash, dedupeKey
                                    ) VALUES (
                                        'raw-$i', 'Bank', 'body', ${1_720_000_000_000L + i}, NULL, 'hash-$i', 'dedupe-$i'
                                    )
                                    """.trimIndent(),
                                )
                                val family = if (i % 20 == 0) "TRANSFER_OUT" else "EXPENSE"
                                val local = if (i % 2 == 0) "2026-08-03T14:32" else "2026-08-03T14:32:07"
                                db.execSQL(
                                    """
                                    INSERT INTO parsed_event (
                                        id, rawSmsId, bankId, messageFamily, confidenceScore, confidenceReasons,
                                        parseStatus, sourceAccountBankId, sourceAccountMaskedNumber,
                                        destinationAccountBankId, destinationAccountMaskedNumber,
                                        cardBankId, cardLast4, loanType, occurredAtLocal
                                    ) VALUES (
                                        'pe-$i', 'raw-$i', '${if (i % 7 == 0) "aljazira" else "other"}', '$family', 1.0, '', 'SUCCESS',
                                        '${if (i == 3) "aljazira" else "other"}', '${if (i == 3) "3001" else "9$i"}',
                                        '${if (i == 9) "aljazira" else "other"}', '${if (i == 9) "3001" else "8$i"}',
                                        'aljazira', '${1000 + (i % 50)}', ${if (i % 50 == 0) "'PERSONAL'" else "NULL"}, '$local'
                                    )
                                    """.trimIndent(),
                                )
                            }
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        openHelper.writableDatabase.close()
        openHelper.close()
        val room = Room.databaseBuilder(context, MasroofDatabase::class.java, dbName)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            val accountPlan = queryPlan(
                db,
                """
                SELECT rawSmsId FROM parsed_event
                WHERE (sourceAccountBankId = 'aljazira' AND sourceAccountMaskedNumber = '3001')
                   OR (destinationAccountBankId = 'aljazira' AND destinationAccountMaskedNumber = '3001')
                ORDER BY id
                """.trimIndent(),
            )
            assertTrue(accountPlan, "index_parsed_event_sourceAccountBankId_sourceAccountMaskedNumber" in accountPlan)
            assertTrue(accountPlan, "index_parsed_event_destinationAccountBankId_destinationAccountMaskedNumber" in accountPlan)

            val cardPlan = queryPlan(
                db,
                """
                SELECT rawSmsId FROM parsed_event
                WHERE cardBankId = 'aljazira' AND cardLast4 = '1001'
                ORDER BY id
                """.trimIndent(),
            )
            assertTrue(cardPlan, "index_parsed_event_cardBankId_cardLast4" in cardPlan)

            val localSql = """
                SELECT pe.* FROM parsed_event pe
                WHERE pe.messageFamily IN ('TRANSFER_IN', 'TRANSFER_OUT')
                  AND pe.occurredAtLocal IS NOT NULL
                  AND datetime(pe.occurredAtLocal) >= datetime('2026-08-03T14:00:00')
                  AND datetime(pe.occurredAtLocal) < datetime('2026-08-03T15:00:00')
                  AND NOT EXISTS (
                    SELECT 1 FROM financial_transaction_raw_sms_link link
                    WHERE link.rawSmsId = pe.rawSmsId
                  )
                ORDER BY pe.id
            """.trimIndent()
            db.execSQL("CREATE INDEX IF NOT EXISTS `ix_local` ON `parsed_event` (`occurredAtLocal`)")
            val localPlan = queryPlan(db, localSql)
            assertTrue(localPlan, "index_parsed_event_messageFamily" in localPlan)
            assertFalse(localPlan, "ix_local" in localPlan || "occurredAtLocal" in localPlan)

            val loanSql = """
                SELECT rawSmsId FROM parsed_event
                WHERE bankId = 'aljazira' AND loanType = 'PERSONAL'
                ORDER BY id
            """.trimIndent()
            val loanPlan = queryPlan(db, loanSql)
            assertTrue(loanPlan, "index_parsed_event_bankId_loanType" in loanPlan)
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun queryPlan(db: SupportSQLiteDatabase, sql: String): String = buildString {
        db.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            while (cursor.moveToNext()) {
                append(cursor.getString(3))
                append('\n')
            }
        }
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun assertUsesIndex(db: SupportSQLiteDatabase, sql: String, indexName: String) {
        val plan = buildString {
            db.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                while (cursor.moveToNext()) {
                    append(cursor.getString(3))
                    append('\n')
                }
            }
        }
        assertTrue(
            "Expected index $indexName in plan for:\n$sql\nPlan:\n$plan",
            indexName in plan,
        )
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
