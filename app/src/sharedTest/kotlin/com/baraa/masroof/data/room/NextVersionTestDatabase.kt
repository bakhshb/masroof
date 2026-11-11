package com.baraa.masroof.data.room

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
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
    }
}

/** The open-path contract, run on the JVM and on a device against the committed v18 fixture. */
object MigrationOpenPathContract {
    /** The v18 fixture upgrades through the production builder to the next version with every row intact. */
    fun upgradeKeepsEveryRow(context: Context, fixture: () -> InputStream) {
        val name = "migration-upgrade.db"
        val file = copyFixture(context, name, fixture)
        val before = readOnly(file) { rows(it) }
        val db = MasroofDatabase.builder(context, NextVersionTestDatabase::class.java, name)
            .addMigrations(NextVersionTestDatabase.MIGRATION_TO_NEXT).build()
        try {
            db.openHelper.writableDatabase
        } finally {
            db.close()
        }
        val after = readOnly(file) { assertEquals(NextVersionTestDatabase.VERSION, it.version); rows(it) }
        assertTrue("fixture has rows", before.values.sumOf { it.size } > 0)
        assertEquals(before, after - "migration_probe")
        assertEquals(emptyList<String>(), after["migration_probe"])
        // The production schema is now older than the file: an app downgrade fails closed.
        rejectsUntouched(context, name)
        context.deleteDatabase(name)
    }

    fun olderSchemaIsRejectedUntouched(context: Context, fixture: () -> InputStream) =
        editedFixtureIsRejectedUntouched(context, fixture, "PRAGMA user_version = ${MasroofDatabase.BASELINE_VERSION - 1}")

    fun unknownIdentityHashIsRejectedUntouched(context: Context, fixture: () -> InputStream) =
        editedFixtureIsRejectedUntouched(context, fixture, "UPDATE room_master_table SET identity_hash = '${"0".repeat(32)}'")

    /** A fixture changed by [edit] is refused and its bytes stay the same. */
    private fun editedFixtureIsRejectedUntouched(context: Context, fixture: () -> InputStream, edit: String) {
        val name = "migration-reject.db"
        val file = copyFixture(context, name, fixture)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(edit) }
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

    private fun copyFixture(context: Context, name: String, fixture: () -> InputStream): File {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        fixture().use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun <T> readOnly(file: File, block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(block)

    private fun rows(db: SQLiteDatabase): Map<String, List<String>> = db.rawQuery(
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
