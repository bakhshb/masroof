package com.baraa.masroof.application.patterns

import com.baraa.masroof.engine.bank.MessageFamily
import com.baraa.masroof.engine.bank.MoneyDirection
import com.baraa.masroof.engine.bank.patterns.*
import org.junit.Assert.*
import org.junit.Test

class PatternCatalogTest {
    @Test fun builtInRulesShowPhrasesAndFieldWordsAndApproveOnlyMovements() {
        val catalog = com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec.decode(
            java.io.File("src/main/assets/bank-patterns/BANK_BARQ.patterns.json").readText()).toCatalog()
        val patterns = catalog.banks.single().currentPatterns.associateBy { it.id }
        val incoming = patterns.getValue("barq.transfer-in")
        assertTrue("حوالة داخلية واردة" in incoming.recognitionPhrases)
        assertEquals(listOf("من حساب", "حساب المرسل", "from a/c"), incoming.fieldLabels["source_account"])
        assertTrue(incoming.fields.isEmpty())
        assertTrue(incoming.canApprove)
        assertFalse(incoming.canEdit)
        assertTrue(patterns.getValue("barq.fee").canApprove)
        assertTrue(patterns.getValue("barq.installment").canApprove)
        assertFalse(patterns.getValue("barq.notice").canApprove)
    }

    @Test fun displayChoicesKeepRevisionsAndApprovalSeparateFromMatching() {
        val body = "Transfer:10SAR From:123 To:456"
        val pattern = SavedMessagePattern.fromChoices("transfer", "BANK_TEST", "TEST", "TRANSFER_OUT", body,
            listOf(FieldChoice("amount", 9, 14), FieldChoice("account_reference", 20, 23),
                FieldChoice("destination_account", 27, 30)))
        val approved = pattern.copy(revision = 2,
            factContract = PatternFactContract(MessageFamily.TRANSFER_OUT, MoneyDirection.OUTGOING))
        fun view(patterns: List<SavedMessagePattern>) = MessagePatternConfiguration(7,
            listOf(BankMessagePatterns("BANK_TEST", listOf("TEST"), patterns = patterns)))
            .toCatalog().banks.single().currentPatterns.single()

        val preview = view(listOf(pattern))
        assertEquals("Transfer:{amount} From:{account_reference} To:{destination_account}", preview.messageTemplate)
        assertEquals("TEST", preview.sender)
        assertEquals(PatternApprovalStatus.PREVIEW_ONLY, preview.status)
        assertFalse(preview.canApply)
        assertEquals("Transfer:… From:… To:…", preview.example)
        assertEquals(listOf("account_reference"), preview.accountReferenceFields(PatternOperation.TRANSFER_OUT))
        assertEquals(listOf("account_reference", "destination_account"), preview.accountReferenceFields(PatternOperation.TRANSFER_IN))
        val current = view(listOf(pattern, approved))
        assertEquals(listOf(1), current.priorRevisions)
        assertTrue(current.canApprove)
        assertTrue(current.canApply)
        assertFalse(view(listOf(approved.copy(revision = 3, enabled = false))).canApply)
    }

    @Test fun previewKeepsTheFullTemplateAndHidesIgnoredSlots() {
        val prefix = "Transfer " + "context ".repeat(40)
        val body = "${prefix}10SAR ref 123 end"
        val pattern = SavedMessagePattern.fromChoices("long", "BANK_TEST", "TEST", "TRANSFER_OUT", body,
            listOf(FieldChoice("amount", prefix.length, prefix.length + 5),
                FieldChoice("ignored_1", body.indexOf("123"), body.indexOf("123") + 3, ignored = true)))
        val view = MessagePatternConfiguration(1, listOf(BankMessagePatterns("BANK_TEST", listOf("TEST"), patterns = listOf(pattern))))
            .toCatalog().banks.single().currentPatterns.single()

        assertEquals(240, view.example.length)
        assertEquals("${prefix}{amount} ref … end", view.messageTemplate)
        assertEquals(listOf("amount"), view.fields)
        assertEquals(1, pattern.revision)
        assertNull(pattern.factContract)
    }
}
