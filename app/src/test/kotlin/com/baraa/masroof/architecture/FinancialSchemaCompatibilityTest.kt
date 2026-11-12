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

/**
 * The released v18 baseline is frozen, and the current schema is reached from it by one exported, migrated step (owner,
 * 2026-11-12: v19 to v21 never shipped, so they are not kept).
 */
class FinancialSchemaCompatibilityTest {
    private val schemas = File("../data/schemas/com.baraa.masroof.data.room.MasroofDatabase")

    @Test
    fun releasedSchemasAreByteFrozen() {
        assertEquals("masroof.db", MasroofDatabase.NAME)
        val digest = MessageDigest.getInstance("SHA-256").digest(File(schemas, "18.json").readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("10d89f9fa6411036dd4470592a25de89c987e332512a4918bceab3de40d849a7", digest)
    }

    @Test
    fun onlyTheBaselineAndTheCurrentVersionAreExported() {
        val exported = schemas.listFiles()!!.map { it.nameWithoutExtension.toInt() }.sorted()
        assertEquals(listOf(18, 22), exported)
        assertEquals("5349c1b666aed8239c225512f4498078", MasroofDatabase.IDENTITY_HASH)
        assertEquals(MasroofDatabase.IDENTITY_HASH, exportedHash(MasroofDatabase.VERSION))
    }

    @Test
    fun knownIdentityHashesMatchEveryExportedSchema() {
        val exported = schemas.listFiles()!!.map { it.nameWithoutExtension.toInt() }.associateWith(::exportedHash)
        assertEquals(exported, MasroofDatabase.IDENTITY_HASHES)
    }

    /**
     * v22 keeps every v18 table exactly and adds `user_decision` (plans 2.1, 5.2) and `pattern_configuration` (plan 5.1),
     * whose createSql the migration copies.
     */
    @Test
    fun currentVersionOnlyAddsTheDecisionAndPatternTables() {
        fun database(version: Int) = Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject.getValue("database").jsonObject
        // The table and index SQL of each entity; the two files come from Room versions that print empty fields differently.
        fun sql(version: Int) = database(version).getValue("entities").jsonArray.associate { entity ->
            val table = entity.jsonObject
            table.getValue("tableName").jsonPrimitive.content to listOf(table.getValue("createSql").jsonPrimitive.content) +
                table["indices"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("createSql").jsonPrimitive.content }
        }
        val (v18, current) = sql(18) to sql(MasroofDatabase.VERSION)
        assertEquals(setOf("user_decision", "pattern_configuration"), current.keys - v18.keys)
        assertEquals(v18, current - setOf("user_decision", "pattern_configuration"))
    }

    private fun exportedHash(version: Int): String =
        Regex("\"identityHash\":\\s*\"(\\w+)\"").find(File(schemas, "$version.json").readText())!!.groupValues[1]

    @Test
    fun theOnlyMigrationRunsFromTheBaselineToTheCurrentVersion() {
        val steps = MasroofDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals(listOf(MasroofDatabase.BASELINE_VERSION to MasroofDatabase.VERSION), steps)
    }
}
