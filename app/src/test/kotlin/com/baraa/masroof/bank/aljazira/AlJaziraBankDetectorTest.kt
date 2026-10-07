package com.baraa.masroof.bank.aljazira

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.parsing.model.BankDetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlJaziraBankDetectorTest {
    private val detector = AlJaziraBankDetector()

    @Test
    fun exactAndNormalizedSenders_areDetected() {
        listOf(
            "AlJazira",
            "ALJAZIRA",
            "Al-Jazira",
            "Al Jazira",
            "AlJazira-AD",
            "Al-Jazira-AD",
            "Jazira Bank",
            "Jazira-Bank",
            "Jazira Bank.",
            "Bank AlJazira",
            "Bank Al Jazira",
            "AlJaziraBank",
            "Al Jazira Bank",
            "Al-Jazira-Bank",
            "AlJaziraBank-AD",
            "Jazira Bank-AD",
            "Jazira\u00a0Bank",
            "بنك الجزيرة",
        ).forEach { sender ->
            val result = detector.detect(sender, "body")
            assertTrue("$sender should be Detected", result is BankDetectionResult.Detected)
            assertEquals(Bank.BANK_ALJAZIRA, (result as BankDetectionResult.Detected).bank)
        }
    }

    @Test
    fun jaziraBankSender_normalizesToAllowedForm() {
        assertEquals("jazirabank", AlJaziraBankDetector.normalizeSender("Jazira Bank"))
        assertEquals("jazirabank", AlJaziraBankDetector.normalizeSender("Jazira Bank."))
        assertEquals("aljazirabank", AlJaziraBankDetector.normalizeSender("AlJaziraBank"))
        assertEquals("aljazirabank", AlJaziraBankDetector.normalizeSender("AlJaziraBank-AD"))
    }

    @Test
    fun nearMissSenders_areNotDetected() {
        listOf("JaziraNews", "OtherBank", "MyJaziraService", "jazira").forEach { sender ->
            val result = detector.detect(sender, "شراء بمبلغ: 10.00 SAR")
            assertTrue("$sender should be Unknown", result is BankDetectionResult.Unknown)
        }
    }

    @Test
    fun plausibleSenderOrBody_isSuspectedWithoutClaimingTheBank() {
        listOf("NotAlJazira", "AlJaziraX", "AlJaziraBanks", "NotAlJaziraBank").forEach { sender ->
            val result = detector.detect(sender, "hello")
            assertTrue("$sender should be Suspected", result is BankDetectionResult.Suspected)
        }
        val fromBody = detector.detect("Mom", "تحويل عبر بنك الجزيرة بمبلغ 10")
        assertTrue(fromBody is BankDetectionResult.Suspected)
        val ordinary = detector.detect("Mom", "See you at 6")
        assertTrue(ordinary is BankDetectionResult.Unknown)
    }
}
