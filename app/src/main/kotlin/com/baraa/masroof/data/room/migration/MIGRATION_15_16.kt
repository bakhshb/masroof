package com.baraa.masroof.data.room.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Stores the zone used to turn an SMS wall clock into [financial_transaction.occurredAt].
 * Existing instants stay as they are; the column is null until the next assembly.
 */
val MIGRATION_15_16: Migration = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE financial_transaction ADD COLUMN occurredAtZone TEXT")
    }
}
