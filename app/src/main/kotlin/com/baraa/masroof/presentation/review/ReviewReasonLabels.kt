package com.baraa.masroof.presentation.review

import com.baraa.masroof.R

object ReviewReasonLabels {
    /** Not a stored reason: the detail screen adds it when the user chose the bank of the message. */
    const val USER_SELECTED_BANK = "user_selected_bank"

    fun labelRes(reason: String): Int? {
        return when (reason) {
            USER_SELECTED_BANK -> R.string.review_reason_user_selected_bank
            "transfer_pending_match" -> R.string.review_reason_transfer_pending_match
            "bill_payment_financial_treatment_unresolved" ->
                R.string.review_reason_bill_payment
            "unknown_message_family" -> R.string.review_reason_unknown_family
            "missing_amount" -> R.string.review_reason_missing_amount
            "needs_review" -> R.string.review_reason_needs_review
            "parse_review_required",
            "parse_partial",
            -> R.string.review_reason_parse_review_required
            "invalid_parsed_event" -> R.string.review_reason_invalid_parsed_event
            "unsupported_bank_message_format" -> R.string.review_reason_unsupported_format
            "processing_error" -> R.string.review_reason_processing_error
            "ambiguous_bank_route" -> R.string.review_reason_ambiguous_bank_route
            "suspected_bank_sender" -> R.string.review_reason_suspected_bank_sender
            "purchase_instrument_ownership_unknown" ->
                R.string.review_reason_purchase_ownership_unknown
            "purchase_without_resolved_owned_instrument" ->
                R.string.review_reason_purchase_unresolved
            "card_payment_missing_containers" -> R.string.review_reason_card_payment_missing
            "card_payment_ownership_unresolved" -> R.string.review_reason_card_payment_ownership
            "transfer_ownership_unknown_no_guess" -> R.string.review_reason_transfer_ownership
            "transfer_missing_source_or_destination" -> R.string.review_reason_transfer_missing_side
            "user_ignored_transaction" -> R.string.review_reason_user_ignored_transaction
            "non_financial_or_informational_message" -> R.string.review_reason_non_financial
            "review_not_found" -> R.string.review_error_not_found
            "review_not_required" -> R.string.review_error_not_required
            "raw_sms_already_finalized" -> R.string.review_error_already_finalized
            "parsed_event_missing" -> R.string.review_error_parsed_missing
            "missing_destination" -> R.string.review_reason_transfer_missing_side
            "transfer_evidence_changed" -> R.string.learning_transfer_changed
            "missing_source" -> R.string.review_reason_transfer_missing_side
            "destination_not_owned" -> R.string.review_reason_transfer_ownership
            "source_not_owned" -> R.string.review_reason_transfer_ownership
            "owned_transfer_requires_self_type" -> com.baraa.masroof.R.string.lab_owned_transfer_requires_self
            "same_transfer_account" -> com.baraa.masroof.R.string.lab_same_transfer_account
            "not_a_transfer" -> R.string.review_error_not_transfer
            "paired_transaction_not_supported" -> R.string.review_reason_paired_transaction_not_supported
            "delete_failed" -> R.string.transaction_detail_ignore_failed
            "review_resolution_failed" -> R.string.transaction_detail_ignore_failed
            "type_not_allowed" -> R.string.review_error_not_transfer
            "transaction_not_found" -> R.string.review_error_not_found
            "update_failed" -> R.string.transaction_detail_reclassify_failed
            else -> null
        }
    }
}
