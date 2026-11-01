package com.baraa.masroof.engine.bank.experiment

import com.baraa.masroof.engine.bank.patterns.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PatternPersistenceTest {
    @Test fun identifierWidthUsesNormalizedDigitsAndKeepsOriginalEvidence() {
        for (raw in listOf("1\u200F23", "١\u200E٢٣", "𝟙𝟚𝟛")) {
            val body = "Synthetic event\nAcc:$raw"
            val value = GenericCandidateExtractor.extract(body).single { it.kind == CandidateKind.NUMBER }.text
            val pattern = SavedMessagePattern.fromChoices("unicode", "EXAMPLE_BANK", "Sender", "USER_CHOICE", body,
                listOf(FieldChoice("account", value.start, value.end)))
            assertEquals(3, pattern.parts.filterIsInstance<FieldSlot>().single().width)
            val original = requireNotNull(pattern.preview("EXAMPLE_BANK", "Sender", body)).fields.getValue("account")
            assertEquals(raw, original.raw)
            assertEquals(raw, body.substring(original.start, original.end))
            assertEquals("456", requireNotNull(pattern.preview("EXAMPLE_BANK", "Sender", body.replace(raw, "456")))
                .fields.getValue("account").raw)
            assertNull(pattern.preview("EXAMPLE_BANK", "Sender", body.replace(raw, "4567")))
        }
    }

    @Test fun definitionAndCopiesOwnTheirPartsAndMatchIdentically() {
        val parts = mutableListOf<PatternPart>(StaticText("Synthetic event\nAmount:"),
            FieldSlot("amount", FieldKind.MONEY, 0, false), StaticText(""))
        val pattern = SavedMessagePattern("immutable", "EXAMPLE_BANK", "Sender", "USER_CHOICE", parts = parts)
        pattern.validate()
        parts[1] = FieldSlot("amount", FieldKind.DIGITS, 2, true)
        val copyParts = pattern.parts.toMutableList()
        val copy = pattern.copy(parts = copyParts)
        copyParts.clear()
        assertThrows(UnsupportedOperationException::class.java) { (pattern.parts as MutableList<PatternPart>).clear() }
        for (value in listOf(pattern, copy)) {
            assertEquals("12SAR", requireNotNull(value.preview("EXAMPLE_BANK", "Sender", "Synthetic event\nAmount:12SAR"))
                .fields.getValue("amount").raw)
        }
    }

    @Test fun bareMonetaryValuesVaryInWidthAndPrecisionWhileIdentifiersStayFixed() {
        val body = "Synthetic event\nAmount:50\nCurrency:SAR\nAcc:007"
        val pattern = SavedMessagePattern.fromChoices("bare-money", "EXAMPLE_BANK", "Sender", "USER_CHOICE", body,
            listOf(FieldChoice("amount", body.indexOf("50"), body.indexOf("50") + 2),
                FieldChoice("account", body.indexOf("007"), body.length)))
        for (amount in listOf("5", "500", "1250.75", "1,250.50")) {
            val result = requireNotNull(pattern.preview("EXAMPLE_BANK", "Sender", body.replace("50", amount)))
            assertEquals(amount, result.fields.getValue("amount").raw)
            assertFalse(result.financialPostingAllowed)
        }
        for (invalid in listOf("-5", "5.123", "1,25", "garbage", "1".repeat(161))) {
            assertNull(pattern.preview("EXAMPLE_BANK", "Sender", body.replace("50", invalid)))
        }
        assertNull(pattern.preview("EXAMPLE_BANK", "Sender", body.replace("007", "0007")))
    }

    @Test fun explicitUserRangesWorkWhenDiscoveryDidNotOfferTheValue() {
        val body = "Synthetic event\nField:😀"
        val start = body.indexOf("😀")
        val pattern = SavedMessagePattern.fromChoices("direct", "EXAMPLE_BANK", "Sender", "USER_CHOICE", body,
            listOf(FieldChoice("counterparty", start, start + 2)))
        assertEquals("🚀", requireNotNull(pattern.preview("EXAMPLE_BANK", "Sender", body.replace("😀", "🚀")))
            .fields.getValue("counterparty").raw)
    }

    @Test fun timeFirstAndStandaloneDatesBecomeTypedSlotsWithoutUserSuppliedKinds() {
        val dates = listOf("19:05 26-07-2026" to "TIME_DMY_LONG", "19:05 2026-07-26" to "TIME_YMD",
            "27/08/2026" to "DATE_DMY_ONLY", "2026-08-27" to "DATE_YMD_ONLY")
        for ((date, kind) in dates) {
            val body = "Synthetic event\nDate:$date"
            val pattern = SavedMessagePattern.fromChoices("dates", "EXAMPLE_BANK", "Sender", "USER_CHOICE", body,
                listOf(FieldChoice("date", body.indexOf(date), body.length)))
            assertEquals(kind, pattern.parts.filterIsInstance<FieldSlot>().single().kind.name)
            val changed = body.replace("2026", "2027")
            assertEquals(date.replace("2026", "2027"), requireNotNull(pattern
                .preview("EXAMPLE_BANK", "Sender", changed)).fields.getValue("date").raw)
            val invalid = body.replace("26-07-2026", "31-02-2026").replace("2026-07-26", "2026-02-31")
                .replace("27/08/2026", "31/02/2026").replace("2026-08-27", "2026-02-31")
            assertNull(pattern.preview("EXAMPLE_BANK", "Sender", invalid))
        }
    }

    @Test fun humanChoicesNeedNoPrimitiveTypesOrManuallyAuthoredTemplate() {
        val example = syntheticExamples().first()
        val pattern = SavedMessagePattern.fromChoices("automatic", "EXAMPLE_BANK", "STC Bank", example.label,
            example.body, example.selections.map { FieldChoice(it.field, it.start, it.end) })
        assertEquals(example.selections.map { it.kind }, pattern.parts.filterIsInstance<FieldSlot>().map { it.kind })
        val (body, expected) = example.variant(1)
        assertEquals(expected, requireNotNull(pattern.preview("EXAMPLE_BANK", "STC Bank", body)).fields.mapValues { it.value.raw })
    }

    @Test fun selectedCandidatesBecomePersistedSlotsInsteadOfRememberedValues() {
        val example = syntheticExamples().first()
        val pattern = SavedMessagePattern.fromBindings("incoming", "EXAMPLE_BANK", "STC Bank", example.label,
            example.body, example.selections.map { UserFieldBinding(it) })
        val literals = pattern.parts.filterIsInstance<StaticText>().joinToString("\n") { it.value }
        assertFalse(literals.contains("137.28")); assertFalse(literals.contains("SYNTHETIC PERSON"))
        assertFalse(literals.contains("14/10/26")); assertFalse(literals.contains("007"))
        val (body, expected) = example.variant(1)
        val match = requireNotNull(SavedMessagePattern.unique(listOf(pattern), "EXAMPLE_BANK", "STC Bank", body))
        assertEquals(expected, match.second.fields.mapValues { it.value.raw })
        assertFalse(match.second.financialPostingAllowed)
    }

    @Test fun scopesDisableRevisionsAndConflicts() {
        val example = syntheticExamples().first()
        val pattern = SavedMessagePattern.fromBindings("incoming", "EXAMPLE_BANK", "STC Bank", example.label,
            example.body, example.selections.map { UserFieldBinding(it) })
        val body = example.variant(1).first
        assertNull(SavedMessagePattern.unique(listOf(pattern), "OTHER_BANK", "STC Bank", body))
        assertNull(SavedMessagePattern.unique(listOf(pattern), "EXAMPLE_BANK", "OtherSender", body))
        assertNull(SavedMessagePattern.unique(listOf(pattern.copy(enabled = false, revision = 2)), "EXAMPLE_BANK", "STC Bank", body))
        assertEquals(3, requireNotNull(SavedMessagePattern.unique(listOf(pattern.copy(revision = 3)), "EXAMPLE_BANK", "STC Bank", body)).first.revision)
        val loaded = listOf(pattern, pattern.copy(id = "conflicting", label = "DIFFERENT_LABEL"))
        assertNull(SavedMessagePattern.unique(loaded, "EXAMPLE_BANK", "STC Bank", body))
        assertNull(SavedMessagePattern.unique(loaded.reversed(), "EXAMPLE_BANK", "STC Bank", body))
    }

    @Test fun ignoredInlineValueChangesWithoutBeingFrozenOrReturned() {
        val example = markedExample("PURCHASE", "Purchase Card:([[card|DIGITS|4567]]) ref:([[unused|DIGITS|001]]) Amount:[[amount|MONEY|SAR 20.00]] at [[merchant|TEXT|SYNTHETIC SHOP]] on:[[time|DATE_YMD|2026-10-05 19:40:25]]")
        val pattern = SavedMessagePattern.fromBindings("inline", "EXAMPLE_BANK", "ExampleSender", example.label, example.body,
            example.selections.map { UserFieldBinding(it, ignored = it.field == "unused") })
        val changed = example.body.replace("001", "009").replace("SAR 20.00", "USD 85.00").replace("SYNTHETIC SHOP", "OTHER SHOP")
        val result = requireNotNull(pattern.preview("EXAMPLE_BANK", "ExampleSender", changed))
        assertEquals("USD 85.00", result.fields.getValue("amount").raw)
        assertEquals("OTHER SHOP", result.fields.getValue("merchant").raw)
        assertFalse(result.fields.containsKey("unused"))
    }
}
