package com.baraa.masroof.testsupport

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.room.MasroofDatabase
import java.io.File

/** The production schema in memory, for pattern-store tests under Robolectric; closed after [block]. */
inline fun <T> withPatternDatabase(block: (MasroofDatabase) -> T): T {
    val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MasroofDatabase::class.java).build()
    return try { block(database) } finally { database.close() }
}

/** Stores [raw] as the pattern row without validation, as a damaged or foreign database would hold it. */
fun MasroofDatabase.writePatternRow(raw: String, generation: Long = 0) = openHelper.writableDatabase.execSQL(
    "INSERT OR REPLACE INTO pattern_configuration (id, generation, document) VALUES (1, ?, ?)", arrayOf<Any>(generation, raw))

/** The pattern document in the database file [file], read without Room (which would migrate or seed); null when none. */
fun storedPatterns(file: File): String? =
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
        db.rawQuery("SELECT document FROM pattern_configuration", null).use { if (it.moveToFirst()) it.getString(0) else null }
    }
