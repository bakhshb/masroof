package com.baraa.masroof.application.patterns

import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.engine.bank.patterns.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StcBundledPatternsTest {
    @Test fun sarieTransferMatchesAnEmptyAccountLineWithoutInventingAnAccountReference() {
        val bank = MessagePatternConfigurationCodec.decode(File("src/main/assets/bank-patterns/BANK_STC.patterns.json").readText()).banks.single()
        val body = "Inward transfer (SARIE)\n100.00 SAR\nFrom TEST PERSON\nFrom BJAZ\nAccount \n02-09-2026 08:01\nRef. No. *8PJ7"
        for (accountLine in listOf("Account ", "Account", "Account   ")) {
            val text = body.replace("Account ", accountLine)
            val match = requireNotNull(SavedMessagePattern.unique(bank.currentPatterns, bank.bankId, "STC Bank", text))
            assertEquals("stc-sarie-in", match.first.id)
            assertEquals(mapOf("amount" to "100.00 SAR", "counterparty" to "TEST PERSON", "counterparty_bank" to "BJAZ",
                "occurred_at" to "02-09-2026 08:01", "reference" to "*8PJ7"), match.second.fields.mapValues { it.value.raw })
            match.second.fields.values.forEach { assertEquals(it.raw, text.substring(it.start, it.end)) }
            assertFalse(match.second.financialPostingAllowed)
        }
        for (invalid in listOf(body.replace("Account \n", ""), body.replace("100.00 SAR", ""),
            body.replace("TEST PERSON", ""), body.replace("BJAZ", ""), body.replace("02-09-2026 08:01", ""),
            body.replace("*8PJ7", ""), body + "\nCancelled")) {
            assertNull(SavedMessagePattern.unique(bank.currentPatterns, bank.bankId, "STC Bank", invalid))
        }
    }

    private val rows get() = Json.parseToJsonElement(File("src/test/resources/testdata/patterns/stc-seed-v1.json").readText())
        .jsonObject.getValue("rows").jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun generate(): BankMessagePatterns = BankMessagePatterns("BANK_STC", listOf("STC Bank"), "STC Bank",
        rows.filter { it.getValue("teach").jsonPrimitive.boolean }.map { row ->
            val body = row.text("body")
            val choices = row.getValue("fields").jsonObject.map { (field, value) ->
                val selected = value.jsonPrimitive.content
                val start = body.indexOf(selected)
                require(start >= 0 && start == body.lastIndexOf(selected))
                FieldChoice(field, start, start + selected.length)
            }
            SavedMessagePattern.fromChoices(row.text("id"), "BANK_STC", "STC Bank", row.text("operation"), body, choices)
        })

    @Test fun bundledDefinitionsAreGeneratedFromBindingsAndMatchIndependentMessages() {
        val generated = MessagePatternConfigurationCodec.encode(MessagePatternConfiguration(banks = listOf(generate())))
        File("build/reports/pattern-seed/stc.generated.json").apply { requireNotNull(parentFile).mkdirs(); writeText(generated + "\n") }
        val bundled = File("src/main/assets/bank-patterns/BANK_STC.patterns.json")
        assertTrue("Generated artifact must be reviewed and bundled", bundled.exists())
        assertEquals(generated, bundled.readText().trim())
        val bank = MessagePatternConfigurationCodec.decode(bundled.readText()).banks.single()
        assertEquals(8, bank.currentPatterns.size)
        val literals = bank.patterns.flatMap { it.parts.filterIsInstance<StaticText>() }.joinToString { it.value }
        rows.filter { it.getValue("teach").jsonPrimitive.boolean }.forEach { row ->
            row.getValue("fields").jsonObject.values.forEach { assertFalse(literals.contains(it.jsonPrimitive.content)) }
        }
        assertEquals(11, rows.count { !it.getValue("teach").jsonPrimitive.boolean })
        rows.forEach { row ->
            val match = SavedMessagePattern.unique(bank.currentPatterns, "BANK_STC", "STC Bank", row.text("body"))!!
            assertEquals(row.text("id"), match.first.id)
            assertEquals(row.text("operation"), match.second.label)
            assertEquals(row.getValue("fields").jsonObject.mapValues { it.value.jsonPrimitive.content }, match.second.fields.mapValues { it.value.raw })
            assertFalse(match.second.financialPostingAllowed)
            assertNull(SavedMessagePattern.unique(bank.currentPatterns, "BANK_ALJAZIRA", "STC Bank", row.text("body")))
            assertNull(SavedMessagePattern.unique(bank.currentPatterns, "BANK_STC", "Other Bank", row.text("body")))
            assertNull(SavedMessagePattern.unique(bank.currentPatterns, "BANK_STC", "STC Bank", row.text("body") + "\nCancelled"))
        }
        val local = rows.single { it.text("id") == "stc-local-in" }.text("body")
        val synthetic = local.replace("50SR", "725.10SR").replace("BJAZ", "OTHER BANK")
        val result = SavedMessagePattern.unique(bank.currentPatterns, "BANK_STC", "STC Bank", synthetic)!!.second
        assertEquals("725.10SR", result.fields.getValue("amount").raw)
        assertEquals("OTHER BANK", result.fields.getValue("counterparty_bank").raw)
    }

    @Test fun defaultsInstallOnceWithoutOverwritingUserRevisionsOrSenderIdentities() = runBlocking {
        val directory = Files.createTempDirectory("stc-defaults-").toFile()
        try {
            val store = MessagePatternStore(directory.resolve("patterns.json"))
            val workflow = MessagePatternsWorkflow(store, DatabaseAccessGate(), bundledBanks = listOf(generate()))
            val seeded = workflow.load()
            assertEquals(1L, seeded.generation)
            assertEquals(8, seeded.banks.single().patterns.size)
            assertEquals(seeded, workflow.load())
            workflow.setEnabled(1, "BANK_STC", "stc-qattah", false)
            assertFalse(workflow.load().banks.single().currentPatterns.single { it.id == "stc-qattah" }.enabled)
            store.restoreRaw(null)
            store.saveBank(0, BankMessagePatterns("USER_STC", listOf("STC Bank"), "My bank"))
            assertEquals(listOf("USER_STC"), workflow.load().banks.map { it.bankId })
            assertEquals(1L, store.load().generation)
        } finally { directory.deleteRecursively() }
        Unit
    }
}
