package com.baraa.masroof.engine.bank.experiment

import com.baraa.masroof.engine.bank.patterns.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class GenericCandidateExtractorTest {
    @Test fun timeFirstAndDateOnlyRemainWholeCandidatesWithOriginalOffsets() {
        val body = "وقت:١٩:٠٥ ٢٠٢٦-٠٧-٢٦\nاستحقاق:2026-08-27\nقيمة:75SR"
        val result = GenericCandidateExtractor.extract(body)
        assertTrue(result.any { it.kind == CandidateKind.DATE_TIME && it.text.raw == "١٩:٠٥ ٢٠٢٦-٠٧-٢٦" })
        assertTrue(result.any { it.kind.name == "DATE" && it.text.raw == "2026-08-27" })
        assertEquals(emptyList<String>(), result.filter { it.kind == CandidateKind.NUMBER }.map { it.text.raw })
        result.forEach { assertEquals(it.text.raw, body.substring(it.text.start, it.text.end)) }
    }

    @Test fun discoversValuesInUnseenTextWithoutSenderTypeOrTraining() {
        val body = "صيغة تجريبية لم تُعلّم\nقيمة:٨٧٫٦٥SR\nطرف:اسم تجريبي\n2026-08-18 13:59\nمعرّف:*A9Z7"
        val candidates = GenericCandidateExtractor.extract(body)
        assertTrue(candidates.any { it.kind == CandidateKind.MONEY && it.text.raw == "٨٧٫٦٥SR" })
        assertTrue(candidates.any { it.kind == CandidateKind.DATE_TIME && it.text.raw == "2026-08-18 13:59" })
        assertTrue(candidates.any { it.kind == CandidateKind.IDENTIFIER && it.text.raw == "*A9Z7" })
        assertTrue(candidates.any { it.kind == CandidateKind.TEXT && it.text.raw == "اسم تجريبي" })
        candidates.forEach { assertEquals(it.text.raw, body.substring(it.text.start, it.text.end)) }
    }

    @Test fun moneyAndTimeDoNotDependOnHeadingsLabelsOrLineOrder() {
        val fragments = listOf("AAA: 71.25SR", "ZZZ: 2026-08-18 13:59", "Random words 001")
        val before = GenericCandidateExtractor.extract(fragments.joinToString("\n"))
        val after = GenericCandidateExtractor.extract("Unexpected title\n" + fragments.reversed().joinToString("\n"))
        fun numericValues(rows: List<TextCandidate>) = rows.filter { it.kind != CandidateKind.TEXT }.map { it.kind to it.text.raw }.toSet()
        assertEquals(numericValues(before), numericValues(after))
    }

    @Test fun balancesAndAmountsRemainSeparateUnassignedCandidates() {
        val body = "Unknown\nValue A:54SR\nValue B:542SR"
        assertEquals(listOf("54SR", "542SR"), GenericCandidateExtractor.extract(body)
            .filter { it.kind == CandidateKind.MONEY }.map { it.text.raw })
    }

    @Test fun repeatedWordsExposeTextAndNumbersWithoutKnowingTheirRoles() {
        val body = "Previously unseen layout\nFrom SYNTHETIC PERSON\nFrom EXAMPLEBANK\nFrom 001"
        val result = GenericCandidateExtractor.extract(body)
        assertTrue(result.any { it.kind == CandidateKind.TEXT && it.text.raw == "SYNTHETIC PERSON" })
        assertTrue(result.any { it.kind == CandidateKind.TEXT && it.text.raw == "EXAMPLEBANK" })
        assertTrue(result.any { it.kind == CandidateKind.NUMBER && it.text.raw == "001" })
    }

    @Test fun timestampsAndMaskedIdsDoNotLeakNumericFragments() {
        val body = "At:12/10/26 18:06\nAcc:001\nVia:*4567\nRef:*AB8Z"
        val result = GenericCandidateExtractor.extract(body)
        assertEquals(listOf("001"), result.filter { it.kind == CandidateKind.NUMBER }.map { it.text.raw })
        assertEquals(listOf("*4567", "*AB8Z"), result.filter { it.kind == CandidateKind.IDENTIFIER }.map { it.text.raw })
        assertEquals(1, result.count { it.kind == CandidateKind.DATE_TIME })
    }

    @Test fun lexicalHintsKeepSignsPrecisionAndAmbiguousSeparatorsForLaterValidation() {
        val body = "A: -10.005 USD\nB: 1.234,56 EUR\nC: SAR50\nD: 0SR\nE: 31/02/26 11:22"
        val result = GenericCandidateExtractor.extract(body)
        assertEquals(listOf("-10.005 USD", "1.234,56 EUR", "SAR50", "0SR"),
            result.filter { it.kind == CandidateKind.MONEY }.map { it.text.raw })
        // Recognizing a date-shaped string is not confirming that the date exists.
        assertTrue(result.any { it.kind == CandidateKind.DATE_TIME && it.text.raw == "31/02/26 11:22" })
    }

    @Test fun identicalValuesRetainIndependentOriginalOffsets() {
        val body = "Any text\nA: 64SR\nB: 64SR"
        val amounts = GenericCandidateExtractor.extract(body).filter { it.kind == CandidateKind.MONEY }
        assertEquals(2, amounts.size)
        assertNotEquals(amounts[0].text.start, amounts[1].text.start)
        amounts.forEach { assertEquals("64SR", body.substring(it.text.start, it.text.end)) }
    }

    @Test fun maskingAndInlineTextRemainVisibleWithoutBankRules() {
        val body = "Field:*50SR\nUnknown label: 93SR OtherLabel NORTH STAR\nCode:AB123"
        val result = GenericCandidateExtractor.extract(body)
        assertTrue(result.any { it.kind == CandidateKind.IDENTIFIER && it.text.raw == "*50SR" })
        assertFalse(result.any { it.kind == CandidateKind.MONEY && it.text.raw == "50SR" })
        assertTrue(result.any { it.kind == CandidateKind.TEXT && it.text.raw == "NORTH STAR" })
        assertTrue(result.any { it.kind == CandidateKind.IDENTIFIER && it.text.raw == "AB123" })
    }

    @Test fun inlineTextBeforeATrailingColonRetainsTheWholeValueCandidate() {
        val body = "شراء تجريبي\nبطاقة تجريبية (4321) بمبلغ (007) SAR 72.10 لدى EXAMPLE MARKET LTD في: 2026-10-05 19:40:25."
        val result = GenericCandidateExtractor.extract(body)
        assertTrue(result.any { it.kind == CandidateKind.TEXT && it.text.raw == "EXAMPLE MARKET LTD" })
        assertEquals(listOf("4321", "007"), result.filter { it.kind == CandidateKind.NUMBER }.map { it.text.raw })
        assertEquals(listOf("SAR 72.10"), result.filter { it.kind == CandidateKind.MONEY }.map { it.text.raw })
        assertEquals(listOf("2026-10-05 19:40:25"), result.filter { it.kind == CandidateKind.DATE_TIME }.map { it.text.raw })
        result.forEach { assertEquals(it.text.raw, body.substring(it.text.start, it.text.end)) }
    }

    @Test fun everyColonPreservesTextOnBothSidesAcrossLanguagesAndLayouts() {
        listOf(
            "prefix ALPHA BETA at: 2026-10-05 19:40:25\nextra: GAMMA DELTA" to "ALPHA BETA",
            "عنوان: 20SR عند متجر تجريبي بتاريخ: 2026-10-05 19:40:25" to "متجر تجريبي",
        ).forEach { (body, raw) ->
            val result = GenericCandidateExtractor.extract(body)
            assertTrue(result.any { it.kind == CandidateKind.TEXT && it.text.raw == raw })
            assertTrue(result.any { it.kind == CandidateKind.DATE_TIME && it.text.raw == "2026-10-05 19:40:25" })
        }
        val inline = GenericCandidateExtractor.extract("prefix ALPHA BETA at:2026-10-05 19:40:25; extra:GAMMA DELTA")
        assertTrue(inline.any { it.kind == CandidateKind.TEXT && it.text.raw == "ALPHA BETA" })
        assertTrue(inline.any { it.kind == CandidateKind.TEXT && it.text.raw == "GAMMA DELTA" })
    }

    @Test fun unsupportedSizeAndEmptyTextReturnNoCandidates() {
        assertTrue(GenericCandidateExtractor.extract("").isEmpty())
        assertTrue(GenericCandidateExtractor.extract(" ".repeat(4097)).isEmpty())
    }

    @Test fun humanCanBindGenericCandidatesBeforeTeachingAnyLayout() {
        val body = "صيغة غير معروفة\nقيمة:42SR\nجهة:متجر تجريبي\nوقت:14/10/26 12:21"
        val candidates = GenericCandidateExtractor.extract(body)
        // These choices simulate human selection; the extractor never supplies these semantic field names.
        val choices = listOf(Triple("amount", FieldKind.MONEY, "42SR"),
            Triple("counterparty", FieldKind.TEXT, "متجر تجريبي"),
            Triple("time", FieldKind.DATE_DMY_SHORT, "14/10/26 12:21"))
        val selections = choices.map { (field, kind, raw) ->
            val value = candidates.single { it.text.raw == raw }.text
            Selection(field, kind, value.start, value.end)
        }
        val template = ExampleTemplate.learn("User-selected sender", "USER_SELECTED_KIND", body, selections)
        val changed = body.replace("42SR", "98SAR").replace("متجر تجريبي", "جهة مختلفة")
        val preview = requireNotNull(template.preview("User-selected sender", changed))
        assertEquals("98SAR", preview.fields.getValue("amount").raw)
        assertEquals("جهة مختلفة", preview.fields.getValue("counterparty").raw)
    }

    @Test fun suppliedRawValuesAreOfferedWithoutTeachingOrSemanticInputs() {
        val inputPath = System.getenv("MASROOF_GENERIC_INPUT")
        val cases = if (inputPath == null) buildJsonArray {
            add(buildJsonObject {
                put("body", "Unseen message\nUnknown label:42SR\nOther:*AB12")
                putJsonArray("expected") {
                    add(buildJsonObject { put("kind", "MONEY"); put("raw", "42SR"); put("start", 29); put("end", 33) })
                    add(buildJsonObject { put("kind", "IDENTIFIER"); put("raw", "*AB12"); put("start", 40); put("end", 45) })
                }
            })
        } else Json.parseToJsonElement(File(inputPath).readText()).jsonArray
        var checkedValues = 0
        var missing = 0
        val rows = cases.mapIndexed { index, value ->
            val row = value.jsonObject
            val body = row.getValue("body").jsonPrimitive.content
            // No expected values, labels, sender or teaching examples reach extract().
            val candidates = GenericCandidateExtractor.extract(body)
            candidates.forEach { assertTrue(it.text.raw == body.substring(it.text.start, it.text.end)) }
            val absent = row.getValue("expected").jsonArray.count { expected ->
                val field = expected.jsonObject
                checkedValues++
                candidates.none { it.kind.name == field.getValue("kind").jsonPrimitive.content &&
                    it.text.raw == field.getValue("raw").jsonPrimitive.content &&
                    it.text.start == field.getValue("start").jsonPrimitive.int && it.text.end == field.getValue("end").jsonPrimitive.int }
            }
            missing += absent
            buildJsonObject {
                put("example", index + 1)
                put("candidateCount", candidates.size)
                put("missingExpectedValues", absent)
                putJsonArray("candidates") { candidates.forEach { candidate ->
                    add(buildJsonObject {
                        put("kind", candidate.kind.name); put("raw", candidate.text.raw)
                        put("start", candidate.text.start); put("end", candidate.text.end)
                    })
                } }
            }
        }
        val report = buildJsonObject {
            put("mode", "TEXT_ONLY_NO_TEACHING_OR_TEMPLATE")
            put("messages", cases.size); put("expectedValuesChecked", checkedValues); put("missingExpectedValues", missing)
            put("semanticRolesAssigned", 0); put("financialWrites", 0)
            put("candidatePrecisionMeasured", false)
            put("results", JsonArray(rows))
        }
        System.getenv("MASROOF_GENERIC_REPORT")?.let { path ->
            require(inputPath != null)
            File(path).writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), report))
        }
        assertEquals(0, missing, "Expected raw values missing; inspect private report without logging message data")
    }
}
