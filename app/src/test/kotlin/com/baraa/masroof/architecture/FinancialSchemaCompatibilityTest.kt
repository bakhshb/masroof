package com.baraa.masroof.architecture

import com.baraa.masroof.data.room.MasroofDatabase
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Released schemas (v18 baseline, v19, v20) are frozen; every later schema is reached by one exported, migrated step. */
class FinancialSchemaCompatibilityTest {
    private val schemas = File("../data/schemas/com.baraa.masroof.data.room.MasroofDatabase")

    @Test
    fun releasedSchemasAreByteFrozen() {
        assertEquals("masroof.db", MasroofDatabase.NAME)
        val digests = listOf(18, 19, 20).associateWith { version ->
            MessageDigest.getInstance("SHA-256").digest(File(schemas, "$version.json").readBytes()).joinToString("") { "%02x".format(it) }
        }
        assertEquals(mapOf(
            18 to "10d89f9fa6411036dd4470592a25de89c987e332512a4918bceab3de40d849a7",
            19 to "b3dfa7a4d02121d1bfecf711bb03cfafaa5100b10ea12e8b0d4f180ce8075fc2",
            20 to "a4732eee512c9ee6757e992c7b6ed1f61a5b5c19083b5fc0c2a61b462dd5bd56",
        ), digests)
    }

    @Test
    fun exportedVersionsAreContiguousFromTheBaselineToTheCurrentVersion() {
        val exported = schemas.listFiles()!!.map { it.nameWithoutExtension.toInt() }.sorted()
        assertEquals((MasroofDatabase.BASELINE_VERSION..MasroofDatabase.VERSION).toList(), exported)
        assertEquals(MasroofDatabase.IDENTITY_HASH, exportedHash(MasroofDatabase.VERSION))
    }

    @Test
    fun knownIdentityHashesMatchEveryExportedSchema() {
        val exported = schemas.listFiles()!!.map { it.nameWithoutExtension.toInt() }.associateWith(::exportedHash)
        assertEquals(exported, MasroofDatabase.IDENTITY_HASHES)
    }

    /** v21 (plan 5.1) adds `pattern_configuration` and leaves every v20 table exactly as it was. */
    @Test
    fun v21OnlyAddsThePatternTable() {
        fun database(version: Int) = Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject.getValue("database").jsonObject
        val (v20, v21) = database(20) to database(21)
        val (added, kept) = v21.getValue("entities").jsonArray.partition { it.jsonObject.getValue("tableName").jsonPrimitive.content == "pattern_configuration" }
        assertEquals(1, added.size)
        assertEquals(v20.getValue("entities"), JsonArray(kept))
        assertEquals(v20 - setOf("version", "identityHash", "entities", "setupQueries"), v21 - setOf("version", "identityHash", "entities", "setupQueries"))
    }

    private fun exportedHash(version: Int): String =
        Regex("\"identityHash\":\\s*\"(\\w+)\"").find(File(schemas, "$version.json").readText())!!.groupValues[1]

    @Test
    fun everyStepHasExactlyOneSingleVersionMigration() {
        val steps = MasroofDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals((MasroofDatabase.BASELINE_VERSION until MasroofDatabase.VERSION).map { it to it + 1 }, steps)
    }
}
