package com.baraa.masroof.engine.bank.experiment

import com.baraa.masroof.engine.bank.patterns.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExampleTemplateTest {
    @Test fun inlineBindingsAreLearnedAutomaticallyIncludingPrefixMoneyAndSeconds() {
        val example = markedExample("PURCHASE", "Purchase Card:([[card|DIGITS|4567]]) Value:[[amount|MONEY|SAR 32.50]] at [[merchant|TEXT|EXAMPLE SHOP]] on:[[time|DATE_YMD|2026-10-05 19:40:25]]")
        val changed = "Purchase Card:(8910) Value:USD 75.20 at ANOTHER SHOP on:2026-10-07 11:15:30"
        val result = requireNotNull(example.learn().preview("STC Bank", changed))
        assertEquals("8910", result.fields.getValue("card").raw)
        assertEquals("USD 75.20", result.fields.getValue("amount").raw)
        assertEquals("ANOTHER SHOP", result.fields.getValue("merchant").raw)
        assertEquals("2026-10-07 11:15:30", result.fields.getValue("time").raw)
    }

    @Test fun oneAnnotatedExampleExtractsNewValuesRatherThanCopyingTheTeacher() {
        val body = "Internal incoming transfer\nAmount:123.45SR\nFrom:EXAMPLE PERSON\nAcc:007\nAt:14/10/26 12:21"
        fun select(field: String, kind: FieldKind, value: String): Selection {
            val start = body.indexOf(value)
            return Selection(field, kind, start, start + value.length)
        }
        val template = ExampleTemplate.learn("STC Bank", "TRANSFER_IN", body, listOf(
            select("amount", FieldKind.MONEY, "123.45SR"),
            select("counterparty", FieldKind.TEXT, "EXAMPLE PERSON"),
            select("account", FieldKind.DIGITS, "007"),
            select("time", FieldKind.DATE_DMY_SHORT, "14/10/26 12:21"),
        ))
        val changed = "Internal incoming transfer\nAmount:96SR\nFrom:ANOTHER PERSON\nAcc:009\nAt:15/10/26 09:05"
        val result = requireNotNull(template.preview("STC Bank", changed))
        assertEquals("96SR", result.fields.getValue("amount").raw)
        assertEquals("ANOTHER PERSON", result.fields.getValue("counterparty").raw)
        assertEquals("009", result.fields.getValue("account").raw)
        assertEquals("15/10/26 09:05", result.fields.getValue("time").raw)
        result.fields.values.forEach { assertEquals(it.raw, changed.substring(it.start, it.end)) }
    }

    @Test fun allFiveLayoutsExtractIndependentSyntheticValues() {
        syntheticExamples().forEach { example ->
            val template = example.learn()
            for (variant in 1..2) {
                val (body, expected) = example.variant(variant)
                val result = requireNotNull(template.preview("STC Bank", body))
                assertEquals(expected, result.fields.mapValues { it.value.raw })
                assertEquals(example.label, result.label)
                result.fields.values.forEach { assertEquals(it.raw, body.substring(it.start, it.end)) }
            }
        }
    }

    @Test fun repeatedFromRolesChangeIndependentlyWithoutRememberingTheBankOrName() {
        val example = syntheticExamples()[3]
        val body = "Credit Local transfer\n75.10SR\nFrom ANOTHER PERSON\nFrom ANOTHER_BANK\nFrom 008\n09-10-2026 11:07"
        val result = requireNotNull(example.learn().preview("STC Bank", body))
        assertEquals("ANOTHER PERSON", result.fields.getValue("counterparty").raw)
        assertEquals("ANOTHER_BANK", result.fields.getValue("counterparty_bank").raw)
        assertEquals("008", result.fields.getValue("account").raw)
        assertEquals("75.10SR", result.fields.getValue("amount").raw)
    }

    @Test fun originalUtf16EvidenceSurvivesArabicDigitsEmojiAndCrLf() {
        val example = syntheticExamples().first()
        val body = "Internal incoming transfer\r\nAmount： ٩٦٫٢٥SR\r\nFrom:شَخص تجريبي 😀\r\nAcc:٠٠٩\r\nAt:١٥/١٠/٢٦ ٠٩:٠٥"
        val result = requireNotNull(example.learn().preview("STC Bank", body))
        assertEquals("٩٦٫٢٥SR", result.fields.getValue("amount").raw)
        assertEquals("شَخص تجريبي 😀", result.fields.getValue("counterparty").raw)
        assertEquals("٠٠٩", result.fields.getValue("account").raw)
        result.fields.values.forEach { assertEquals(it.raw, body.substring(it.start, it.end)) }
    }

    @Test fun originalSelectionsDisambiguateEqualDigitsInMoneyAndAccount() {
        val example = markedExample("TRANSFER_IN", "Incoming\nAmount:[[amount|MONEY|123SR]]\nAcc:[[account|DIGITS|123]]")
        val result = requireNotNull(example.learn().preview("STC Bank", "Incoming\nAmount:89SR\nAcc:001"))
        assertEquals("89SR", result.fields.getValue("amount").raw)
        assertEquals("001", result.fields.getValue("account").raw)
    }

    @Test fun changedExecutionTitlesAndExtraLinesNeverMatchTheTaughtLayout() {
        val example = syntheticExamples().first()
        val template = example.learn()
        val negatives = listOf(
            example.body.replace("Internal incoming transfer", "Internal incoming transfer pending"),
            example.body.replace("Internal incoming transfer", "OTP for internal incoming transfer"),
            example.body + "\nOTP: 123456",
            example.body + "\nDeclined",
            example.body + "\nAmount:90SR",
            example.body.replace("Amount:", "Available balance:"),
            example.body.lineSequence().filterNot { it.startsWith("Amount:") }.joinToString("\n"),
            example.body.lineSequence().filterNot { it.startsWith("From:") }.joinToString("\n"),
        )
        negatives.forEachIndexed { index, body -> assertNull(template.preview("STC Bank", body), "synthetic negative $index") }
        assertNull(template.preview("Another sender", example.body))
    }

    @Test fun invalidMagnitudesCurrencyAndDateOrUnseenIdentifierWidthAbstain() {
        val example = syntheticExamples().first()
        val template = example.learn()
        listOf("0SR", "-10SR", "1.234SR", "1,23SR", "10XYZ", "10SR extra").forEach { amount ->
            assertNull(template.preview("STC Bank", example.replace("amount", amount)))
        }
        assertNull(template.preview("STC Bank", example.replace("time", "31/02/26 12:21")))
        assertNull(template.preview("STC Bank", example.replace("time", "2026-10-14 12:21")))
        assertNull(template.preview("STC Bank", example.replace("account", "0007")))
    }

    @Test fun conflictingTemplatesAbstainIndependentlyOfOrder() {
        val example = syntheticExamples().first()
        val incoming = example.learn()
        val conflicting = example.copy(label = "DIFFERENT_HUMAN_LABEL").learn()
        assertNull(listOf(incoming, conflicting).unique("STC Bank", example.body))
        assertNull(listOf(conflicting, incoming).unique("STC Bank", example.body))
        assertNotNull(listOf(incoming).unique("STC Bank", example.body))
    }

    @Test fun overlappingOutOfBoundsOrTitleSelectionsAreRejected() {
        val example = syntheticExamples().first()
        val amount = example.selections.first()
        listOf(
            example.selections + amount.copy(field = "overlap"),
            listOf(amount.copy(end = example.body.length + 1)),
            listOf(Selection("title", FieldKind.TEXT, 0, 8)),
            listOf(amount.copy(field = "invalid field")),
        ).forEach { selections ->
            assertThrows(IllegalArgumentException::class.java) {
                ExampleTemplate.learn("STC Bank", example.label, example.body, selections)
            }
        }
    }

    @Test fun twoVariableFieldsOnOneLineRequireMoreEvidenceInsteadOfAGreedyGuess() {
        val example = markedExample("EXAMPLE", "Example\nName:[[first|TEXT|FIRST]] [[last|TEXT|LAST]]")
        assertThrows(IllegalArgumentException::class.java) { example.learn() }
    }

    @Test fun selectedEmojiMustNotSplitASurrogatePair() {
        val body = "Example\nFrom:😀"
        val start = body.indexOf("😀")
        assertThrows(IllegalArgumentException::class.java) {
            ExampleTemplate.learn("STC Bank", "EXAMPLE", body, listOf(Selection("name", FieldKind.TEXT, start + 1, start + 2)))
        }
    }

    @Test fun templatesRetainTheirOwnAnnotationsAndRefuseOversizedInput() {
        val example = syntheticExamples().first()
        val selections = example.selections.toMutableList()
        val template = ExampleTemplate.learn("STC Bank", example.label, example.body, selections)
        selections.clear()
        assertEquals(4, requireNotNull(template.preview("STC Bank", example.body)).fields.size)
        assertNull(template.preview("STC Bank", example.body + " ".repeat(4096)))
    }

    @Test fun frozenTemplatesEvaluateHeldOutMessagesWithoutLearningFromThem() {
        val teachers = syntheticExamples()
        val templates = teachers.map { it.learn() }
        val (known, knownFields) = teachers.first().variant(1)
        val unseen = "Previously unseen transfer\nAmount:17SR"
        // Only original teachers reach learn(). Neither expected fields nor held-out text can alter a template.
        assertTrue(listOf(known, unseen).none { body -> teachers.any { it.body == body } })
        val preview = requireNotNull(templates.unique("STC Bank", known))
        assertEquals(teachers.first().label, preview.label)
        assertEquals(knownFields, preview.fields.mapValues { it.value.raw })
        assertTrue(preview.fields.values.all { it.raw == known.substring(it.start, it.end) })
        assertTrue(templates.none { it.preview("STC Bank", unseen) != null })
    }

    @Test fun oneAdditionalExampleHandlesAnUnseenLayoutWithoutLearningTheHoldout() {
        val originalTeachers = syntheticExamples()
        val originalTemplates = originalTeachers.map { it.learn() }
        val teacher = markedExample("TRANSFER_IN",
            "Previously unseen incoming transfer\n[[amount|MONEY|33.00 SAR]]\nReference:[[reference|TEXT|*AB12]]")
        val (holdout, holdoutFields) = teacher.variant(1)
        assertNull(originalTemplates.unique("STC Bank", teacher.body))
        assertTrue((originalTeachers + teacher).none { it.body == holdout })
        val extendedTemplates = originalTemplates + teacher.learn()
        val reproduced = requireNotNull(extendedTemplates.unique("STC Bank", teacher.body))
        assertEquals(teacher.selections.associate { it.field to teacher.body.substring(it.start, it.end) },
            reproduced.fields.mapValues { it.value.raw })
        assertNull(originalTemplates.unique("STC Bank", holdout))
        val result = requireNotNull(extendedTemplates.unique("STC Bank", holdout))
        assertEquals(teacher.label, result.label)
        assertEquals(holdoutFields, result.fields.mapValues { it.value.raw })
        assertTrue(result.fields.values.all { it.raw == holdout.substring(it.start, it.end) })
        // Additional learning must retain all original templates and their meaning.
        originalTeachers.forEach { original ->
            assertEquals(original.learn().preview("STC Bank", original.body), extendedTemplates.unique("STC Bank", original.body))
        }
    }

    private fun List<ExampleTemplate>.unique(sender: String, body: String): Preview? =
        mapNotNull { it.preview(sender, body) }.singleOrNull()
}

