package com.baraa.masroof.integration.learning

import com.baraa.masroof.application.interpretation.FamilyAssistBridge
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
                classifier?.let { c -> FamilyAssist { input, title -> FamilyAssistBridge.suggest(c, bank, input.body, title) } })
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
}
