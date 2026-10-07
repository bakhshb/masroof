package com.baraa.masroof.bank.aljazira

import com.baraa.masroof.core.text.ArabicTextFolding
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.parsing.detector.BankDetector
import com.baraa.masroof.parsing.model.BankDetectionResult
import java.util.Locale

/**
 * Conservative Bank AlJazira detection via normalized sender match.
 *
 * A positive [BankDetectionResult.Detected] is an exact allowlist hit after
 * normalization (spacing, hyphens, punctuation, promotional -AD suffix, Arabic
 * labels). A plausible sender stem or body phrase is [BankDetectionResult.Suspected]
 * and does not claim the bank.
 */
class AlJaziraBankDetector : BankDetector {
    override fun detect(sender: String, body: String): BankDetectionResult {
        val normalizedSender = normalizeSender(sender)
        if (normalizedSender in EXACT_SENDERS || normalizedSender in ARABIC_SENDERS) {
            return BankDetectionResult.Detected(
                bank = Bank.BANK_ALJAZIRA,
                confidence = Confidence(
                    score = 1.0,
                    reasons = listOf("exact_sender:$normalizedSender"),
                ),
                evidence = listOf("sender:$sender"),
            )
        }
        if (isPlausibleSender(sender, normalizedSender) || bodySuggestsAlJazira(body)) {
            return BankDetectionResult.Suspected(
                evidence = listOf("sender:$sender"),
                reasons = listOf("suspected_aljazira_sender"),
            )
        }
        return BankDetectionResult.Unknown(
            reasons = listOf("sender_not_recognized_as_bank_aljazira"),
        )
    }

    internal companion object {
        /** Latin sender forms observed on devices (after normalization). */
        val EXACT_SENDERS = setOf(
            "aljazira",
            "jazirabank",
            "bankaljazira",
            "aljazirabank",
        )

        /** Arabic sender labels (after normalization). */
        val ARABIC_SENDERS = setOf(
            "بنكالجزيرة",
            "بنكالجزيره",
        )

        internal fun normalizeSender(sender: String): String {
            val cleaned = sender
                .trim()
                .replace(Regex("[\\p{Cf}\\p{Mn}]"), "")

            val arabicKey = normalizeArabicSender(cleaned)
            if (arabicKey in ARABIC_SENDERS) {
                return arabicKey
            }

            var latin = cleaned
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]"), "")

            if (latin.endsWith("ad") && latin.length > 2) {
                val withoutPromoSuffix = latin.removeSuffix("ad")
                if (withoutPromoSuffix in EXACT_SENDERS) {
                    latin = withoutPromoSuffix
                }
            }
            return latin
        }

        private fun isPlausibleSender(rawSender: String, normalizedSender: String): Boolean {
            if (normalizedSender.contains("aljazira")) return true
            val arabic = normalizeArabicSender(rawSender)
            return arabic.contains("بنكالجزيرة") || arabic.contains("بنكالجزيره")
        }

        private fun bodySuggestsAlJazira(body: String): Boolean {
            val comparison = ArabicTextFolding.foldForComparison(body)
            return comparison.contains("بنك الجزيرة") ||
                comparison.contains("بنك الجزيره") ||
                comparison.contains("bank aljazira") ||
                comparison.contains("aljazira bank")
        }

        private fun normalizeArabicSender(sender: String): String =
            sender
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ى', 'ي')
                .replace('ة', 'ه')
                .replace(Regex("[\\s\\-_.]+"), "")
    }
}
