package com.baraa.masroof.bank

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.testsupport.ALJAZIRA_BUNDLED_SENDERS
import com.baraa.masroof.testsupport.aljaziraSenderDetector
import com.baraa.masroof.testsupport.bundledPatterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderBankDetectorTest {
    private val aljazira = aljaziraSenderDetector()

    @Test
    fun sharedTestCopy_matchesTheBundledSenders() {
        val bank = bundledPatterns("BANK_ALJAZIRA").banks.single()
        assertEquals(bank.senders, ALJAZIRA_BUNDLED_SENDERS)
    }

    @Test
    fun aljaziraSenderForms_areDetected() {
        listOf(
            "AlJazira", "ALJAZIRA", "Al-Jazira", "Al Jazira", "AlJazira-AD", "Al-Jazira-AD", "Jazira Bank", "Jazira-Bank",
            "Jazira Bank.", "Bank AlJazira", "Bank Al Jazira", "AlJaziraBank", "Al Jazira Bank", "Al-Jazira-Bank",
            "AlJaziraBank-AD", "Jazira Bank-AD", "Jazira Bank", "بنك الجزيرة", "بنك الجزيره",
        ).forEach { sender -> assertDetected(aljazira, Bank.BANK_ALJAZIRA, sender) }
    }

    @Test
    fun otherBanks_getTheSameSenderNormalization() {
        val barq = SenderBankDetector(Bank("BANK_BARQ"), listOf("barq app"))
        listOf("barq app", "Barq App", "BARQ-APP", "Barq App-AD").forEach { assertDetected(barq, Bank("BANK_BARQ"), it) }
        val snb = SenderBankDetector(Bank("BANK_SNB"), listOf("SNB-AlAhli"))
        listOf("SNB-AlAhli", "snb-alahli", "SNB AlAhli", "SNB-AlAhli-AD").forEach { assertDetected(snb, Bank("BANK_SNB"), it) }
        // A short sender is never a stem, so unrelated senders that contain it stay unknown.
        val sab = SenderBankDetector(Bank("BANK_SAB"), listOf("SAB"))
        assertTrue(sab.detect("HASABAT", "hello") is BankDetectionResult.Unknown)
    }

    @Test
    fun nearMissSenders_areNotDetected() {
        listOf("JaziraNews", "OtherBank", "MyJaziraService", "jazira", "AD").forEach { sender ->
            assertTrue("$sender should be Unknown", aljazira.detect(sender, "شراء بمبلغ: 10.00 SAR") is BankDetectionResult.Unknown)
        }
    }

    @Test
    fun plausibleSenderOrBody_isSuspectedWithoutClaimingTheBank() {
        listOf("NotAlJazira", "AlJaziraX", "AlJaziraBanks", "NotAlJaziraBank").forEach { sender ->
            assertTrue("$sender should be Suspected", aljazira.detect(sender, "hello") is BankDetectionResult.Suspected)
        }
        assertTrue(aljazira.detect("Mom", "تحويل عبر بنك الجزيرة بمبلغ 10") is BankDetectionResult.Suspected)
        assertTrue(aljazira.detect("Mom", "Paid with Bank AlJazira card") is BankDetectionResult.Suspected)
        assertTrue(aljazira.detect("Mom", "See you at 6") is BankDetectionResult.Unknown)
        val alinma = SenderBankDetector(Bank("BANK_ALINMA"), listOf("alinma"), "مصرف الإنماء")
        assertTrue(alinma.detect("Mom", "حوالة من مصرف الانماء") is BankDetectionResult.Suspected)
        // A short name never matches inside ordinary words.
        val sab = SenderBankDetector(Bank("BANK_SAB"), listOf("SAB"), "ساب")
        assertTrue(sab.detect("Mom", "hasab sabah") is BankDetectionResult.Unknown)
    }

    @Test
    fun sameSender_followsDetection() {
        assertTrue(SenderBankDetector.sameSender("alinma", "ALINMA"))
        assertTrue(SenderBankDetector.sameSender("alinma", "Alinma-AD"))
        assertTrue(!SenderBankDetector.sameSender("alinma", "Alinma Pay"))
    }

    @Test
    fun anExactSenderIsNeverAlsoReadAsAnotherBanksAdSuffix() {
        val short = SenderBankDetector(Bank("SHORT"), listOf("Riy"))
        val full = SenderBankDetector(Bank("FULL"), listOf("Riyad"))
        assertDetected(full, Bank("FULL"), "Riyad")
        assertDetected(short, Bank("SHORT"), "Riy-AD")
        assertTrue(SenderBankDetector(Bank("BOTH"), listOf("Riy", "Riyad")).detect("Riyad", "") is BankDetectionResult.Detected)
    }

    private fun assertDetected(detector: SenderBankDetector, bank: Bank, sender: String) {
        val result = detector.detect(sender, "body")
        assertTrue("$sender should be Detected", result is BankDetectionResult.Detected)
        assertEquals(bank, (result as BankDetectionResult.Detected).bank)
    }
}
