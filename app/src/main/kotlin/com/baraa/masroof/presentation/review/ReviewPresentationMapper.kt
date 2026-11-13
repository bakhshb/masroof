package com.baraa.masroof.presentation.review

import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.application.review.ReviewDetailLoader
import com.baraa.masroof.application.review.ReviewOwnershipWorkflow
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.ExplicitBankSelection
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.presentation.dashboard.MoneyUiFormatter
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Builds the list rows and the detail of [ReviewViewModel] from loaded reviews. */
internal class ReviewPresentationMapper(
    private val reviewWorkflowService: ReviewWorkflowService,
    private val reviewOwnershipWorkflow: ReviewOwnershipWorkflow,
    private val selectableBanks: List<Bank>,
    private val appLocaleRepository: AppLocaleRepository,
    private val zoneId: ZoneId,
) {
    private val languageTag: String
        get() = appLocaleRepository.getLanguageTag()

    private val dateFormatter: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern(
            "d MMM yyyy",
            AppLocale.displayLocale(languageTag),
        )

    fun toListItem(summary: ReviewDetailLoader.ReviewSummary): ReviewListItemUi {
        val review = summary.review
        val dateLabel = when {
            isIgnoredReview(review) ->
                review.resolvedAt?.atZone(zoneId)?.toLocalDate()?.let(dateFormatter::format) ?: "—"
            else ->
                summary.receivedAt?.atZone(zoneId)?.toLocalDate()?.let(dateFormatter::format) ?: "—"
        }
        val dismissible = shouldOfferNonFinancialDismiss(
            messageFamily = summary.messageFamily,
            reasons = review.reasons,
        )
        return ReviewListItemUi(
            id = review.id,
            kind = review.kind,
            kindLabelRes = if (isIgnoredReview(review)) {
                com.baraa.masroof.R.string.review_status_ignored
            } else {
                review.kind.toUiLabelRes()
            },
            title = summary.title ?: "—",
            titleRes = if (summary.title != null) null
                else if (summary.messageFamily in setOf(MessageFamily.PURCHASE, MessageFamily.REFUND)) com.baraa.masroof.R.string.transaction_unknown_merchant
                else summary.messageFamily.toUiLabelRes(),
            smsBody = summary.body?.trim().orEmpty().ifEmpty { "—" }
                .let { maskIgnoredCodes(it, summary.messageFamily, isIgnoredReview(review)) },
            amountLabel = summary.amount?.let { MoneyUiFormatter.format(it, languageTag) },
            dateLabel = dateLabel,
            reasonLabel = review.reasons.firstOrNull().orEmpty(),
            dismissibleAsNonFinancial = dismissible,
            ignored = isIgnoredReview(review),
            sender = summary.sender,
            bankId = summary.bankId,
            ignoredReasonRes = if (!isIgnoredReview(review)) null else when (summary.messageFamily) {
                MessageFamily.OTP -> com.baraa.masroof.R.string.review_ignored_reason_otp
                MessageFamily.BALANCE_NOTICE -> com.baraa.masroof.R.string.review_ignored_reason_balance
                null, MessageFamily.UNKNOWN -> com.baraa.masroof.R.string.review_family_unknown
                else -> com.baraa.masroof.R.string.review_ignored_reason_not_transaction
            },
        )
    }

    private fun isIgnoredReview(review: com.baraa.masroof.domain.model.ReviewItem): Boolean =
        review.status == ReviewStatus.RESOLVED &&
            review.resolutionKind == ReviewResolutionKind.USER_NON_FINANCIAL

    suspend fun toDetailUi(
        detail: ReviewDetailLoader.ReviewDetail,
        pairCandidates: List<ReviewListItemUi>,
    ): ReviewDetailUi {
        val review = detail.review
        val family = detail.messageFamily
        val isTransferOut = family == MessageFamily.TRANSFER_OUT
        val isTransferIn = family == MessageFamily.TRANSFER_IN
        val pendingMatch = review.kind == ReviewKind.PENDING_MATCH
        val dismissNonFinancial = shouldOfferNonFinancialDismiss(
            messageFamily = family,
            reasons = review.reasons,
        )
        val dateLabel = detail.receivedAt?.atZone(zoneId)?.toLocalDate()?.let(dateFormatter::format)
            ?: "—"
        val resolvedAtLabel = review.resolvedAt?.atZone(zoneId)?.toLocalDate()?.let(dateFormatter::format)
        val ignored = isIgnoredReview(review)
        val ownershipCard = if (!ignored && review.reasons.any { it in OWNERSHIP_CARD_REASONS }) {
            detail.cardRef?.takeIf { cardRef ->
                cardRef.last4 != null &&
                    reviewOwnershipWorkflow.isCardOwnershipUnknown(cardRef)
            }
        } else {
            null
        }
        return ReviewDetailUi(
            id = review.id,
            rawSmsId = review.rawSmsId,
            kind = review.kind,
            kindLabelRes = review.kind.toUiLabelRes(),
            sender = detail.sender,
            body = detail.body.orEmpty(),
            provenance = detail.provenance,
            bankId = detail.bankId,
            dateLabel = dateLabel,
            messageFamily = family,
            amountLabel = detail.amount?.let { MoneyUiFormatter.format(it, languageTag) },
            merchant = detail.merchant,
            counterparty = detail.counterparty,
            reasonLabels = review.reasons + listOfNotNull(ReviewReasonLabels.USER_SELECTED_BANK.takeIf { detail.bankSelectedByUser }),
            pairCandidates = pairCandidates,
            showExternalTransferAction = !ignored && (isTransferOut || isTransferIn) &&
                (pendingMatch || review.kind == ReviewKind.NEEDS_REVIEW),
            transferBetweenOwnAccounts = !ignored && (isTransferOut || isTransferIn) &&
                reviewWorkflowService.transferResolvesBetweenOwnAccounts(review.rawSmsId),
            showIncomingIncomeAction = !ignored && pendingMatch && isTransferIn,
            showFinancialTypeActions = !ignored && review.kind == ReviewKind.NEEDS_REVIEW &&
                !dismissNonFinancial &&
                family !in TRANSFER_MESSAGE_FAMILIES &&
                ownershipCard == null,
            showDismissNonFinancialAction = !ignored && review.kind == ReviewKind.NEEDS_REVIEW,
            ownershipCard = ownershipCard,
            showOwnershipActions = !ignored && ownershipCard != null,
            showRestoreActions = ignored,
            resolvedAtLabel = resolvedAtLabel,
            bankChoices = bankChoicesFor(review, ignored),
        )
    }

    private fun bankChoicesFor(
        review: com.baraa.masroof.domain.model.ReviewItem,
        ignored: Boolean,
    ): List<String> {
        if (ignored || review.kind != ReviewKind.NEEDS_REVIEW) return emptyList()
        if (!ExplicitBankSelection.offersBankChoice(review.reasons)) return emptyList()
        return selectableBanks.map { it.id }
    }

    private companion object {
        val OWNERSHIP_CARD_REASONS = setOf(
            "purchase_instrument_ownership_unknown",
            "card_payment_ownership_unresolved",
        )
    }
}

private val OTP_DIGITS = Regex("\\p{Nd}{3,}")

// A standalone 4–8 digit run, not part of an amount, a longer number or a masked card (*1234).
private val CODE_LIKE_DIGITS = Regex("(?<![\\p{Nd}.,٫٬*•·])\\p{Nd}{4,8}(?![\\p{Nd},٫٬]|[.]\\p{Nd})")

/** An ignored code still works for a while, so the list never shows its digits, even when the rules did not label it OTP. */
internal fun maskIgnoredCodes(body: String, family: MessageFamily?, ignored: Boolean): String = when {
    family == MessageFamily.OTP -> body.replace(OTP_DIGITS, "••••")
    ignored -> body.replace(CODE_LIKE_DIGITS, "••••")
    else -> body
}
