package com.baraa.masroof.data.room

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.baraa.masroof.data.room.entity.AccountRegistryEntity
import com.baraa.masroof.data.room.entity.BankRegistryEntity
import com.baraa.masroof.data.room.entity.CardRegistryEntity
import com.baraa.masroof.data.room.entity.CommitmentEntity
import com.baraa.masroof.data.room.entity.CreditFacilityEntity
import com.baraa.masroof.data.room.entity.FinancialTransactionEntity
import com.baraa.masroof.data.room.entity.FinancialTransactionRawSmsLinkEntity
import com.baraa.masroof.data.room.entity.LoanRegistryEntity
import com.baraa.masroof.data.room.entity.ParsedEventEntity
import com.baraa.masroof.data.room.entity.ProcessingRetryEntity
import com.baraa.masroof.data.room.entity.RawSmsEntity
import com.baraa.masroof.data.room.entity.RawSmsProviderAliasEntity
import com.baraa.masroof.data.room.entity.ReviewItemEntity
import com.baraa.masroof.data.room.entity.UserCorrectionEntity
import com.baraa.masroof.data.room.entity.UserDecisionEntity
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

@Entity(tableName = "migration_probe")
data class MigrationProbeEntity(@PrimaryKey val id: Long, val note: String?)

/**
 * Test-only schema one step after [MasroofDatabase.VERSION]: every production entity (add new ones here
 * too) plus [MigrationProbeEntity]. It is never exported, so production never knows this version.
 */
@Database(
    entities = [
        RawSmsEntity::class,
        RawSmsProviderAliasEntity::class,
        ParsedEventEntity::class,
        BankRegistryEntity::class,
        AccountRegistryEntity::class,
        CardRegistryEntity::class,
        CreditFacilityEntity::class,
        LoanRegistryEntity::class,
        FinancialTransactionEntity::class,
        FinancialTransactionRawSmsLinkEntity::class,
        CommitmentEntity::class,
        ReviewItemEntity::class,
        UserCorrectionEntity::class,
        ProcessingRetryEntity::class,
        UserDecisionEntity::class,
        MigrationProbeEntity::class,
    ],
    version = NextVersionTestDatabase.VERSION,
    exportSchema = false,
)
abstract class NextVersionTestDatabase : RoomDatabase() {
    companion object {
        const val VERSION: Int = MasroofDatabase.VERSION + 1

        /** An additive step, as every real one must be. */
        val MIGRATION_TO_NEXT = object : Migration(MasroofDatabase.VERSION, VERSION) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS migration_probe (id INTEGER NOT NULL, note TEXT, PRIMARY KEY(id))")
            }
        }

        /** Room's identity hash for this class, read from a fresh file because the schema is never exported. */
        fun identityHash(context: Context): String {
            val name = "next-version-identity.db"
            context.deleteDatabase(name)
            val db = Room.databaseBuilder(context, NextVersionTestDatabase::class.java, name).build()
            try {
                return db.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table").use {
                    check(it.moveToFirst()); it.getString(0)
                }
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
    }
}

/** The open-path contract, run on the JVM and on a device against the committed v18 fixture. */
object MigrationOpenPathContract {
    /** Tables the real migrations add after v18; each starts empty (no backfill). */
    val TABLES_SINCE_V18: Set<String> = setOf("user_decision")

    /** Tables the test-only next version holds beyond the v18 fixture. */
    val NEXT_VERSION_TABLES: Set<String> = TABLES_SINCE_V18 + "migration_probe"

    /** The v18 fixture upgrades through the production builder to production's own version with every row intact. */
    fun productionUpgradeKeepsEveryRow(context: Context, fixture: () -> InputStream) {
        val name = "migration-production.db"
        upgrade(context, name, fixture, MasroofDatabase::class.java, MasroofDatabase.VERSION, TABLES_SINCE_V18)
        context.deleteDatabase(name)
    }

    /** The v18 fixture upgrades through the production builder to the next version with every row intact. */
    fun upgradeKeepsEveryRow(context: Context, fixture: () -> InputStream) {
        val name = "migration-upgrade.db"
        upgrade(context, name, fixture, NextVersionTestDatabase::class.java, NextVersionTestDatabase.VERSION,
            NEXT_VERSION_TABLES, NextVersionTestDatabase.MIGRATION_TO_NEXT)
        // The production schema is now older than the file: an app downgrade fails closed.
        rejectsUntouched(context, name)
        context.deleteDatabase(name)
    }

