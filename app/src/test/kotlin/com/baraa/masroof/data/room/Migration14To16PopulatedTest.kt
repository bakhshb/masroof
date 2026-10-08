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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opens a populated published v14 database with the current schema.
 * Legacy instants stay as stored. The new zone column stays null until a later reprocess.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Migration14To16PopulatedTest {
    @Test
    fun migratePopulated14To16_preservesRowsAndLeavesZoneNull() {
        val schema14 = java.io.File("schemas/com.baraa.masroof.data.room.MasroofDatabase/14.json")
        assertTrue(schema14.isFile)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-14-16-populated.db"
        context.deleteDatabase(dbName)

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(14) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            applyExportedSchema(db, schema14, expectedVersion = 14)
                            insertRepresentativeRows(db)
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
            assertEquals(2, count(db, "raw_sms"))
            assertEquals(1, count(db, "parsed_event"))
            assertEquals(1, count(db, "financial_transaction"))
        }
        openHelper.close()

        val room = Room.databaseBuilder(context, MasroofDatabase::class.java, dbName)
            .addMigrations(*MasroofDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(MasroofDatabase.VERSION, db.version)
            assertEquals(2, count(db, "raw_sms"))
            assertEquals(1, count(db, "parsed_event"))
            assertEquals(1, count(db, "financial_transaction"))
            assertEquals(1, count(db, "financial_transaction_raw_sms_link"))
            assertEquals(1, count(db, "review_item"))
            assertEquals(1, count(db, "user_correction"))
            assertEquals(1, count(db, "commitment"))
            assertEquals(1, count(db, "account_registry"))
            assertEquals(1, count(db, "card_registry"))
            assertEquals(1, count(db, "loan_registry"))
            assertEquals(1, count(db, "credit_facility"))
            assertEquals(1, count(db, "bank_registry"))

            db.query(
                """
                SELECT sender, receivedAtEpochMillis FROM raw_sms WHERE id = 'sms-linked'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("AlJazira", cursor.getString(0))
                assertEquals(RECEIVED_AT, cursor.getLong(1))
            }
            db.query(
                """
                SELECT amountDecimal, amountCurrency, messageFamily, rawSmsId
                FROM parsed_event WHERE id = 'pe-linked'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("51.99", cursor.getString(0))
                assertEquals("SAR", cursor.getString(1))
                assertEquals("PURCHASE", cursor.getString(2))
                assertEquals("sms-linked", cursor.getString(3))
            }
            db.query(
                """
                SELECT occurredAtEpochMillis, occurredAtZone, appliedExchangeRate, exchangeRateSource, type
                FROM financial_transaction WHERE id = 'tx-legacy'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(OCCURRED_AT, cursor.getLong(0))
                assertNull(cursor.getString(1))
                assertEquals("3.75", cursor.getString(2))
                assertEquals("SMS", cursor.getString(3))
                assertEquals("EXPENSE", cursor.getString(4))
            }
            db.query(
                """
                SELECT link.transactionId
                FROM financial_transaction_raw_sms_link link
                JOIN financial_transaction tx ON tx.id = link.transactionId
                JOIN raw_sms sms ON sms.id = link.rawSmsId
                WHERE link.rawSmsId = 'sms-linked'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("tx-legacy", cursor.getString(0))
            }
            db.query("SELECT rawSmsId, kind, status FROM review_item WHERE id = 'rev-held'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("sms-held", cursor.getString(0))
                assertEquals("NEEDS_REVIEW", cursor.getString(1))
                assertEquals("REQUIRED", cursor.getString(2))
            }
            db.query(
                "SELECT sourceTransactionId, amountDecimal FROM commitment WHERE id = 'commit-legacy'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("tx-legacy", cursor.getString(0))
                assertEquals("51.99", cursor.getString(1))
            }
            db.query("SELECT ownershipStatus FROM account_registry WHERE maskedNumber = '3001'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("OWNED", cursor.getString(0))
            }
            db.query("SELECT ownershipStatus FROM card_registry WHERE last4 = '7271'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("OWNED", cursor.getString(0))
            }
            db.query("SELECT loanType, ownershipStatus FROM loan_registry WHERE bankId = 'BANK_ALJAZIRA'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("PERSONAL", cursor.getString(0))
                assertEquals("OWNED", cursor.getString(1))
            }
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun insertRepresentativeRows(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO raw_sms (
                id, sender, body, receivedAtEpochMillis, deviceMessageId, bodyHash, dedupeKey
            ) VALUES
                ('sms-linked', 'AlJazira', 'purchase body', $RECEIVED_AT, '100', 'hash-linked', 'dedupe-linked'),
                ('sms-held', 'AlJazira', 'held body', $RECEIVED_AT, '101', 'hash-held', 'dedupe-held')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO parsed_event (
                id, rawSmsId, bankId, messageFamily, direction, amountDecimal, amountCurrency,
                cardBankId, cardLast4, merchant, confidenceScore, confidenceReasons, parseStatus,
                occurredAtEpochMillis, salaryIncomeWording
            ) VALUES (
                'pe-linked', 'sms-linked', 'BANK_ALJAZIRA', 'PURCHASE', 'OUTGOING', '51.99', 'SAR',
                'BANK_ALJAZIRA', '7271', 'Keeta', 1.0, '', 'SUCCESS',
                $OCCURRED_AT, 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO financial_transaction (
                id, type, amountDecimal, amountCurrency, occurredAtEpochMillis,
                sourceContainerId, destinationContainerId, merchant, counterparty, categoryId,
                appliedExchangeRate, exchangeRateSource
            ) VALUES (
                'tx-legacy', 'EXPENSE', '51.99', 'SAR', $OCCURRED_AT,
                'card:BANK_ALJAZIRA:7271', NULL, 'Keeta', NULL, NULL,
                '3.75', 'SMS'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO financial_transaction_raw_sms_link (rawSmsId, transactionId)
            VALUES ('sms-linked', 'tx-legacy')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO review_item (
                id, rawSmsId, kind, status, reasons, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                'rev-held', 'sms-held', 'NEEDS_REVIEW', 'REQUIRED', 'needs_review',
                $RECEIVED_AT, $RECEIVED_AT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO user_correction (
                id, targetRawSmsId, correctedMerchant, createdAtEpochMillis
            ) VALUES (
                'corr-held', 'sms-held', 'Keeta', $RECEIVED_AT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO commitment (
                id, name, amountDecimal, amountCurrency, transactionDateIso, recurrence, dueDateIso,
                active, pauseIntervalsJson, sourceTransactionId, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                'commit-legacy', 'Keeta', '51.99', 'SAR', '2024-08-26', NULL, NULL,
                1, '[]', 'tx-legacy', $RECEIVED_AT, $RECEIVED_AT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO bank_registry (bankId, displayName) VALUES ('BANK_ALJAZIRA', 'AlJazira')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO account_registry (
                id, bankId, maskedNumber, ownershipStatus, displayName, accountType,
                firstSeenRawSmsId, lastSeenRawSmsId
            ) VALUES (
                'account:BANK_ALJAZIRA:3001', 'BANK_ALJAZIRA', '3001', 'OWNED', 'Current', 'CURRENT',
                'sms-linked', 'sms-linked'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO card_registry (
                id, bankId, last4, ownershipStatus, displayName, firstSeenRawSmsId, lastSeenRawSmsId
            ) VALUES (
                'card:BANK_ALJAZIRA:7271', 'BANK_ALJAZIRA', '7271', 'OWNED', 'Card',
                'sms-linked', 'sms-linked'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO loan_registry (
                id, bankId, loanType, ownershipStatus, displayName, firstSeenRawSmsId, lastSeenRawSmsId
            ) VALUES (
                'loan:BANK_ALJAZIRA:PERSONAL', 'BANK_ALJAZIRA', 'PERSONAL', 'OWNED', 'Personal',
                'sms-linked', 'sms-linked'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO credit_facility (id, bankId, primaryLast4, displayName)
            VALUES ('facility:BANK_ALJAZIRA:7271', 'BANK_ALJAZIRA', '7271', 'Facility')
            """.trimIndent(),
        )
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int {
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
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

    private companion object {
        const val RECEIVED_AT = 1_724_682_600_000L
        const val OCCURRED_AT = 1_724_674_200_000L
    }
}
