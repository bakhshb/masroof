package com.baraa.masroof.integration.learning

import com.baraa.masroof.application.interpretation.FamilyAssistBridge
import com.baraa.masroof.bank.InterpretationDraftMapper
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.rules.InformationalMessagePolicy
import com.baraa.masroof.engine.bank.interpretation.FamilyTextPreparation
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.engine.bank.MessageFamily
import com.baraa.masroof.engine.bank.MessageInput
import com.baraa.masroof.engine.bank.interpretation.FamilyAssist
import com.baraa.masroof.engine.bank.interpretation.FamilyOrigin
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.ml.family.FamilyClassifier
import com.baraa.masroof.ml.family.HumanFamilyLabel
import com.baraa.masroof.ml.family.SmsFamilyLabel
import com.baraa.masroof.testsupport.bundledPatterns
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

/** A correction teaches later messages of the same wording in every bank, not only AlJazira. */
class MultiBankTemplateLearningTest {
    private val purchases = Json.parseToJsonElement(File("src/test/resources/testdata/multibank/messages.json").readText())
        .jsonArray.map { it.jsonObject }.filter { it.text("family") == "PURCHASE" && it.text("status") == "SUCCESS" }
        .distinctBy { it.text("bankId") }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test fun aCorrectedUnfamiliarTitleTeachesTheSameBank() {
        assertTrue(purchases.size >= 4)
        purchases.forEach { row ->
            val bank = row.text("bankId")
            // Wording the bundled rules do not know and the reviewed vocabulary does not list.
            val body = "عملية جديدة غير مالوفة\n" + row.text("body").substringAfter('\n')
            val engine = PatternFactInterpreter.of(bundledPatterns(bank))
            fun read(classifier: FamilyClassifier?) = engine.interpretRouted(
                MessageInput("learn-$bank", row.text("sender"), body, Instant.parse("2026-09-15T09:30:00Z"), true), BankId(bank),
                classifier?.let { c -> FamilyAssist { input, title -> FamilyAssistBridge.suggest(c, bank, input.body) } })
            assertEquals(bank, MessageFamily.UNKNOWN, read(null).facts.family)
            val features = requireNotNull(FamilyAssistBridge.clean(body).features) { "$bank wording rejected" }
            val label = HumanFamilyLabel(bank, "raw-$bank", "decision-$bank", SmsFamilyLabel.PURCHASE, features.template,
                features.terms, 1, "USER_MESSAGE_FAMILY")
            val learned = read(FamilyClassifier(bank, listOf(label)))
            assertEquals(bank, MessageFamily.PURCHASE, learned.facts.family)
            assertEquals(bank, FamilyOrigin.HUMAN_TEMPLATE, learned.familyInference?.origin)
            // Another bank's correction never applies here.
            assertEquals(bank, MessageFamily.UNKNOWN, read(FamilyClassifier("BANK_OTHER", listOf(label.copy(bankId = "BANK_OTHER")))).facts.family)
        }
    }

