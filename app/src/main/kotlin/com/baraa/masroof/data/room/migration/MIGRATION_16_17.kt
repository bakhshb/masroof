package com.baraa.masroof.data.room.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Performance indexes for arrival ordering, typed transaction ranges, batch user
 * corrections, and parsed-event card/dashboard lookups.
 */
val MIGRATION_16_17: Migration = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_raw_sms_receivedAtEpochMillis` ON `raw_sms` (`receivedAtEpochMillis`)",
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_financial_transaction_type_occurredAtEpochMillis`
            ON `financial_transaction` (`type`, `occurredAtEpochMillis`)
            """.trimIndent(),
        )
        db.execSQL("DROP INDEX IF EXISTS `index_user_correction_targetRawSmsId_createdAtEpochMillis`")
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_user_correction_targetRawSmsId_createdAtEpochMillis_id`
            ON `user_correction` (`targetRawSmsId`, `createdAtEpochMillis`, `id`)
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_parsed_event_messageFamily` ON `parsed_event` (`messageFamily`)",
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_parsed_event_cardBankId_cardLast4`
            ON `parsed_event` (`cardBankId`, `cardLast4`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_parsed_event_cardSmsChannel_cardLast4`
            ON `parsed_event` (`cardSmsChannel`, `cardLast4`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_parsed_event_sourceAccountBankId_sourceAccountMaskedNumber`
            ON `parsed_event` (`sourceAccountBankId`, `sourceAccountMaskedNumber`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_parsed_event_destinationAccountBankId_destinationAccountMaskedNumber`
            ON `parsed_event` (`destinationAccountBankId`, `destinationAccountMaskedNumber`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_parsed_event_bankId_loanType`
            ON `parsed_event` (`bankId`, `loanType`)
            """.trimIndent(),
        )
    }
}
