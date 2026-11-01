package com.baraa.masroof.bank.patterns

import com.baraa.masroof.bank.MessagePatternSmsAdapter
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.integration.bank.NewEngineFixtureTest
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.engine.bank.MoneyDirection
import com.baraa.masroof.engine.bank.patterns.PatternFactContract
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.testsupport.engineFixtureAdapter
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Every existing AlJazira/STC fixture body, parsed by the bundled production definitions, must keep the
 * exact outcome frozen before the additional banks were adopted. Shared-engine changes may only widen
 * formats that no existing message uses. Regenerate with MASROOF_UPDATE_PARSE_SNAPSHOT=1 only for an
 * intentional, reviewed change.
 */
class ExistingBankParseSnapshotTest {
    private val snapshot = File("src/test/resources/testdata/pattern-parity/existing-bank-parse-snapshot.txt")

    private val stc = run {
        val configuration = MessagePatternConfigurationCodec.decode(File("src/main/assets/bank-patterns/BANK_STC.patterns.json").readText())
        val bank = configuration.banks.single()
        val approved = bank.copy(patterns = bank.patterns.map {
            if (it.id == "stc-qattah") it.copy(factContract = PatternFactContract(com.baraa.masroof.engine.bank.MessageFamily.TRANSFER_OUT, MoneyDirection.OUTGOING)) else it
        })
        MessagePatternSmsAdapter(approved, PatternFactInterpreter(configuration.copy(banks = listOf(approved))))
    }

    private fun bodies(): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        fun walk(file: String, element: JsonElement, path: String) {
            when (element) {
                is JsonObject -> {
                    (element["body"] as? JsonPrimitive)?.takeIf { it.isString }?.let { body ->
                        out += Triple("$file$path#${(element["id"] as? JsonPrimitive)?.content.orEmpty()}",
                            (element["sender"] as? JsonPrimitive)?.content ?: if (file.startsWith("patterns/stc-")) "STC Bank" else "AlJazira", body.content)
                    }
                    element.forEach { (key, value) -> walk(file, value, "$path.$key") }
                }
                is JsonArray -> element.forEachIndexed { index, value -> walk(file, value, "$path[$index]") }
                else -> Unit
            }
        }
        File("src/test/resources/testdata").walkTopDown()
            .filter { it.isFile && it.extension == "json" && it.parentFile?.name != "multibank" }
            .sortedBy { it.invariantSeparatorsPath }
            .forEach { walk(it.relativeTo(File("src/test/resources/testdata")).invariantSeparatorsPath, Json.parseToJsonElement(it.readText()), "") }
        return out
    }

    @Test fun existingFixturesKeepFrozenParseOutcome() {
        val aljazira = engineFixtureAdapter()
        val actual = bodies().joinToString("\n", postfix = "\n") { (id, sender, body) ->
            val adapter = if (sender == "STC Bank") stc else aljazira
            val outcome = runCatching { adapter.parse(SmsParseInput(id, sender, body, Instant.parse("2026-08-10T00:00:00Z"))).toString() }
                .getOrElse { "THROWS ${it.javaClass.simpleName}" }
                // Engine version labels are provenance, not parse facts.
                .replace(Regex("bank-rules-independent-v\\d+"), "bank-rules-independent-vN")
            "$id\t$outcome"
        }
        if (System.getenv("MASROOF_UPDATE_PARSE_SNAPSHOT") == "1") snapshot.writeText(actual)
        assertEquals(snapshot.readText(), actual)
    }

    @Test fun defaultFixtureAdapterUsesBundledProductionDefinitions() {
        val result = engineFixtureAdapter().parse(SmsParseInput("default-fixture", "AlJazira",
            "Purchase\nAmount:10 SAR\nFrom account:4321\nMerchant:SHOP\nOn:2026-10-15 12:30",
            Instant.parse("2026-10-20T12:00:00Z")))
        assertTrue(result is ParseResult.Success)
        assertTrue("engine:message-pattern-roles-v1" in (result as ParseResult.Success).event.confidence.reasons)
    }

    @Test fun everyEngineFixtureIsDetectedAsAlJazira() {
        val adapter = bundledAlJaziraPatternAdapter()
        val fixtures = NewEngineFixtureTest.cases().map { Json.parseToJsonElement(it.single().readText()).jsonObject }
        assertTrue(fixtures.isNotEmpty())
        for (row in fixtures) {
            val detection = adapter.detect(row.getValue("sender").jsonPrimitive.content, row.getValue("body").jsonPrimitive.content)
            assertEquals(row.getValue("id").jsonPrimitive.content, Bank.BANK_ALJAZIRA, (detection as? BankDetectionResult.Detected)?.bank)
        }
    }
}
