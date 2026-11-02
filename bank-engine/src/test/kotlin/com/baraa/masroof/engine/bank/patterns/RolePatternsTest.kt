package com.baraa.masroof.engine.bank.patterns

import com.baraa.masroof.engine.bank.*
import com.baraa.masroof.engine.bank.rules.RuleLibrary
import com.baraa.masroof.finance.value.BankId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class RolePatternsTest {
    private val body = "Purchase\nAmount:10 SAR\nCard:1234\nMerchant:SHOP\nOn:2026-10-15 12:30"
    private val bank = RuleLibrary.current().asMessagePatterns("BANK_OTHER", listOf("OTHER"))
    private fun parse(definition: BankMessagePatterns = bank, text: String = body) =
        PatternFactInterpreter(MessagePatternConfiguration(banks = listOf(definition)))
            .interpretRouted(MessageInput("test", "router-selected", text, Instant.EPOCH, true), BankId(bank.bankId))

    @Test fun roleDefinitionsWorkForAnyBankAndKeepFlexibleFieldOrderAndOriginalEvidence() {
        val result = parse(text = body.replace("Amount:10 SAR\nCard:1234", "Card:1234\n\nAmount:11 SAR"))
        assertEquals(ParseStatus.SUCCESS, result.status)
        assertEquals("11.00", result.facts.amount!!.amount.toPlainString())
        assertEquals(BankId("BANK_OTHER"), result.facts.card!!.bank)
        assertTrue(result.patternProvenance!!.roleProfile)
        assertEquals("bank.purchase", result.patternProvenance.patternId)
    }

    @Test fun disabledRolesDoNotLoseSecurityOrCompetingFamilyEvidence() {
        val disabled = bank.copy(patterns = bank.patterns.map { if (it.id == "bank.purchase") it.copy(enabled = false) else it })
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(disabled).status)
        assertEquals(ParseStatus.NON_FINANCIAL, parse(disabled, "OTP\n$body").status)
        val collision = parse(disabled, body + "\nCash withdrawal")
        assertEquals(ParseStatus.REVIEW_REQUIRED, collision.status)
        assertNotEquals(MessageFamily.PURCHASE, collision.facts.family)
    }

    @Test fun approvedLearnedAndRoleMatchesRemainConflictsEvenWithTheSameFamily() {
        val taught = SavedMessagePattern.fromChoices("taught", bank.bankId, "OTHER", "PURCHASE", body,
            listOf("amount" to "10 SAR", "card" to "1234", "merchant" to "SHOP", "occurred_at" to "2026-10-15 12:30").map { (field, value) ->
                FieldChoice(field, body.indexOf(value), body.indexOf(value) + value.length)
            })
        val result = parse(bank.copy(patterns = bank.patterns +
            taught.copy(factContract = PatternFactContract(MessageFamily.PURCHASE, MoneyDirection.OUTGOING))))
        assertTrue(Blocker.CONFLICTING_FACTS in result.blockers)
        assertEquals(MessageFamily.UNKNOWN, result.facts.family)
        // A preview-only template has no authority, so it cannot take a recognized message away from its role.
        assertEquals(ParseStatus.SUCCESS, parse(bank.copy(patterns = bank.patterns + taught)).status)
    }

    @Test fun roleProfilesOwnTheirCollectionsAndRejectAContradictoryContract() {
        val phrases = mutableListOf("purchase")
        val schema = RuleLibrary.current().copy(rules = listOf(RuleLibrary.current().rules.first().copy(phrases = phrases)))
        val definition = schema.asMessagePatterns(bank.bankId, listOf("OTHER"))
        val before = PatternFactInterpreter(MessagePatternConfiguration(banks = listOf(definition))).fingerprint
        phrases.clear()
        assertEquals(ParseStatus.SUCCESS, parse(definition).status)
        assertEquals(before, PatternFactInterpreter(MessagePatternConfiguration(banks = listOf(definition))).fingerprint)
        assertTrue(runCatching { definition.patterns.single().copy(factContract = PatternFactContract(MessageFamily.REFUND, MoneyDirection.INCOMING)) }.isFailure)
    }

    @Test fun aLearnedDefinitionCannotBypassQuotedContextWhenTheBuiltInRoleIsDisabled() {
        val quoted = ">" + body
        val taught = SavedMessagePattern.fromChoices("quoted", bank.bankId, "OTHER", "PURCHASE", quoted,
            listOf("amount" to "10 SAR", "card" to "1234", "occurred_at" to "2026-10-15 12:30").map { (field, value) ->
                FieldChoice(field, quoted.indexOf(value), quoted.indexOf(value) + value.length)
            }).copy(factContract = PatternFactContract(MessageFamily.PURCHASE, MoneyDirection.OUTGOING, cardBank = BankId(bank.bankId)))
        val disabled = bank.copy(patterns = bank.patterns.map { if (it.id == "bank.purchase") it.copy(enabled = false) else it } + taught)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(disabled, quoted).status)
    }
}
