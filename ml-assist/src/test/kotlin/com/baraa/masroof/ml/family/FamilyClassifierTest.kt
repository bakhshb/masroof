package com.baraa.masroof.ml.family

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FamilyClassifierTest {
    private val f = requireNotNull(FamilyInputCleaner.clean("سداد تمويل خاص", listOf("من", "القسط")).features)
    private fun label(id: String = "one", family: SmsFamilyLabel = SmsFamilyLabel.FINANCING_INSTALLMENT, at: Long = 1) =
        HumanFamilyLabel("BANK_TEST", id, "decision-$id-$at", family, f.template, f.terms, at, "USER_MESSAGE_FAMILY")

    @Test fun aBlockRevokesTheTemplateAndOnlyANewerExplicitDecisionClearsIt() {
        val block = HumanTemplateBlock("BANK_TEST", "one", "edit", f.template, 2)
        val blocked = FamilyClassifier("BANK_TEST", listOf(label()), listOf(block))
        assertNull(blocked.suggest("BANK_TEST", f))
        assertTrue(blocked.latestLabels.isEmpty())
        assertEquals(SmsFamilyLabel.FINANCING_INSTALLMENT,
            FamilyClassifier("BANK_TEST", listOf(label(at = 3)), listOf(block)).suggest("BANK_TEST", f)!!.family)
        assertNotNull(FamilyClassifier("BANK_TEST", listOf(label()), listOf(block.copy(bankId = "OTHER_BANK"))).suggest("BANK_TEST", f))
    }

    @Test fun canSuggestMatchesWhatSuggestReturns() {
        assertFalse(FamilyClassifier("BANK_TEST").canSuggest)
        assertTrue(FamilyClassifier("BANK_TEST", listOf(label())).canSuggest)
        assertFalse(FamilyClassifier("BANK_TEST", listOf(label(family = SmsFamilyLabel.OTP))).canSuggest)
        assertTrue(FamilyClassifier("BANK_TEST", listOf(label(family = SmsFamilyLabel.NON_FINANCIAL).copy(provenance = "USER_NON_FINANCIAL"))).canSuggest)
        assertFalse(FamilyClassifier("BANK_TEST", listOf(label(), label("two", SmsFamilyLabel.FEE))).canSuggest)
        assertFalse(FamilyClassifier("BANK_TEST", listOf(label()), listOf(HumanTemplateBlock("BANK_TEST", "x", "b", f.template, 2))).canSuggest)
        assertTrue(FamilyClassifier("BANK_TEST", listOf(label()), listOf(HumanTemplateBlock("OTHER_BANK", "x", "b", f.template, 2))).canSuggest)
    }

    @Test fun withoutHumanDecisionsNothingIsSuggested() {
        val empty = FamilyClassifier("BANK_TEST")
        assertNull(empty.suggest("BANK_TEST", f))
        assertEquals(FamilyClassifier.VERSION, empty.version)
    }

    @Test fun conflictingTemplatesProposeNothing() {
        assertNull(FamilyClassifier("BANK_TEST", listOf(label(), label("two", SmsFamilyLabel.FEE))).suggest("BANK_TEST", f))
    }

    @Test fun repeatedMessagesShareOneTemplateAndNeverLeakAcrossBanks() {
        val c = FamilyClassifier("BANK_TEST", listOf(label(), label("two"), label("three")))
        assertEquals(FamilyPrediction(SmsFamilyLabel.FINANCING_INSTALLMENT, c.version), c.suggest("BANK_TEST", f))
        assertNull(c.suggest("OTHER_BANK", f))
        assertNull(FamilyClassifier("OTHER_BANK", listOf(label())).suggest("OTHER_BANK", f))
    }

    @Test fun aLaterNotFinancialDecisionReplacesTheEarlierFamilyAndIsRemembered() {
        val ignored = label(family = SmsFamilyLabel.NON_FINANCIAL, at = 2).copy(provenance = "USER_NON_FINANCIAL")
        val c = FamilyClassifier("BANK_TEST", listOf(label(), ignored))
        assertEquals(listOf(ignored), c.latestLabels)
        assertEquals(SmsFamilyLabel.NON_FINANCIAL, c.suggest("BANK_TEST", f)?.family)
        // The same wording decided both ways on two messages stays undecided.
        assertNull(FamilyClassifier("BANK_TEST", listOf(label(), label("two", SmsFamilyLabel.NON_FINANCIAL))).suggest("BANK_TEST", f))
    }

    @Test fun versionChangesWithDecisionsSoStaleInferencesRefresh() {
        val one = FamilyClassifier("BANK_TEST", listOf(label()))
        assertNotEquals(FamilyClassifier.VERSION, one.version)
        assertEquals(one.version, FamilyClassifier("BANK_TEST", one.latestLabels, one.latestBlocks).version)
        assertNotEquals(one.version, FamilyClassifier("BANK_TEST", listOf(label(at = 5))).version)
    }

    @Test fun modelPredictionsAndMalformedLabelsAreNeverTrainingTruth() {
        assertThrows(IllegalArgumentException::class.java) { label().copy(provenance = "MODEL_SUGGESTED") }
        assertThrows(IllegalArgumentException::class.java) { label().copy(family = SmsFamilyLabel.UNKNOWN) }
        assertThrows(IllegalArgumentException::class.java) { label().copy(terms = mapOf("w:sarah" to 1)) }
        assertThrows(IllegalArgumentException::class.java) { label().copy(template = "old-incompatible-template") }
    }
}
