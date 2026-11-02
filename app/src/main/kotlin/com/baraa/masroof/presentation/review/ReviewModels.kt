package com.baraa.masroof.presentation.review

import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.application.review.ReviewDismissPolicy

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
    /** Localized title used when the SMS named no merchant or counterparty. */
    val titleRes: Int? = null,
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
    val provenance: com.baraa.masroof.application.interpretation.InterpretationProvenance? = null,
    val bankId: String? = null,
    val transferBetweenOwnAccounts: Boolean = false,
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

val REVIEW_RESOLUTION_TYPE_ACTIONS = listOf(
    FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
    FinancialTransactionType.EXTERNAL_TRANSFER_IN,
    FinancialTransactionType.SELF_TRANSFER,
) + REVIEW_FINANCIAL_TYPE_ACTIONS

/** Compose test tag for a review-detail financial-type action. Stable across locales. */
fun reviewResolveTypeTestTag(type: FinancialTransactionType): String =
    "review_resolve_${type.name}"

/** Compose test tag for restore-as-is on an ignored review. Stable across locales. */
const val REVIEW_RESTORE_AS_IS_TEST_TAG: String = "review_action_restore"

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

fun ReviewKind.toUiLabelRes(): Int =
    when (this) {
        ReviewKind.NEEDS_REVIEW -> com.baraa.masroof.R.string.review_kind_needs_review
        ReviewKind.PENDING_MATCH -> com.baraa.masroof.R.string.review_kind_pending_match
    }

fun shouldOfferNonFinancialDismiss(
    messageFamily: MessageFamily?,
    reasons: List<String>,
): Boolean = ReviewDismissPolicy.offersNonFinancialDismiss(messageFamily, reasons)