    /** One "not financial" decision keeps the same wording out of review in every bank, with or without role rules. */
    @Test fun aNotFinancialDecisionIsRememberedInEveryBank() {
        val senders = purchases.associate { it.text("bankId") to it.text("sender") } +
            mapOf("BANK_ALJAZIRA" to "jazirabank", "BANK_STC" to "STC Bank")
        val mapper = InterpretationDraftMapper()
        senders.forEach { (bank, sender) ->
            val body = "عرض حصري لعملائنا الكرام\nاستمتع بمزايا البطاقة الجديدة 2026"
            val input = MessageInput("promo-$bank", sender, body, Instant.parse("2026-09-15T09:30:00Z"), true)
            val ruled = PatternFactInterpreter.of(bundledPatterns(bank)).interpretRouted(input, BankId(bank))
            assertEquals(bank, MessageFamily.UNKNOWN, ruled.facts.family)
            val features = requireNotNull(FamilyAssistBridge.clean(body).features) { "$bank wording rejected" }
            val label = HumanFamilyLabel(bank, "raw-$bank", "decision-$bank", SmsFamilyLabel.NON_FINANCIAL, features.template,
                features.terms, 1, "USER_NON_FINANCIAL")
            fun remembered(classifier: FamilyClassifier) = FamilyAssistBridge.rememberNonFinancial(ruled,
                FamilyAssistBridge.suggest(classifier, bank, body))
            val settled = remembered(FamilyClassifier(bank, listOf(label)))
            assertEquals(bank, MessageFamily.NON_FINANCIAL, settled.facts.family)
            assertTrue(bank, mapper.map(input, settled, Bank.fromId(bank)) is ParseResult.NonFinancial)
            assertTrue(bank, mapper.map(input, ruled, Bank.fromId(bank)).let { it is ParseResult.ReviewRequired &&
                !InformationalMessagePolicy.shouldAutoIgnore(it.draft?.messageFamily) })
            // Different wording, another bank's decision, or a conflicting decision change nothing.
            assertSame(bank, ruled, remembered(FamilyClassifier("BANK_OTHER", listOf(label.copy(bankId = "BANK_OTHER")))))
            assertSame(bank, ruled, remembered(FamilyClassifier(bank, listOf(label,
                label.copy(rawId = "raw-2", decisionId = "decision-2", family = SmsFamilyLabel.PURCHASE, provenance = "USER_MESSAGE_FAMILY")))))
            val other = "عرض حصري آخر لعملائنا\nاستمتع بمزايا البطاقة"
            assertNull(bank, FamilyAssistBridge.suggest(FamilyClassifier(bank, listOf(label)), bank, other))
            // A message with a transaction amount stays reviewable even with the remembered wording.
            val withAmount = ruled.copy(facts = ruled.facts.copy(amount = com.baraa.masroof.finance.value.Money.of("250",
                com.baraa.masroof.finance.value.Currency.SAR)))
            assertSame(bank, withAmount, FamilyAssistBridge.rememberNonFinancial(withAmount,
                FamilyAssistBridge.suggest(FamilyClassifier(bank, listOf(label)), bank, body)))
        }
    }

    /** A hidden copy goes back to review once the remembered decision no longer holds, and only then. */
    @Test fun aContradictedMemoryReturnsItsHiddenMessagesToReview() {
        val bank = "BANK_STC"
        val body = "عرض حصري لعملائنا الكرام\nاستمتع بمزايا البطاقة الجديدة"
        val features = requireNotNull(FamilyAssistBridge.clean(body).features)
        val ignored = HumanFamilyLabel(bank, "raw-1", "decision-1", SmsFamilyLabel.NON_FINANCIAL, features.template,
            features.terms, 1, "USER_NON_FINANCIAL")
        val hidden = com.baraa.masroof.parsing.repository.ParsedEventRecord(com.baraa.masroof.domain.model.ParsedEvent(
            "evt-hidden", "hidden", Bank.fromId(bank), com.baraa.masroof.domain.model.MessageFamily.NON_FINANCIAL, null, null,
            null, null, null, null, null, null, null, null, com.baraa.masroof.domain.model.Confidence(1.0,
                listOf("family_origin:HUMAN_TEMPLATE")), com.baraa.masroof.domain.model.ParseStatus.REVIEW_REQUIRED),
            com.baraa.masroof.parsing.model.ParsedEventDetails())
        val bodies = mapOf("hidden" to body)
        assertTrue(FamilyAssistBridge.revokedMemories(listOf(hidden), bodies, mapOf(bank to FamilyClassifier(bank, listOf(ignored)))).isEmpty())
        val contradicted = FamilyClassifier(bank, listOf(ignored, ignored.copy(rawId = "raw-2", decisionId = "decision-2",
            family = SmsFamilyLabel.PURCHASE, provenance = "USER_MESSAGE_FAMILY")))
        assertEquals(setOf("hidden"), FamilyAssistBridge.revokedMemories(listOf(hidden), bodies, mapOf(bank to contradicted)))
        assertEquals(setOf("hidden"), FamilyAssistBridge.revokedMemories(listOf(hidden), bodies, emptyMap()))
    }
}
