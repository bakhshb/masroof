package com.baraa.masroof.architecture

import com.baraa.masroof.data.room.MasroofDatabase
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The schema restarted at version 1 (owner, 2026-11-13): one exported file, whose hash the code names. */
class FinancialSchemaCompatibilityTest {
    private val schemas = File("../data/schemas/com.baraa.masroof.data.room.MasroofDatabase")

    @Test
    fun onlyTheCurrentVersionIsExportedAndItsHashIsTheOneTheCodeNames() {
        assertEquals("masroof.db", MasroofDatabase.NAME)
        assertEquals(listOf(MasroofDatabase.VERSION), schemas.listFiles()!!.map { it.nameWithoutExtension.toInt() })
        assertEquals(MasroofDatabase.IDENTITY_HASH, exportedHash(MasroofDatabase.VERSION))
    }

    /** Every later step is one version at a time and ends at the current version. */
    @Test
    fun migrationsFormOneChainFromVersionOne() {
        val steps = MasroofDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals((1 until MasroofDatabase.VERSION).map { it to it + 1 }, steps)
    }

    private fun exportedHash(version: Int): String =
        Regex("\"identityHash\":\\s*\"(\\w+)\"").find(File(schemas, "$version.json").readText())!!.groupValues[1]
}
