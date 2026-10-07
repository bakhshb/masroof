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
        listOf(
            "JaziraNews",
            "NotAlJazira",
            "OtherBank",
            "MyJaziraService",
            "jazira",
            "AlJaziraBanks",
            "NotAlJaziraBank",
        ).forEach { sender ->
            val result = detector.detect(sender, "شراء بمبلغ: 10.00 SAR")
            assertTrue("$sender should be Unknown", result is BankDetectionResult.Unknown)
        }
    }
}
