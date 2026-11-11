package com.baraa.masroof.architecture

import com.baraa.masroof.data.room.MasroofDatabase
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The v18 baseline is frozen; every later schema is reached by one exported, migrated step. */
class FinancialSchemaCompatibilityTest {
    private val schemas = File("schemas/com.baraa.masroof.data.room.MasroofDatabase")

    @Test
    fun baselineSchemaIsByteFrozen() {
        assertEquals("masroof.db", MasroofDatabase.NAME)
        val digest = MessageDigest.getInstance("SHA-256").digest(File(schemas, "18.json").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("10d89f9fa6411036dd4470592a25de89c987e332512a4918bceab3de40d849a7", digest)
    }

    @Test
    fun exportedVersionsAreContiguousFromTheBaselineToTheCurrentVersion() {
        val exported = schemas.listFiles()!!.map { it.nameWithoutExtension.toInt() }.sorted()
        assertEquals((MasroofDatabase.BASELINE_VERSION..MasroofDatabase.VERSION).toList(), exported)
        val current = File(schemas, "${MasroofDatabase.VERSION}.json").readText()
        assertEquals(MasroofDatabase.IDENTITY_HASH, Regex("\"identityHash\":\\s*\"(\\w+)\"").find(current)!!.groupValues[1])
    }

    @Test
    fun everyStepHasExactlyOneSingleVersionMigration() {
        val steps = MasroofDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals((MasroofDatabase.BASELINE_VERSION until MasroofDatabase.VERSION).map { it to it + 1 }, steps)
    }
}
