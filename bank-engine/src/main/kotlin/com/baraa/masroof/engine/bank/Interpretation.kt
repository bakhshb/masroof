package com.baraa.masroof.engine.bank

import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.finance.value.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Durable captured message reference; engines do not open databases or the device inbox. */
data class MessageInput(val evidenceId: String, val sender: String, val body: String, val receivedAt: Instant,
    val durableSmsReceipt: Boolean = false) {
    init { require(evidenceId.isNotBlank()) }
}

enum class MessageFamily {
    PURCHASE, TRANSFER_IN, TRANSFER_OUT, CARD_PAYMENT, BILL_PAYMENT, FINANCING_INSTALLMENT,
    WITHDRAWAL, REFUND, FEE, BALANCE_NOTICE, OTP, NON_FINANCIAL, UNKNOWN,
}
enum class MoneyDirection { INCOMING, OUTGOING, NEUTRAL, UNKNOWN }
enum class CardChannel { DEBIT, CREDIT, STATEMENT }
enum class PurchaseChannel { POS, ONLINE, UNKNOWN }
enum class BankNetwork { INTRA_BANK, INTER_BANK, UNKNOWN }
enum class LoanType { PERSONAL, AUTO, MORTGAGE }
enum class ParseStatus { SUCCESS, PARTIAL, REVIEW_REQUIRED, NON_FINANCIAL, UNSUPPORTED, INVALID }
data class AccountReference(val bank: BankId?, val maskedNumber: String?)
data class CardReference(val bank: BankId?, val last4: String?)
data class Confidence(val score: Double, val reasons: List<String>) {
    init { require(score in 0.0..1.0) }
}

/** Extracted observations only: no asset ownership, transaction classification or ledger IDs. */
data class Facts(
    val family: MessageFamily,
    val amount: Money? = null,
    val direction: MoneyDirection? = null,
    val sourceAccount: AccountReference? = null,
    val destinationAccount: AccountReference? = null,
    val card: CardReference? = null,
    val debitSourceAccountLast4: String? = null,
    val cardChannel: CardChannel? = null,
    val purchaseChannel: PurchaseChannel? = null,
    val bankNetwork: BankNetwork? = null,
    val transactionReference: String? = null,
    val merchant: String? = null,
    val counterparty: String? = null,
    val occurredAtLocal: LocalDateTime? = null,
    val occurredAt: Instant? = null,
    val availableBalance: Money? = null,
    val outstandingBalance: Money? = null,
    val paymentDueDate: LocalDate? = null,
    val exchangeRate: BigDecimal? = null,
    val internationalFee: Money? = null,
    val labeledForeignAmount: Money? = null,
    val biller: String? = null,
    val billerCode: String? = null,
    val loanType: LoanType? = null,
    val salaryIncomeWording: Boolean? = null,
    val executionState: String? = null,
)

/** Half-open offsets into the ORIGINAL input body, never folded comparison text. */
data class Evidence(val ruleId: String, val field: String, val start: Int, val end: Int) {
    init { require(ruleId.isNotBlank() && field.isNotBlank() && start >= 0 && end > start) }
    fun slice(input: MessageInput): String = input.body.substring(start, end)
}

enum class Blocker { UNKNOWN_BANK, AMBIGUOUS_BANK, UNKNOWN_FAMILY, CONFLICTING_FACTS, VALIDATION_ERROR, UNSUPPORTED_RULE_SCHEMA }

enum class DiagnosticSeverity { INFO, WARNING, ERROR }
data class InterpretationDiagnostic(val code: String, val message: String, val severity: DiagnosticSeverity)

data class InterpretationVersion(val contract: Int, val engine: String, val rules: String) {
    init { require(contract > 0 && engine.isNotBlank() && rules.isNotBlank()) }
    fun isSupported(): Boolean = contract == CURRENT_CONTRACT
    companion object { const val CURRENT_CONTRACT: Int = 1 }
}

/** Version must be checked before mapping. Empty blockers is not financial posting authority. */
data class Interpretation(
    val evidenceId: String,
    val version: InterpretationVersion,
    val bank: BankId?,
    val facts: Facts,
    val status: ParseStatus,
    val confidence: Confidence?,
    val evidence: List<Evidence>,
    val blockers: Set<Blocker>,
    val diagnostics: List<InterpretationDiagnostic> = emptyList(),
    val familyInference: com.baraa.masroof.engine.bank.interpretation.FamilySuggestion? = null,
    val patternProvenance: com.baraa.masroof.engine.bank.patterns.PatternProvenance? = null,
) {
    init {
        require(evidenceId.isNotBlank())
        require(bank != null || blockers.any { it == Blocker.UNKNOWN_BANK || it == Blocker.AMBIGUOUS_BANK })
        require(facts.family != MessageFamily.UNKNOWN || Blocker.UNKNOWN_FAMILY in blockers)
    }
}
