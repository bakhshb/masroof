package com.baraa.masroof.testsupport

import android.content.Context
import android.content.ContextWrapper
import java.io.File

class TestStorageContext(base: Context, prefix: String = "test-storage") : ContextWrapper(base) {
    private val root = File(base.filesDir, prefix).apply { mkdirs() }
    override fun getApplicationContext(): Context = this
    override fun getDatabasePath(name: String): File = File(name).takeIf { it.isAbsolute } ?: File(root, name)
    override fun deleteDatabase(name: String): Boolean = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun databaseList(): Array<String> = root.listFiles().orEmpty().filter { it.extension == "db" }.map { it.name }.toTypedArray()
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(root.name + ":" + name, mode)
}
