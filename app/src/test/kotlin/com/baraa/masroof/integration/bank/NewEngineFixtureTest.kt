package com.baraa.masroof.integration.bank

import com.baraa.masroof.testsupport.detailsOrNull
import com.baraa.masroof.testsupport.eventOrNull
import com.baraa.masroof.engine.bank.interpretation.RuleInterpreter
import com.baraa.masroof.engine.bank.rules.AlJaziraRuleProfile
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.testsupport.InterpretationTestAdapter
import com.baraa.masroof.parsing.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.time.Instant

@RunWith(Parameterized::class)
class NewEngineFixtureTest(private val file: File) {
    @Test fun matchesApprovedFixtureAndProductionFacts() {
        val fixture = Json.parseToJsonElement(file.readText()).jsonObject
        val expected = fixture.getValue("expected").jsonObject
        val input = SmsParseInput(fixture.getValue("id").jsonPrimitive.content,fixture.getValue("sender").jsonPrimitive.content,
            fixture.getValue("body").jsonPrimitive.content,Instant.parse("2026-10-20T12:00:00Z"))
        val engine = RuleInterpreter(AlJaziraRuleProfile.current())
        val actual = InterpretationTestAdapter { engine.interpretRouted(it,BankId("BANK_ALJAZIRA")) }.parse(input)
        fun event(r: ParseResult) = r.eventOrNull()
        fun details(r: ParseResult) = r.detailsOrNull()
        val e = event(actual)
        assertNotNull("$file outcome=$actual",e)
        val errors = mutableListOf<String>()
        if (expected.getValue("parseStatus").jsonPrimitive.content != e!!.parseStatus.name) errors += "status expected=${expected["parseStatus"]} actual=${e.parseStatus} reasons=${e.confidence.reasons}"
        if (expected.getValue("messageFamily").jsonPrimitive.content != e.messageFamily.name) errors += "family expected=${expected["messageFamily"]} actual=${e.messageFamily}"
        assertTrue("${file.name}:\n${errors.joinToString("\n")}\nresult=$actual",errors.isEmpty())
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases(): List<Array<File>> {
            val canonical = listOf(File("src/test/resources/testdata/bank_aljazira"),File("src/test/resources/testdata/engine")).asSequence()
                .flatMap { it.walkTopDown() }.filter { it.extension=="json" && it.name!="jazira_sms_bodies.json" }.toList()
            val reference = Json.parseToJsonElement(File("src/test/resources/testdata/bank_aljazira/reference/jazira_sms_bodies.json").readText()).jsonObject.getValue("messages").jsonArray
            val families = listOf("TRANSFER_OUT","TRANSFER_IN","TRANSFER_IN","PURCHASE","PURCHASE","TRANSFER_OUT","PURCHASE","TRANSFER_OUT","TRANSFER_IN","FINANCING_INSTALLMENT")
            check(reference.size==families.size)
            val generated = reference.mapIndexed { index,value ->
                val row = value.jsonObject
                val id = row.getValue("id").jsonPrimitive.content
                val fixture = buildJsonObject {
                    put("id",id);put("sender","AlJazira");put("body",row.getValue("body"))
                    put("expected",buildJsonObject { put("messageFamily",families[index]);put("parseStatus","SUCCESS") })
                }
                File("build/generated/reference-fixtures/$id.json").apply { parentFile!!.mkdirs();writeText(fixture.toString()) }
            }
            return (canonical+generated).sortedBy { it.name }.map { arrayOf(it) }.also { check(it.size>=55) }
        }
    }
}
