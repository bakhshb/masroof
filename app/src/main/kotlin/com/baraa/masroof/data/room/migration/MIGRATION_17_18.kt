package com.baraa.masroof.data.room.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Provider-id aliases for a live RawSms. Existing raw_sms rows are not rewritten.
 */
val MIGRATION_17_18: Migration = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `raw_sms_provider_alias` (`providerMessageId` TEXT NOT NULL, `rawSmsId` TEXT NOT NULL, PRIMARY KEY(`providerMessageId`), FOREIGN KEY(`rawSmsId`) REFERENCES `raw_sms`(`id`) ON UPDATE CASCADE ON DELETE RESTRICT )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_raw_sms_provider_alias_rawSmsId` ON `raw_sms_provider_alias` (`rawSmsId`)
            """.trimIndent(),
        )
    }
}
