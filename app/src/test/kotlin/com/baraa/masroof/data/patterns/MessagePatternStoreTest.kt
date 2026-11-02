package com.baraa.masroof.data.patterns

import com.baraa.masroof.engine.bank.patterns.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class MessagePatternStoreTest {
    @Test fun deletionRemovesEveryRevisionOnlyFromItsBankAndSurvivesReloadAndFailedWrites() = withFile { file ->
        val first = pattern("STC", "STC Bank")
        val other = pattern("OTHER", "Other Bank")
        val store = MessagePatternStore(file)
        store.saveBank(0, bank(first))
        assertThrows(IllegalArgumentException::class.java) { store.deletePattern(1, "STC", first.id) }
        assertEquals(1L, store.load().generation)
        store.saveBank(1, bank(first.copy(revision = 2, enabled = false)))
        store.saveBank(2, bank(other))
        val before = file.readBytes()
        assertThrows(IllegalStateException::class.java) { store.deletePattern(2, "STC", first.id) }
        assertThrows(IOException::class.java) {
            MessagePatternStore(file, beforeReplace = { throw IOException("injected") }).deletePattern(3, "STC", first.id)
        }
        assertArrayEquals(before, file.readBytes())
        val deleted = store.deletePattern(3, "STC", first.id)
        assertEquals(4L, deleted.generation)
        assertEquals(deleted, MessagePatternStore(file).load())
        val stc = deleted.banks.single { it.bankId == "STC" }
        assertTrue(stc.patterns.isEmpty())
        assertEquals(setOf(first.id), stc.deletedPatternIds)
        assertEquals(listOf(other), deleted.banks.single { it.bankId == "OTHER" }.patterns)
        assertThrows(IllegalArgumentException::class.java) { store.saveBank(4, bank(first.copy(revision = 3))) }
        assertEquals(deleted, store.load())
        assertEquals(listOf(file.name), file.parentFile!!.listFiles()!!.map { it.name })
    }

    /** Routing loads the document for every SMS; it is decoded again only when the file's text changes. */
    @Test fun unchangedFileReusesTheDecodedDocumentAndAnyChangeIsSeen() = withFile { file ->
        val store = MessagePatternStore(file)
        store.saveBank(0, bank(pattern("STC", "STC Bank")))
        val first = store.load()
        assertSame(first, store.load())
        assertSame(PatternFactInterpreter.of(first), PatternFactInterpreter.of(store.load()))

        store.saveBank(1, bank(pattern("OTHER", "Other Bank")))
        val afterSave = store.load()
        assertNotSame(first, afterSave)
        assertEquals(2L, afterSave.generation)

        MessagePatternStore(file).restoreRaw(MessagePatternConfigurationCodec.encode(first))
        assertEquals(first, store.load())
        assertNotSame(afterSave, store.load())
    }

    @Test fun manySavesKeepOnlyRecentRevisionsSoTheBankNeverFills() = withFile { file ->
        val first = pattern("STC", "STC Bank")
        val store = MessagePatternStore(file)
        (1..200).forEach { revision ->
            store.saveBank(revision - 1L, bank(first.copy(revision = revision, enabled = revision % 2 == 0)))
        }
        val kept = store.load().banks.single().patterns
        assertEquals(listOf(197, 198, 199, 200), kept.map { it.revision }.sorted())
    }

    @Test fun explicitFactContractsRoundTripAndCannotRewriteAPriorRevision() = withFile { file ->
        val contract = PatternFactContract(com.baraa.masroof.engine.bank.MessageFamily.PURCHASE,
            com.baraa.masroof.engine.bank.MoneyDirection.OUTGOING, com.baraa.masroof.finance.value.Currency.SAR)
        val first = pattern("STC", "STC Bank").copy(factContract = contract)
        val store = MessagePatternStore(file)
        store.saveBank(0, bank(first))
        assertEquals(first, MessagePatternStore(file).load().banks.single().patterns.single())
        val raw = file.readText()
        assertThrows(IllegalArgumentException::class.java) {
            store.saveBank(1, bank(first.copy(factContract = contract.copy(currency = com.baraa.masroof.finance.value.Currency.USD))))
        }
        assertEquals(raw, file.readText())
        assertThrows(IllegalArgumentException::class.java) {
            MessagePatternConfigurationCodec.decode(raw.replace("\"version\":1", "\"version\":99"))
        }
        // Existing v1 definitions stay preview-only, including after they are exported again.
        assertNull(MessagePatternConfigurationCodec.decode(MessagePatternConfigurationCodec.encode(
            MessagePatternConfiguration(banks = listOf(bank(first.copy(factContract = null))))))
            .banks.single().patterns.single().factContract)
    }

    @Test fun versionsBankScopeDisableAndRollbackSurviveFreshStoreInstances() = withFile { file ->
        val store = MessagePatternStore(file)
        val first = pattern("STC", "STC Bank")
        val second = pattern("JAZIRA", "AlJazira")
        val a = store.saveBank(0, bank(first))
        val b = MessagePatternStore(file).saveBank(a.generation, bank(second))
        val c = store.saveBank(b.generation, bank(first.copy(revision = 2, enabled = false)))
        val loaded = MessagePatternStore(file).load()
        assertEquals(c.generation, loaded.generation)
        assertEquals(2, loaded.banks.size)
        val stc = loaded.banks.single { it.bankId == "STC" }
        assertEquals(2, stc.patterns.size)
        assertFalse(stc.currentPatterns.single().enabled)
        assertNull(SavedMessagePattern.unique(stc.currentPatterns, "STC", "STC Bank", BODY))
        assertNotNull(SavedMessagePattern.unique(loaded.banks.single { it.bankId == "JAZIRA" }.currentPatterns,
            "JAZIRA", "AlJazira", BODY))
        store.saveBank(loaded.generation, bank(first.copy(revision = 3)))
        val restored = MessagePatternStore(file).load().banks.single { it.bankId == "STC" }
        assertEquals(3, restored.patterns.size)
        assertNotNull(SavedMessagePattern.unique(restored.currentPatterns, "STC", "STC Bank", BODY))
        assertNull(SavedMessagePattern.unique(restored.currentPatterns, "JAZIRA", "STC Bank", BODY))
    }

    @Test fun staleEditsRewrittenVersionsAndFailedReplacementsKeepPriorBytes() = withFile { file ->
        val first = pattern("STC", "STC Bank")
        val store = MessagePatternStore(file)
        store.saveBank(0, bank(first))
        val prior = file.readBytes()
        assertThrows(IllegalStateException::class.java) { MessagePatternStore(file).saveBank(0, bank(first)) }
        assertThrows(IllegalArgumentException::class.java) { store.saveBank(1, bank(first.copy(label = "OTHER"))) }
        assertThrows(IOException::class.java) {
            MessagePatternStore(file, beforeReplace = { throw IOException("injected") })
                .saveBank(1, bank(first.copy(revision = 2)))
        }
        assertArrayEquals(prior, file.readBytes())
        assertEquals(listOf(file.name), file.parentFile!!.listFiles()!!.map { it.name })
    }

    @Test fun corruptAndUnsupportedDocumentsStayVisibleAndCannotBeOverwrittenByAnEdit() = withFile { file ->
        val store = MessagePatternStore(file)
        store.saveBank(0, bank(pattern("STC", "STC Bank")))
        val valid = requireNotNull(store.captureRaw())
        val root = Json.parseToJsonElement(valid).jsonObject
        val invalids = listOf("{broken", JsonObject(root + ("schema" to JsonPrimitive(99))).toString(),
            JsonObject(root + ("regex" to JsonPrimitive("execute"))).toString(), " ".repeat(1_048_577))
        for (raw in invalids) {
            file.writeText(raw)
            assertThrows(Exception::class.java) { store.load() }
            assertThrows(Exception::class.java) { store.saveBank(0, bank(pattern("STC", "STC Bank"))) }
            assertEquals(raw, file.readText())
        }
        store.restoreRaw(valid)
        assertEquals(1, store.load().banks.size)
        store.restoreRaw(null)
        assertEquals(0, store.load().banks.size)
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

    private fun withFile(check: (File) -> Unit) {
        val directory = Files.createTempDirectory("masroof-configuration-").toFile()
        try { check(File(directory, "patterns.json")) } finally { directory.deleteRecursively() }
    }

    private fun bank(pattern: SavedMessagePattern) = BankMessagePatterns(pattern.bankId, listOf(pattern.sender), patterns = listOf(pattern))
    private fun pattern(bank: String, sender: String): SavedMessagePattern = SavedMessagePattern.fromChoices(
        "same-id", bank, sender, "PURCHASE", BODY, listOf(FieldChoice("amount", BODY.indexOf("10SAR"), BODY.length)))

    companion object { private const val BODY = "Synthetic event\nAmount:10SAR" }
}
