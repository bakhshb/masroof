package com.baraa.masroof.bank

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.engine.bank.patterns.SenderIdentity
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
    private val exact = senders.map(SenderIdentity::key).filter { it.isNotEmpty() }.toSet()
    private val senderStems = exact.filter { it.length >= MIN_SENDER_STEM }
    // A real bank SMS from a new sender usually names the bank; a long name keeps short ones (SAB) from matching words.
    private val bodyNames = (exact + listOfNotNull(displayName?.let(SenderIdentity::key)))
        .filter { it.length >= MIN_BODY_NAME && !it.all(Char::isDigit) }.toSet()

    override fun detect(sender: String, body: String): BankDetectionResult {
        val key = SenderIdentity.key(sender)
        val matched = key.takeIf { it in exact } ?: SenderIdentity.promotionalKey(sender)?.takeIf { it in exact }
        if (matched != null) {
            return BankDetectionResult.Detected(bank, Confidence(1.0, listOf("exact_sender:$matched")), listOf("sender:$sender"))
        }
        if (senderStems.any { key.contains(it) } ||
            bodyNames.isNotEmpty() && SenderIdentity.key(body).let { text -> bodyNames.any { text.contains(it) } }) {
            return BankDetectionResult.Suspected(evidence = listOf("sender:$sender"), reasons = listOf("suspected_bank_sender:${bank.id}"))
        }
        return BankDetectionResult.Unknown(reasons = listOf("sender_not_in_scope"))
    }

    private companion object {
        const val MIN_SENDER_STEM = 6
        const val MIN_BODY_NAME = 9
    }
}
