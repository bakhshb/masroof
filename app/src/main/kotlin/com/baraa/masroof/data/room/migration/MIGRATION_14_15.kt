package com.baraa.masroof.data.room.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Processing-retry markers. Existing rows stay valid; nothing is backfilled.
 */
val MIGRATION_14_15: Migration = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `processing_retry` (`rawSmsId` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `mode` TEXT NOT NULL, PRIMARY KEY(`rawSmsId`), FOREIGN KEY(`rawSmsId`) REFERENCES `raw_sms`(`id`) ON UPDATE CASCADE ON DELETE RESTRICT )
            """.trimIndent(),
        )
    }
}
