package com.baraa.masroof.presentation.review

import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.rules.InformationalMessagePolicy

data class ReviewListItemUi(
    val id: String,
    val kind: ReviewKind,
    val kindLabelRes: Int,
    val title: String,
    val smsBody: String,
    val amountLabel: String?,
    val dateLabel: String,
    val reasonLabel: String,
    val dismissibleAsNonFinancial: Boolean = false,
    val ignored: Boolean = false,
)

data class ReviewBankChoiceUi(
    val bankId: String,
    val labelRes: Int?,
)

data class ReviewDetailUi(
    val id: String,
    val rawSmsId: String,
    val kind: ReviewKind,
    val kindLabelRes: Int,
    val sender: String?,
    val body: String,
    val dateLabel: String,
    val messageFamilyLabel: String?,
    val messageFamily: MessageFamily?,
    val amountLabel: String?,
    val merchant: String?,
    val counterparty: String?,
    val reasonLabels: List<String>,
    val pairCandidates: List<ReviewListItemUi>,
    val showExternalTransferAction: Boolean,
    val showIncomingIncomeAction: Boolean,
    val showFinancialTypeActions: Boolean,
    val showDismissNonFinancialAction: Boolean,
    val ownershipCard: CardReference? = null,
    val showOwnershipActions: Boolean = false,
    val showRestoreActions: Boolean = false,
    val readOnly: Boolean = false,
    val resolvedAtLabel: String? = null,
    val bankChoices: List<ReviewBankChoiceUi> = emptyList(),
)

enum class ReviewListMode {
    PENDING,
    IGNORED,
}

data class ReviewUiState(
    val loading: Boolean = true,
    val listMode: ReviewListMode = ReviewListMode.PENDING,
    val items: List<ReviewListItemUi> = emptyList(),
    val informationalDismissCount: Int = 0,
    val selectedDetail: ReviewDetailUi? = null,
    val resolving: Boolean = false,
    val error: ReviewError? = null,
    val actionErrorDetail: String? = null,
    val message: ReviewMessage? = null,
)

enum class ReviewError {
    LOAD_FAILED,
    ACTION_FAILED,
}

enum class ReviewMessage {
    RESOLVED,
    STILL_NEEDS_REVIEW,
    RESTORED,
}

val REVIEW_FINANCIAL_TYPE_ACTIONS: List<FinancialTransactionType> = listOf(
    FinancialTransactionType.EXPENSE,
    FinancialTransactionType.INCOME,
    FinancialTransactionType.CREDIT_CARD_PAYMENT,
    FinancialTransactionType.REFUND,
    FinancialTransactionType.CASH_WITHDRAWAL,
    FinancialTransactionType.BILL_PAYMENT,
    FinancialTransactionType.FEE,
    FinancialTransactionType.LOAN_REPAYMENT,
)

/** Compose test tag for a review-detail financial-type action. Stable across locales. */
fun reviewResolveTypeTestTag(type: FinancialTransactionType): String =
    "review_resolve_${type.name}"

/** Transfers use dedicated external-transfer / pair actions instead of generic type buttons. */
val TRANSFER_MESSAGE_FAMILIES: Set<MessageFamily> = setOf(
    MessageFamily.TRANSFER_IN,
    MessageFamily.TRANSFER_OUT,
)

fun MessageFamily?.toUiLabelRes(): Int? =
    when (this) {
        MessageFamily.PURCHASE -> com.baraa.masroof.R.string.review_family_purchase
        MessageFamily.TRANSFER_IN -> com.baraa.masroof.R.string.review_family_transfer_in
        MessageFamily.TRANSFER_OUT -> com.baraa.masroof.R.string.review_family_transfer_out
        MessageFamily.BILL_PAYMENT -> com.baraa.masroof.R.string.review_family_bill_payment
        MessageFamily.CARD_PAYMENT -> com.baraa.masroof.R.string.review_family_card_payment
        MessageFamily.REFUND -> com.baraa.masroof.R.string.review_family_refund
        MessageFamily.WITHDRAWAL -> com.baraa.masroof.R.string.review_family_withdrawal
        MessageFamily.FEE -> com.baraa.masroof.R.string.review_family_fee
        MessageFamily.FINANCING_INSTALLMENT -> com.baraa.masroof.R.string.review_family_financing_installment
        MessageFamily.UNKNOWN -> com.baraa.masroof.R.string.review_family_unknown
        else -> null
    }

fun FinancialTransactionType.toUiLabelRes(): Int =
    when (this) {
        FinancialTransactionType.EXPENSE -> com.baraa.masroof.R.string.txn_type_expense
        FinancialTransactionType.INCOME -> com.baraa.masroof.R.string.txn_type_income
        FinancialTransactionType.CREDIT_CARD_PAYMENT -> com.baraa.masroof.R.string.txn_type_card_payment
        FinancialTransactionType.REFUND -> com.baraa.masroof.R.string.txn_type_refund
        FinancialTransactionType.CASH_WITHDRAWAL -> com.baraa.masroof.R.string.txn_type_cash_withdrawal
        FinancialTransactionType.BILL_PAYMENT -> com.baraa.masroof.R.string.txn_type_bill_payment
        FinancialTransactionType.LOAN_REPAYMENT -> com.baraa.masroof.R.string.txn_type_loan_repayment
        FinancialTransactionType.FEE -> com.baraa.masroof.R.string.txn_type_fee
        FinancialTransactionType.ADJUSTMENT -> com.baraa.masroof.R.string.txn_type_adjustment
        FinancialTransactionType.SELF_TRANSFER -> com.baraa.masroof.R.string.txn_type_self_transfer
        FinancialTransactionType.EXTERNAL_TRANSFER_IN -> com.baraa.masroof.R.string.txn_type_external_in
        FinancialTransactionType.EXTERNAL_TRANSFER_OUT -> com.baraa.masroof.R.string.txn_type_external_out
        FinancialTransactionType.UNKNOWN -> com.baraa.masroof.R.string.txn_type_unknown
    }

fun ReviewKind.toUiLabelRes(): Int =
    when (this) {
        ReviewKind.NEEDS_REVIEW -> com.baraa.masroof.R.string.review_kind_needs_review
        ReviewKind.PENDING_MATCH -> com.baraa.masroof.R.string.review_kind_pending_match
    }

fun shouldOfferNonFinancialDismiss(
    messageFamily: MessageFamily?,
    reasons: List<String>,
): Boolean {
    if (reasons.any { it == "non_financial_or_informational_message" }) {
        return true
    }
    return InformationalMessagePolicy.shouldAutoIgnore(messageFamily)
}