/** Only synthetic, anonymized fixtures are checked in. Each marker simulates a human selection. */
internal data class TeachingExample(val label: String, val body: String, val selections: List<Selection>) {
    fun learn(): ExampleTemplate = ExampleTemplate.learn("STC Bank", label, body, selections)
    fun replace(field: String, value: String): String = selections.single { it.field == field }.let {
        body.replaceRange(it.start, it.end, value)
    }

    fun variant(number: Int): Pair<String, Map<String, String>> {
        val values = selections.associate { selection -> selection.field to when (selection.kind) {
            FieldKind.MONEY -> if (number == 1) "731.09 SAR" else "82USD"
            FieldKind.DECIMAL -> if (number == 1) "731.09" else "82"
            FieldKind.TEXT -> if (number == 1) "DIFFERENT VALUE" else "قيمة تجريبية"
            FieldKind.DIGITS -> "0".repeat(selection.end - selection.start - 1) + number
            FieldKind.DATE_DMY_SHORT -> "17/10/26 09:0$number"
            FieldKind.DATE_DMY_LONG -> "17-10-2026 09:0$number"
            FieldKind.DATE_YMD -> "2026-10-17 09:0$number"
            FieldKind.TIME_DMY_LONG -> "09:0$number 17-10-2026"
            FieldKind.TIME_YMD -> "09:0$number 2026-10-17"
            FieldKind.DATE_DMY_ONLY -> "17/10/2026"
            FieldKind.DATE_YMD_ONLY -> "2026-10-17"
        } }
        var changed = body
        selections.sortedByDescending { it.start }.forEach { changed = changed.replaceRange(it.start, it.end, values.getValue(it.field)) }
        return changed to values
    }
}

