package com.baraa.masroof.bank.patterns

import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.engine.bank.MessageFamily
import com.baraa.masroof.engine.bank.MessageInput
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.finance.value.BankId
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pending preview's single engine pass never finds a role that previewing each role alone misses,
 * and agrees whenever at most one role matches alone. It may settle an overlap the way processing does.
 */
class CombinedRolePreviewParityTest {
    @Test fun combinedRolePreviewMatchesPerRolePreviewOnEveryFixture() {
        val bodies = File("src/test/resources/testdata").walkTopDown().filter { it.extension == "json" }.flatMap { file ->
            Regex("\"body\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")").findAll(file.readText())
                .map { Json.parseToJsonElement(it.groupValues[1]).jsonPrimitive.content }
        }.toList()
        assertTrue(bodies.size > 100)
        File("src/main/assets/bank-patterns").listFiles()!!.sorted().forEach { asset ->
            val configuration = MessagePatternConfigurationCodec.decode(asset.readText())
            val bank = configuration.banks.single()
            val interpreter = PatternFactInterpreter(configuration)
            bodies.forEach { body ->
                val routed = interpreter.previewRouted(MessageInput("parity", bank.senders.first(), body, Instant.EPOCH, true), BankId(bank.bankId))
                // OTP security is decided before any pattern is read.
                if (routed.interpretation.facts.family == MessageFamily.OTP) return@forEach
                val single = bank.currentPatterns.filter { it.roleProfile != null }
                    .mapNotNull { pattern -> pattern.preview(bank.bankId, pattern.sender, body)?.let { pattern.id } }
                val combined = routed.matches.filter { it.first.roleProfile != null }.map { it.first.id }
                val label = "${bank.bankId}: $body"
                assertTrue(label, single.containsAll(combined))
                if (single.size <= 1) assertEquals(label, single, combined)
            }
        }
    }

    @Test fun refundWordingOverAPurchaseIsOneRefundRole() {
        val configuration = MessagePatternConfigurationCodec.decode(File("src/main/assets/bank-patterns/BANK_ALJAZIRA.patterns.json").readText())
        val body = "استرداد مبلغ عملية شراء عبر نقاط البيع\nبطاقة: 7271\nلدى: TEST_SHOP_C\nبمبلغ: 89.50 SAR\nفي: 2026-08-04 18:20"
        val routed = PatternFactInterpreter(configuration).previewRouted(MessageInput("refund", "AlJazira", body, Instant.EPOCH, true), BankId("BANK_ALJAZIRA"))
        assertEquals(listOf("bank.refund"), routed.matches.map { it.first.id })
    }
}
