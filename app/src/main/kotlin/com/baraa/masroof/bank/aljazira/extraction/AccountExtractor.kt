package com.baraa.masroof.bank.aljazira.extraction

import com.baraa.masroof.parsing.normalizer.comparisonRegex
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.parsing.model.NormalizedSms

/** Role/suffix evidence before bank scoping. */
data class AccountSuffixEvidence(
    val sourceLast4: String? = null,
    val destinationLast4: String? = null,
)

data class AccountExtraction(
    val source: AccountReference? = null,
    val destination: AccountReference? = null,
)

/**
 * Extracts source/destination suffixes, then assigns bank scope from parse context.
 *
 * Does not resolve ownership. [BankNetworkType] only distinguishes local vs
 * unidentified external bank references for INTER_BANK transfers.
 */
class AccountExtractor {
    fun extractSuffixes(sms: NormalizedSms, family: MessageFamily? = null): AccountSuffixEvidence {
        val text = sms.comparisonBody
        var sourceLast4: String? = null
        var destinationLast4: String? = null

        val sourcePatterns = when (family) {
            // Incoming inter-bank SMS may mention the sender's deducted account — not our source.
            // Generic "حساب:" also appears on the destination line ("أودعت إلى حساب:").
            MessageFamily.TRANSFER_IN -> TRANSFER_IN_SOURCE_PATTERNS
            else -> SOURCE_PATTERNS
        }

        for (pattern in sourcePatterns) {
            val match = pattern.find(text) ?: continue
            sourceLast4 = match.groupValues[1]
            break
        }

        for (pattern in DESTINATION_PATTERNS) {
            val match = pattern.find(text) ?: continue
            destinationLast4 = match.groupValues[1]
            break
        }

        return AccountSuffixEvidence(
            sourceLast4 = sourceLast4,
            destinationLast4 = destinationLast4,
        )
    }

    /**
     * @param localBank Bank AlJazira (the parser's bank)
     */
    fun toReferences(
        suffixes: AccountSuffixEvidence,
        localBank: Bank,
        family: MessageFamily,
        networkType: BankNetworkType?,
    ): AccountExtraction {
        val sourceBank = bankForSource(localBank, family, networkType)
        val destinationBank = bankForDestination(localBank, family, networkType)
        return AccountExtraction(
            source = suffixes.sourceLast4?.let { AccountReference(sourceBank, it) },
            destination = suffixes.destinationLast4?.let { AccountReference(destinationBank, it) },
        )
    }

    fun extract(
        sms: NormalizedSms,
        localBank: Bank,
        family: MessageFamily,
        networkType: BankNetworkType?,
    ): AccountExtraction =
        toReferences(extractSuffixes(sms, family), localBank, family, networkType)

    private fun bankForSource(
        localBank: Bank,
        family: MessageFamily,
        networkType: BankNetworkType?,
    ): Bank =
        when {
            family == MessageFamily.TRANSFER_IN && networkType == BankNetworkType.INTER_BANK ->
                Bank.UNKNOWN
            else -> localBank
        }

    private fun bankForDestination(
        localBank: Bank,
        family: MessageFamily,
        networkType: BankNetworkType?,
    ): Bank =
        when {
            family == MessageFamily.TRANSFER_OUT && networkType == BankNetworkType.INTER_BANK ->
                Bank.UNKNOWN
            else -> localBank
        }

    companion object {
        private val DEDUCTED_FROM_ACCOUNT_PATTERN =
            comparisonRegex("""خصمت\s*من\s*حساب\s*:\s*(\d{4})""")

        private val SOURCE_PATTERNS = listOf(
            DEDUCTED_FROM_ACCOUNT_PATTERN,
            comparisonRegex("""من\s*حساب\s*:\s*(\d{4})"""),
            comparisonRegex("""حساب\s*رقم\s*:\s*(\d{4})"""),
            comparisonRegex("""رقم\s*حساب\s*المرسل\s*:\s*(\d{4})"""),
            comparisonRegex("""(?<![\p{L}])حساب\s*:\s*(\d{4})"""),
            comparisonRegex("""(?:^|\n)\s*من\s*:\s*(\d{4})"""),
        )

        private val TRANSFER_IN_SOURCE_PATTERNS = listOf(
            comparisonRegex("""رقم\s*حساب\s*المرسل\s*:\s*(\d{4})"""),
            comparisonRegex("""(?:^|\n)\s*من\s*:\s*(\d{4})"""),
        )

        private val DESTINATION_PATTERNS = listOf(
            comparisonRegex("""أودعت\s*(?:إلى|الى)\s*حساب\s*:\s*(\d{4})"""),
            comparisonRegex("""المعرف\s*البديل\s*\\?\s*الايبان\s*:\s*(\d{4})"""),
            comparisonRegex("""الى\s*حساب(?:ك)?(?:\s*الجاري)?\s*:\s*(\d{4})"""),
            comparisonRegex("""إلى\s*:\s*(\d{4})"""),
            comparisonRegex("""الى\s*:\s*(\d{4})"""),
            comparisonRegex("""إلى\s*حساب\s*:\s*(\d{4})"""),
        )
    }
}
