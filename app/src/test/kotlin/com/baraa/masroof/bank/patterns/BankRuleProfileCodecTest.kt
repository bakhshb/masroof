package com.baraa.masroof.bank.patterns

import com.baraa.masroof.data.patterns.BankRuleProfileCodec
import com.baraa.masroof.engine.bank.interpretation.RuleInterpreter
import com.baraa.masroof.engine.bank.rules.AlJaziraRuleProfile
import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BankRuleProfileCodecTest {
    @Test fun bundledProfileKeepsEveryLiteralRoleAndRefreshIdentity() {
        val original = AlJaziraRuleProfile.current()
        val encoded = BankRuleProfileCodec.encode(original)
        File("build/reports/pattern-parity/aljazira-rules.json").apply { parentFile.mkdirs(); writeText(encoded) }
        assertEquals(original, BankRuleProfileCodec.decode(encoded))
        val bundled = File("src/main/assets/bank-patterns/BANK_ALJAZIRA.patterns.json")
        assertTrue("Bundled profile missing; original profile exported under build/reports/pattern-parity", bundled.isFile)
        val document = MessagePatternConfigurationCodec.decode(bundled.readText())
        val bank = document.banks.single()
        val roundTrip = MessagePatternConfigurationCodec.decode(MessagePatternConfigurationCodec.encode(document))
        assertEquals(bank.copy(patterns = bank.currentPatterns), roundTrip.banks.single())
        val loaded = original.copy(rules = bank.patterns.map { it.roleProfile!!.rules.single() })
        assertEquals(RuleInterpreter(original).version, RuleInterpreter(loaded).version)
    }

    @Test fun closedSchemaRejectsUnknownCapabilitiesVersionsAndOversizedInput() {
        val valid = Json.parseToJsonElement(BankRuleProfileCodec.encode(AlJaziraRuleProfile.current())).jsonObject
        listOf(JsonObject(valid + ("schema" to JsonPrimitive(99))),
            JsonObject(valid + ("regex" to JsonPrimitive("execute"))),
            JsonObject(valid + ("rules" to buildJsonArray {
                add(JsonObject(valid.getValue("rules").jsonArray.first().jsonObject + ("operator" to JsonPrimitive("REGEX"))))
            }))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { BankRuleProfileCodec.decode(invalid.toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { BankRuleProfileCodec.decode(" ".repeat(262_145)) }
    }
}
