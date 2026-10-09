package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.FinancialContainerIdParser
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.model.isDebitCardSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord

data class CurrentAccountTransactionScope(
    val ownedContainerIds: Set<String>,
    val ownedAccountLast4s: Set<String>,
    val mode: AccountFlowScopeMode = AccountFlowScopeMode.Fleet,
    val ownedDebitCardContainerIds: Set<String> = emptySet(),
    val debitCardLinkedAccountIds: Map<String, String> = emptyMap(),
) {
    fun involvesOwnedSource(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
    ): Boolean {
        if (ownedContainerIds.isEmpty()) return true

        resolveOwnedAccountSourceId(tx, parsedRecordsById, rawSmsById)?.let { accountId ->
            return matchesOwnedContainer(accountId)
        }

        val sourceId = tx.sourceContainerId
        if (sourceId != null) {
            if (sourceId in ownedDebitCardContainerIds) {
                if (!isDebitCardAttributedTransaction(tx, parsedRecordsById, rawSmsById)) {
                    return false
                }
                debitCardLinkedAccountIds[sourceId]?.let { linkedAccountId ->
                    if (matchesOwnedContainer(linkedAccountId)) return true
                }
                return when (mode) {
                    AccountFlowScopeMode.Fleet -> tx.type in TRUSTED_OWNED_SOURCE_TYPES
                    AccountFlowScopeMode.SingleAccount -> false
                }
            }
            if (isCreditCardContainer(sourceId)) return false
            return matchesOwnedContainer(sourceId)
        }

        return when (mode) {
            AccountFlowScopeMode.Fleet -> tx.type in TRUSTED_OWNED_SOURCE_TYPES
            AccountFlowScopeMode.SingleAccount -> false
        }
    }

    fun involvesOwnedDestination(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
    ): Boolean {
        if (ownedContainerIds.isEmpty()) return true

        resolveOwnedDestinationAccountId(tx, parsedRecordsById, rawSmsById)?.let { accountId ->
            return matchesOwnedContainer(accountId)
        }

        val destId = tx.destinationContainerId
        if (destId != null) {
            if (isCreditCardContainer(destId)) return false
            return matchesOwnedContainer(destId)
        }

        return when (mode) {
            AccountFlowScopeMode.Fleet -> tx.type in TRUSTED_OWNED_DESTINATION_TYPES
            AccountFlowScopeMode.SingleAccount -> false
        }
    }

    /** Account debited for this expense, from linked SMS/events (ignores card-only [sourceContainerId]). */
    fun resolveOwnedAccountSourceId(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
    ): String? {
        for (record in linkedRecords(tx, parsedRecordsById)) {
            record.event.sourceAccountRef
                ?.let(FinancialContainerIdFactory::accountId)
                ?.let { return it }
            accountIdFromSmsBody(record, rawSmsById)?.let { return it }
        }
        return null
    }

    fun isCreditCardSourcedExpenseWithoutOwnedAccount(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
    ): Boolean {
        val sourceId = tx.sourceContainerId ?: return false
        if (!isCreditCardContainer(sourceId)) return false
        if (sourceId in ownedDebitCardContainerIds) {
            if (!isDebitCardAttributedTransaction(tx, parsedRecordsById, rawSmsById)) {
                return false
            }
            if (resolveOwnedAccountSourceId(tx, parsedRecordsById, rawSmsById) != null) {
                return false
            }
            if (debitCardLinkedAccountIds[sourceId] != null) {
                return false
            }
            return mode != AccountFlowScopeMode.Fleet
        }
        return resolveOwnedAccountSourceId(tx, parsedRecordsById, rawSmsById) == null
    }

    fun isBillPayment(
        tx: FinancialTransaction,
        billPaymentTxIds: Set<String>,
        parsedRecordsById: Map<String, ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
    ): Boolean {
        if (tx.type == FinancialTransactionType.BILL_PAYMENT) return true
        if (tx.id in billPaymentTxIds) return true
        return linkedRecords(tx, parsedRecordsById).any { record ->
            record.event.messageFamily == MessageFamily.BILL_PAYMENT ||
                smsBody(rawSmsById, record).containsBillPaymentWording()
        }
    }

    fun isCreditCardPayment(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
    ): Boolean {
        if (tx.type == FinancialTransactionType.CREDIT_CARD_PAYMENT) return true
        return linkedRecords(tx, parsedRecordsById).any { record ->
            record.event.messageFamily == MessageFamily.CARD_PAYMENT ||
                smsBody(rawSmsById, record).containsCreditCardPaymentWording()
        }
    }

    fun involvesOwnedAccount(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
    ): Boolean =
        involvesOwnedSource(tx, parsedRecordsById, rawSmsById) ||
            involvesOwnedDestination(tx, parsedRecordsById, rawSmsById)

    fun isCashWithdrawal(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
    ): Boolean {
        if (tx.type == FinancialTransactionType.CASH_WITHDRAWAL) return true
        return linkedRecords(tx, parsedRecordsById).any { record ->
            record.event.messageFamily == MessageFamily.WITHDRAWAL ||
                smsBody(rawSmsById, record).containsCashWithdrawalWording()
        }
    }

    fun isFinancingInstallment(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
    ): Boolean = LoanRepaymentAttribution.isLoanRepayment(tx, parsedRecordsById)

    private fun resolveOwnedDestinationAccountId(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
    ): String? {
        for (record in linkedRecords(tx, parsedRecordsById)) {
            record.event.destinationAccountRef
                ?.let(FinancialContainerIdFactory::accountId)
                ?.let { return it }
            accountIdFromDestinationSmsBody(record, rawSmsById)?.let { return it }
        }
        return null
    }

    private fun matchesOwnedContainer(containerId: String): Boolean {
        if (containerId in ownedContainerIds) return true
        val masked = FinancialContainerIdParser.accountMaskedNumber(containerId) ?: return false
        val bankId = FinancialContainerIdParser.accountBankId(containerId)
        if (bankId != null) {
            return ownedContainerIds.any { ownedId ->
                val ownedBank = FinancialContainerIdParser.accountBankId(ownedId) ?: return@any false
                val ownedMasked = FinancialContainerIdParser.accountMaskedNumber(ownedId) ?: return@any false
                ownedBank.equals(bankId, ignoreCase = true) && ownedMasked == masked
            }
        }
        val owners = ownedContainerIds.filter { ownedId ->
            val ownedMasked = FinancialContainerIdParser.accountMaskedNumber(ownedId) ?: return@filter false
            ownedMasked == masked ||
                (masked.length == 4 && ownedMasked.length > masked.length && ownedMasked.endsWith(masked))
        }
        return owners.size == 1
    }

    private fun accountIdFromSmsBody(
        record: ParsedEventRecord,
        rawSmsById: Map<String, RawSms>,
    ): String? {
        if (record.event.bank == Bank.UNKNOWN) return null
        val last4 = extractSourceAccountLast4(smsBody(rawSmsById, record)) ?: return null
        return FinancialContainerIdFactory.accountId(record.event.bank, last4)
    }

    private fun accountIdFromDestinationSmsBody(
        record: ParsedEventRecord,
        rawSmsById: Map<String, RawSms>,
    ): String? {
        if (record.event.bank == Bank.UNKNOWN) return null
        val last4 = extractDestinationAccountLast4(smsBody(rawSmsById, record)) ?: return null
        return FinancialContainerIdFactory.accountId(record.event.bank, last4)
    }

    private fun extractSourceAccountLast4(body: String): String? =
        SOURCE_ACCOUNT_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(body)?.groupValues?.getOrNull(1)
        }

    private fun extractDestinationAccountLast4(body: String): String? =
        DESTINATION_ACCOUNT_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(body)?.groupValues?.getOrNull(1)
        }

    private fun linkedRecords(
        tx: FinancialTransaction,
        parsedRecordsById: Map<String, ParsedEventRecord>,
    ): List<ParsedEventRecord> =
        tx.linkedParsedEventIds.mapNotNull { parsedRecordsById[it] }

    private fun smsBody(rawSmsById: Map<String, RawSms>, record: ParsedEventRecord): String =
        rawSmsById[record.event.rawSmsId]?.body.orEmpty()

    private fun String.containsBillPaymentWording(): Boolean =
        contains("سداد فاتورة") || contains("المفوتر:")

    private fun String.containsCreditCardPaymentWording(): Boolean =
        contains("سداد بطاقة") || contains("سداد بطاقه") ||
            (contains("تسديد") && (contains("بطاقة ائتمان") || contains("بطاقة إئتمان")))

    private fun String.containsCashWithdrawalWording(): Boolean =
        contains("سحب نقدي") || contains("سحب نقدى")

    companion object {
        fun isDebitCardAttributedTransaction(
            tx: FinancialTransaction,
            parsedRecordsById: Map<String, ParsedEventRecord>,
            rawSmsById: Map<String, RawSms>,
        ): Boolean =
            tx.linkedParsedEventIds.mapNotNull { parsedRecordsById[it] }.any { record ->
                if (!record.details.isDebitCardSms()) return@any false
                when (record.event.messageFamily) {
                    MessageFamily.PURCHASE,
                    MessageFamily.WITHDRAWAL,
                    -> true
                    else -> false
                }
            }

        private val SOURCE_ACCOUNT_PATTERNS = listOf(
            Regex("""خصمت\s*من\s*حساب\s*:\s*(\d{4})"""),
            Regex("""من\s*حساب\s*:\s*(\d{4})"""),
            Regex("""حساب\s*رقم\s*:\s*(\d{4})"""),
            Regex("""رقم\s*حساب\s*المرسل\s*:\s*(\d{4})"""),
            Regex("""(?<![\p{L}])حساب\s*:\s*(\d{4})"""),
        )

        private val DESTINATION_ACCOUNT_PATTERNS = listOf(
            Regex("""أودعت\s*(?:إلى|الى)\s*حساب\s*:\s*(\d{4})"""),
            Regex("""إلى\s*حساب\s*:\s*(\d{4})"""),
            Regex("""الى\s*حساب\s*:\s*(\d{4})"""),
            Regex("""إلى\s*:\s*(\d{4})"""),
            Regex("""الى\s*:\s*(\d{4})"""),
        )

        private val TRUSTED_OWNED_SOURCE_TYPES = setOf(
            FinancialTransactionType.CASH_WITHDRAWAL,
            FinancialTransactionType.CREDIT_CARD_PAYMENT,
            FinancialTransactionType.BILL_PAYMENT,
            FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            FinancialTransactionType.EXPENSE,
            FinancialTransactionType.FEE,
        )

        private val TRUSTED_OWNED_DESTINATION_TYPES = setOf(
            FinancialTransactionType.INCOME,
            FinancialTransactionType.EXTERNAL_TRANSFER_IN,
            FinancialTransactionType.REFUND,
        )

        fun ownedAccountLast4sFromMaskedNumbers(maskedNumbers: Collection<String>): Set<String> =
            maskedNumbers
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .flatMap { masked ->
                    if (masked.length <= 4) {
                        listOf(masked)
                    } else {
                        listOf(masked, masked.takeLast(4))
                    }
                }
                .toSet()

        private fun isCreditCardContainer(containerId: String?): Boolean =
            containerId?.startsWith("card:") == true
    }
}
