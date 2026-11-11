package com.baraa.masroof.application.backup

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.baraa.masroof.data.room.MasroofDatabase

/**
 * The schemas a restore accepts and the database class it migrates them to. Production is [MasroofDatabase];
 * tests pass a later test-only class with its step, so production never names a future schema.
 */
class BackupSchema(
    val databaseClass: Class<out RoomDatabase>,
    /** Every accepted (version, identity hash) pair; the highest version is the restore target. */
    val identityHashes: Map<Int, String>,
    /** Steps beyond [MasroofDatabase.MIGRATIONS], which the builder always adds. */
    val extraMigrations: List<Migration> = emptyList(),
) {
    val version: Int = identityHashes.keys.max()

    companion object {
        val PRODUCTION = BackupSchema(MasroofDatabase::class.java, MasroofDatabase.IDENTITY_HASHES)
    }
}
