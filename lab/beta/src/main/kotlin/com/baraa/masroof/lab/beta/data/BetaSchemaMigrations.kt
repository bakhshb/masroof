package com.baraa.masroof.lab.beta.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Only the immediately preceding lab schema is supported; production is never opened. */
object BetaSchemaMigrations {
    val COMMITMENT_FACTS: Migration = object: Migration(14,15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE beta_commitment ADD COLUMN recurrence TEXT DEFAULT 'MONTHLY'")
            db.execSQL("ALTER TABLE beta_commitment ADD COLUMN transactionDate TEXT")
            db.execSQL("ALTER TABLE beta_commitment ADD COLUMN dueDate TEXT")
        }
    }
    fun readable(version: Int): Boolean = version==14 || version==BetaDatabase.VERSION
}
