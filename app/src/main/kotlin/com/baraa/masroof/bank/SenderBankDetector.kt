package com.baraa.masroof.bank

import com.baraa.masroof.core.text.ArabicTextFolding
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.parsing.detector.BankDetector
import com.baraa.masroof.parsing.model.BankDetectionResult

/**
 * Conservative sender detection for one bank, built from its configured senders.
 *
 * [BankDetectionResult.Detected] is a sender equal to a configured one after normalization
 * (case, spacing, punctuation, Arabic letter forms, promotional -AD suffix). A sender containing a long
 * sender name, or a body naming the bank, is only [BankDetectionResult.Suspected]: stored and reviewed, never parsed.
 */
class SenderBankDetector(
    private val bank: Bank,
    senders: Collection<String>,
    displayName: String? = null,
) : BankDetector {
    private val exact = senders.map(::senderKey).filter { it.isNotEmpty() }.toSet()
    private val senderStems = exact.filter { it.length >= MIN_SENDER_STEM }
    // A real bank SMS from a new sender usually names the bank; a long name keeps short ones (SAB) from matching words.
    private val bodyNames = (exact + listOfNotNull(displayName?.let(::senderKey))).filter { it.length >= MIN_BODY_NAME }.toSet()

    override fun detect(sender: String, body: String): BankDetectionResult {
        val normalized = normalizeSender(sender)
        if (normalized in exact) {
            return BankDetectionResult.Detected(bank, Confidence(1.0, listOf("exact_sender:$normalized")), listOf("sender:$sender"))
        }
        if (senderStems.any { normalized.contains(it) } || senderKey(body).let { text -> bodyNames.any { text.contains(it) } }) {
            return BankDetectionResult.Suspected(evidence = listOf("sender:$sender"), reasons = listOf("suspected_bank_sender:${bank.id}"))
        }
        return BankDetectionResult.Unknown(reasons = listOf("sender_not_in_scope"))
    }

    private fun normalizeSender(sender: String): String {
        val key = senderKey(sender)
        if (key in exact) return key
        // A promotional suffix (BankName-AD) still names the bank, but only when the rest is a known sender.
        return key.removeSuffix("ad").takeIf { key.endsWith("ad") && it in exact } ?: key
    }

    companion object {
        private const val MIN_SENDER_STEM = 6
        private const val MIN_BODY_NAME = 9
        private val NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{Nd}]")

        /** Whether two configured senders would detect the same incoming sender, including its -AD form. */
        fun sameSender(first: String, second: String): Boolean {
            val a = senderKey(first)
            val b = senderKey(second)
            return a == b || a == b + "ad" || b == a + "ad"
        }

        /** Two senders with the same key are the same sender. Sender labels also fold ة to ه (بنك الجزيرة / بنك الجزيره). */
        fun senderKey(value: String): String =
            ArabicTextFolding.foldForComparison(value).replace('ة', 'ه').replace(NOT_LETTER_OR_DIGIT, "")
    }
}