    private fun upgrade(
        context: Context,
        name: String,
        fixture: () -> InputStream,
        databaseClass: Class<out RoomDatabase>,
        version: Int,
        addedTables: Set<String>,
        vararg extra: Migration,
    ) {
        val file = copyFixture(context, name, fixture)
        val before = readOnly(file) { rows(it) }
        val db = MasroofDatabase.builder(context, databaseClass, name).addMigrations(*extra).build()
        try {
            db.openHelper.writableDatabase
        } finally {
            db.close()
        }
        val after = readOnly(file) { assertEquals(version, it.version); rows(it) }
        assertTrue("fixture has rows", before.values.sumOf { it.size } > 0)
        assertKeepsEveryRow(before, after, addedTables)
    }

    /** [after] holds every row of [before] unchanged, plus exactly [addedTables], all empty. */
    fun assertKeepsEveryRow(before: Map<String, List<String>>, after: Map<String, List<String>>, addedTables: Set<String>) {
        assertEquals(before, after - addedTables)
        assertEquals(addedTables.associateWith { emptyList<String>() }, after - before.keys)
    }

    fun olderSchemaIsRejectedUntouched(context: Context, fixture: () -> InputStream) =
        editedFixtureIsRejectedUntouched(context, fixture, "PRAGMA user_version = ${MasroofDatabase.BASELINE_VERSION - 1}")

    /**
     * Room checks the identity hash only of a file already at the current version (a migration validates the
     * resulting tables instead), so the hash is changed on the fixture after it is upgraded to production.
     */
    fun unknownIdentityHashIsRejectedUntouched(context: Context, fixture: () -> InputStream) =
        editedFixtureIsRejectedUntouched(context, fixture, "UPDATE room_master_table SET identity_hash = '${"0".repeat(32)}'",
            upgradeFirst = true)

    /** A v18-labelled file whose tables differ from v18 fails the migration's validation and stays untouched. */
    fun unknownBaselineStructureIsRejectedUntouched(context: Context, fixture: () -> InputStream) =
        editedFixtureIsRejectedUntouched(context, fixture, "ALTER TABLE raw_sms ADD COLUMN stray TEXT")

    /** A fixture changed by [edit] is refused and its bytes stay the same. */
    private fun editedFixtureIsRejectedUntouched(
        context: Context,
        fixture: () -> InputStream,
        edit: String,
        upgradeFirst: Boolean = false,
    ) {
        val name = "migration-reject.db"
        val file = copyFixture(context, name, fixture)
        if (upgradeFirst) {
            val db = MasroofDatabase.builder(context, MasroofDatabase::class.java, name).build()
            try {
                db.openHelper.writableDatabase
            } finally {
                db.close()
            }
        }
        // WAL like the live file: a rollback-journal edit would flip the header's journal bytes, and Room flips them back.
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        SQLiteDatabase.openDatabase(file.path, null, flags).use { it.execSQL(edit) }
        rejectsUntouched(context, name)
        context.deleteDatabase(name)
    }

    private fun rejectsUntouched(context: Context, name: String) {
        val file = context.getDatabasePath(name)
        val before = snapshot(file)
        val db = MasroofDatabase.builder(context, MasroofDatabase::class.java, name).build()
        try {
            assertThrows(IllegalStateException::class.java) { db.openHelper.writableDatabase }
        } finally {
            db.close()
        }
        val after = snapshot(file)
        assertArrayEquals(before.first, after.first)
        assertArrayEquals(before.second, after.second)
    }

    private fun snapshot(file: File) =
        file.readBytes() to (File(file.path + "-wal").takeIf { it.isFile }?.readBytes() ?: ByteArray(0))

    fun copyFixture(context: Context, name: String, fixture: () -> InputStream): File {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        fixture().use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    fun <T> readOnly(file: File, block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(block)

    /** Every row of every app table, in rowid order. */
    fun rows(db: SQLiteDatabase): Map<String, List<String>> = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'room_%' " +
            "AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%' ORDER BY name", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        .associateWith { table -> dump(db.rawQuery("SELECT * FROM `$table` ORDER BY rowid", null)) }

    private fun dump(cursor: Cursor): List<String> = cursor.use {
        buildList {
            while (it.moveToNext()) {
                add((0 until it.columnCount).joinToString("|") { column ->
                    when (it.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> "null"
                        Cursor.FIELD_TYPE_BLOB -> it.getBlob(column).contentToString()
                        else -> it.getString(column)
                    }
                })
            }
        }
    }
}