internal fun markedExample(label: String, annotated: String): TeachingExample {
    val selections = mutableListOf<Selection>()
    var cursor = 0
    val body = buildString {
        Regex("\\[\\[([a-z_]+)\\|([A-Z_]+)\\|([^\\n]*?)]]").findAll(annotated).forEach { marker ->
            append(annotated.substring(cursor, marker.range.first))
            val start = length
            append(marker.groupValues[3])
            selections += Selection(marker.groupValues[1], FieldKind.valueOf(marker.groupValues[2]), start, length)
            cursor = marker.range.last + 1
        }
        append(annotated.substring(cursor))
    }
    return TeachingExample(label, body, selections)
}

internal fun syntheticExamples(): List<TeachingExample> = listOf(
    markedExample("TRANSFER_IN", "Internal incoming transfer\nAmount:[[amount|MONEY|137.28SR]]\nFrom:[[counterparty|TEXT|SYNTHETIC PERSON]]\nAcc:[[account|DIGITS|007]]\nAt:[[time|DATE_DMY_SHORT|14/10/26 12:21]]"),
    markedExample("TRANSFER_OUT", "Internal outward transfer\nAmount:[[amount|MONEY|29SR]]\nTo:[[counterparty|TEXT|SYNTHETIC RECIPIENT]]\nAcc:[[account|DIGITS|008]]\nAt:[[time|DATE_DMY_SHORT|12/10/26 18:06]]"),
    markedExample("FUNDING_EXECUTED", "Adding money to account\nAmount: [[amount|MONEY|210.00 SAR]]\nVia: *[[funding_card|DIGITS|4567]]\nAt: [[time|DATE_YMD|2026-10-12 18:06]]"),
    markedExample("TRANSFER_IN", "Credit Local transfer\n[[amount|MONEY|36SR]]\nFrom [[counterparty|TEXT|SYNTHETIC SENDER]]\nFrom [[counterparty_bank|TEXT|EXAMPLE_BANK]]\nFrom [[account|DIGITS|001]]\n[[time|DATE_DMY_LONG|08-10-2026 14:57]]"),
    markedExample("PERSON_PAYMENT_EXECUTED", "Pay qattah\nAmount: [[amount|MONEY|18.75 SAR]]\nTo: [[counterparty|TEXT|SYNTHETIC PAYEE]]\nAt: [[time|DATE_DMY_SHORT|08/10/26 12:34]]"),
)
