package com.baraa.masroof.data.patterns

import com.baraa.masroof.engine.bank.patterns.*
import com.baraa.masroof.testsupport.bundledPatterns
import com.baraa.masroof.testsupport.withPatternDatabase
import com.baraa.masroof.testsupport.writePatternRow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessagePatternStoreTest {
    @Test fun deletionRemovesEveryRevisionOnlyFromItsBankAndSurvivesReloadAndFailedWrites() = withPatternDatabase { db ->
        val first = pattern("STC", "STC Bank")
        val other = pattern("OTHER", "Other Bank")
        val store = MessagePatternStore(db)
        store.saveBank(0, bank(first))
        assertThrows(IllegalArgumentException::class.java) { store.deletePattern(1, "STC", first.id) }
        assertEquals(1L, store.load().generation)
        store.saveBank(1, bank(first.copy(revision = 2, enabled = false)))
        store.saveBank(2, bank(other))
        val before = store.captureRaw()
        assertThrows(IllegalStateException::class.java) { store.deletePattern(2, "STC", first.id) }
        assertThrows(IOException::class.java) {
            MessagePatternStore(db, beforeCommit = { throw IOException("injected") }).deletePattern(3, "STC", first.id)
        }
        assertEquals(before, store.captureRaw())
        val deleted = store.deletePattern(3, "STC", first.id)
        assertEquals(4L, deleted.generation)
        assertEquals(deleted, MessagePatternStore(db).load())
        val stc = deleted.banks.single { it.bankId == "STC" }
        assertTrue(stc.patterns.isEmpty())
        assertEquals(setOf(first.id), stc.deletedPatternIds)
        assertEquals(listOf(other), deleted.banks.single { it.bankId == "OTHER" }.patterns)
        assertThrows(IllegalArgumentException::class.java) { store.saveBank(4, bank(first.copy(revision = 3))) }
        assertEquals(deleted, store.load())
    }

    /** Routing loads the document for every SMS; it is decoded again only when the stored text changes. */
    @Test fun unchangedRowReusesTheDecodedDocumentAndAnyChangeIsSeen() = withPatternDatabase { db ->
        val store = MessagePatternStore(db)
        store.saveBank(0, bank(pattern("STC", "STC Bank")))
        val first = store.load()
        assertSame(first, store.load())
        assertSame(PatternFactInterpreter.of(first), PatternFactInterpreter.of(store.load()))

        store.saveBank(1, bank(pattern("OTHER", "Other Bank")))
        val afterSave = store.load()
        assertNotSame(first, afterSave)
        assertEquals(2L, afterSave.generation)

        MessagePatternStore(db).restoreRaw(MessagePatternConfigurationCodec.encode(first))
        assertEquals(first, store.load())
        assertNotSame(afterSave, store.load())
        // Another database with the same generation but other text is never served from the cache.
        withPatternDatabase { other ->
            MessagePatternStore(other).restoreRaw(MessagePatternConfigurationCodec.encode(afterSave.copy(generation = 1)))
            assertEquals(afterSave.banks, MessagePatternStore(other).load().banks)
        }
        assertEquals(first, store.load())
    }

    @Test fun manySavesKeepOnlyRecentRevisionsSoTheBankNeverFills() = withPatternDatabase { db ->
        val first = pattern("STC", "STC Bank")
        val store = MessagePatternStore(db)
        (1..200).forEach { revision ->
            assertEquals(revision.toLong(), store.saveBank(revision - 1L, bank(first.copy(revision = revision, enabled = revision % 2 == 0))).generation)
        }
        val kept = store.load().banks.single().patterns
        assertEquals(listOf(197, 198, 199, 200), kept.map { it.revision }.sorted())
    }

    @Test fun explicitFactContractsRoundTripAndCannotRewriteAPriorRevision() = withPatternDatabase { db ->
        val contract = PatternFactContract(com.baraa.masroof.engine.bank.MessageFamily.PURCHASE,
            com.baraa.masroof.engine.bank.MoneyDirection.OUTGOING, com.baraa.masroof.finance.value.Currency.SAR)
        val first = pattern("STC", "STC Bank").copy(factContract = contract)
        val store = MessagePatternStore(db)
        store.saveBank(0, bank(first))
        assertEquals(first, MessagePatternStore(db).load().banks.single().patterns.single())
        val raw = requireNotNull(store.captureRaw())
        assertThrows(IllegalArgumentException::class.java) {
            store.saveBank(1, bank(first.copy(factContract = contract.copy(currency = com.baraa.masroof.finance.value.Currency.USD))))
        }
        assertEquals(raw, store.captureRaw())
        assertThrows(IllegalArgumentException::class.java) {
            MessagePatternConfigurationCodec.decode(raw.replace("\"version\":1", "\"version\":99"))
        }
        // Existing v1 definitions stay preview-only, including after they are exported again.
        assertNull(MessagePatternConfigurationCodec.decode(MessagePatternConfigurationCodec.encode(
            MessagePatternConfiguration(banks = listOf(bank(first.copy(factContract = null))))))
            .banks.single().patterns.single().factContract)
    }

    @Test fun versionsBankScopeDisableAndRollbackSurviveFreshStoreInstances() = withPatternDatabase { db ->
        val store = MessagePatternStore(db)
        val first = pattern("STC", "STC Bank")
        val second = pattern("JAZIRA", "AlJazira")
        val a = store.saveBank(0, bank(first))
        val b = MessagePatternStore(db).saveBank(a.generation, bank(second))
        val c = store.saveBank(b.generation, bank(first.copy(revision = 2, enabled = false)))
        val loaded = MessagePatternStore(db).load()
        assertEquals(c.generation, loaded.generation)
        assertEquals(2, loaded.banks.size)
        val stc = loaded.banks.single { it.bankId == "STC" }
        assertEquals(2, stc.patterns.size)
        assertFalse(stc.currentPatterns.single().enabled)
        assertNull(SavedMessagePattern.unique(stc.currentPatterns, "STC", "STC Bank", BODY))
        assertNotNull(SavedMessagePattern.unique(loaded.banks.single { it.bankId == "JAZIRA" }.currentPatterns,
            "JAZIRA", "AlJazira", BODY))
        store.saveBank(loaded.generation, bank(first.copy(revision = 3)))
        val restored = MessagePatternStore(db).load().banks.single { it.bankId == "STC" }
        assertEquals(3, restored.patterns.size)
        assertNotNull(SavedMessagePattern.unique(restored.currentPatterns, "STC", "STC Bank", BODY))
        assertNull(SavedMessagePattern.unique(restored.currentPatterns, "JAZIRA", "STC Bank", BODY))
    }

    @Test fun staleEditsRewrittenVersionsAndFailedWritesKeepThePriorDocument() = withPatternDatabase { db ->
        val first = pattern("STC", "STC Bank")
        val store = MessagePatternStore(db)
        store.saveBank(0, bank(first))
        val prior = store.captureRaw()
        assertThrows(IllegalStateException::class.java) { MessagePatternStore(db).saveBank(0, bank(first)) }
        assertThrows(IllegalArgumentException::class.java) { store.saveBank(1, bank(first.copy(label = "OTHER"))) }
        assertThrows(IOException::class.java) {
            MessagePatternStore(db, beforeCommit = { throw IOException("injected") })
                .saveBank(1, bank(first.copy(revision = 2)))
        }
        assertEquals(prior, store.captureRaw())
        assertEquals(1L, store.load().generation)
    }

    @Test fun corruptAndUnsupportedDocumentsStayVisibleAndCannotBeOverwrittenByAnEdit() = withPatternDatabase { db ->
        val store = MessagePatternStore(db)
        store.saveBank(0, bank(pattern("STC", "STC Bank")))
        val valid = requireNotNull(store.captureRaw())
        val root = Json.parseToJsonElement(valid).jsonObject
        val invalids = listOf("{broken", JsonObject(root + ("schema" to JsonPrimitive(99))).toString(),
            JsonObject(root + ("regex" to JsonPrimitive("execute"))).toString(), " ".repeat(1_048_577))
        for (raw in invalids) {
            db.writePatternRow(raw, generation = 1)
            assertThrows(Exception::class.java) { store.load() }
            assertThrows(Exception::class.java) { store.saveBank(1, bank(pattern("STC", "STC Bank"))) }
            assertThrows(Exception::class.java) { store.restoreRaw(raw) }
            assertEquals(raw, store.captureRaw())
        }
        // A row whose generation column disagrees with its document is not trusted either.
        db.writePatternRow(valid, generation = 7)
        assertThrows(IllegalStateException::class.java) { store.load() }
        store.restoreRaw(valid)
        assertEquals(1, store.load().banks.size)
        store.restoreRaw(null)
        assertEquals(MessagePatternConfiguration(), store.load())
        assertNull(store.captureRaw())
    }

    @Test fun codecRejectsDuplicateAndCrossBankDefinitionsAndExecutableParts() {
        val first = pattern("STC", "STC Bank")
        val valid = MessagePatternConfiguration(1, listOf(bank(first)))
        assertThrows(IllegalArgumentException::class.java) {
            MessagePatternConfigurationCodec.encode(valid.copy(banks = listOf(bank(first), bank(first))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessagePatternConfigurationCodec.encode(valid.copy(banks = listOf(bank(first).copy(bankId = "JAZIRA"))))
        }
        val raw = MessagePatternConfigurationCodec.encode(valid)
        assertThrows(IllegalArgumentException::class.java) {
            MessagePatternConfigurationCodec.decode(raw.replace("\"literal\":", "\"regex\":"))
        }
        assertFalse(raw.contains("10SAR"))
    }

    /** The one-time copy: the row equals the file (text and decoded) for an empty, a seeded and an edited document. */
    @Test fun legacyFileImportsByteForByteThenIsRemoved() = withFile { file ->
        for (raw in listOf(MessagePatternConfigurationCodec.encode(MessagePatternConfiguration()),
            MessagePatternConfigurationCodec.encode(bundledPatterns("BANK_STC")), editedDocument())) {
            file.writeText(raw)
            val bytes = file.readBytes()
            withPatternDatabase { db ->
                val store = MessagePatternStore(db)
                store.importLegacyFile(file)
                assertArrayEquals(bytes, requireNotNull(store.captureRaw()).toByteArray(Charsets.UTF_8))
                assertEquals(MessagePatternConfigurationCodec.decode(raw), store.load())
                assertFalse(file.exists())
                // A rerun finds no file and changes nothing; the generation check carries on from the file's generation.
                store.importLegacyFile(file)
                assertEquals(raw, store.captureRaw())
                val generation = store.load().generation
                assertThrows(IllegalStateException::class.java) { store.saveBank(generation + 1, bank(pattern("NEW", "New Bank"))) }
                assertEquals(generation + 1, store.saveBank(generation, bank(pattern("NEW", "New Bank"))).generation)
            }
        }
    }

    @Test fun legacyFileStaysWhenTheCopyFailsAndTheNextOpenFinishesIt() = withFile { file ->
        val raw = editedDocument()
        file.writeText(raw)
        withPatternDatabase { db ->
            assertThrows(IOException::class.java) {
                MessagePatternStore(db, beforeCommit = { throw IOException("injected") }).importLegacyFile(file)
            }
            assertEquals(raw, file.readText())
            assertNull(MessagePatternStore(db).captureRaw())
            MessagePatternStore(db).importLegacyFile(file)
            assertEquals(raw, MessagePatternStore(db).captureRaw())
            assertFalse(file.exists())
        }
    }

    @Test fun aCrashAfterTheCopyOnlyRemovesTheFileWhenTheRowIsVerified() = withFile { file ->
        val raw = editedDocument()
        val generation = MessagePatternConfigurationCodec.decode(raw).generation
        withPatternDatabase { db ->
            val store = MessagePatternStore(db)
            // Row written, file not yet removed: equal, so the file goes and the row stays.
            db.writePatternRow(raw, generation)
            file.writeText(raw)
            store.importLegacyFile(file)
            assertFalse(file.exists())
            assertEquals(raw, store.captureRaw())
            // A later edit made the row newer than a file left behind: the row wins once it decodes.
            val newer = store.saveBank(generation, bank(pattern("NEW", "New Bank")))
            file.writeText(raw)
            store.importLegacyFile(file)
            assertFalse(file.exists())
            assertEquals(newer, store.load())
            // A row that cannot decode never wins over the file: both stay and the open fails.
            db.writePatternRow("{broken", generation)
            file.writeText(raw)
            assertThrows(Exception::class.java) { store.importLegacyFile(file) }
            assertEquals(raw, file.readText())
            assertEquals("{broken", store.captureRaw())
        }
    }

    @Test fun aCorruptLegacyFileIsKeptAndNeverCopied() = withFile { file ->
        withPatternDatabase { db ->
            val store = MessagePatternStore(db)
            file.writeText("{broken")
            assertThrows(Exception::class.java) { store.importLegacyFile(file) }
            file.writeBytes(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d))
            assertThrows(Exception::class.java) { store.importLegacyFile(file) }
            assertTrue(file.exists())
            assertNull(store.captureRaw())
            file.delete()
            store.importLegacyFile(file)
            assertEquals(MessagePatternConfiguration(), store.load())
        }
    }

    /** Revisions, a deleted id, role profiles and fact contracts, as a used installation holds them. */
    private fun editedDocument(): String = withPatternDatabase { db ->
        val store = MessagePatternStore(db)
        var current = store.installMissingBanks(bundledPatterns("BANK_STC").banks + bundledPatterns("BANK_ALJAZIRA").banks)
        val taught = pattern("OTHER", "Other Bank").copy(id = "taught")
        current = store.saveBank(current.generation, bank(taught))
        current = store.saveBank(current.generation, bank(taught.copy(revision = 2, enabled = false)))
        current = store.deletePattern(current.generation, "OTHER", "taught")
        assertTrue(current.banks.any { bank -> bank.patterns.any { it.roleProfile != null } })
        assertTrue(current.banks.any { it.deletedPatternIds.isNotEmpty() })
        requireNotNull(store.captureRaw())
    }

    private fun withFile(check: (File) -> Unit) {
        val directory = Files.createTempDirectory("masroof-configuration-").toFile()
        try { check(File(directory, "message-patterns/definitions.json").also { it.parentFile.mkdirs() }) } finally { directory.deleteRecursively() }
    }

    private fun bank(pattern: SavedMessagePattern) = BankMessagePatterns(pattern.bankId, listOf(pattern.sender), patterns = listOf(pattern))
    private fun pattern(bank: String, sender: String): SavedMessagePattern = SavedMessagePattern.fromChoices(
        "same-id", bank, sender, "PURCHASE", BODY, listOf(FieldChoice("amount", BODY.indexOf("10SAR"), BODY.length)))

    companion object { private const val BODY = "Synthetic event\nAmount:10SAR" }
}
