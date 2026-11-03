package com.baraa.masroof.application.analytics

import android.content.Context
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.CommitmentDashboardSource
import com.baraa.masroof.data.analytics.AnalysisAlertRow
import com.baraa.masroof.domain.model.LoanType

/** One naming rule for commitment rows, in-app due alerts and phone due notifications. */
object CommitmentNames {
    /** User-facing last4; never shows internal placeholders like "unknown". */
    fun maskedLast4(last4: String?): String {
        val trimmed = last4?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed.equals("unknown", ignoreCase = true)) "····" else trimmed
    }

    fun loanTypeRes(type: LoanType): Int = when (type) {
        LoanType.PERSONAL -> R.string.settings_loan_type_personal
        LoanType.AUTO -> R.string.settings_loan_type_auto
        LoanType.MORTGAGE -> R.string.settings_loan_type_mortgage
    }

    /** A card shows "Credit card ···last4"; an unnamed loan shows its type in the app language. */
    fun name(context: Context, source: CommitmentDashboardSource?, displayName: String, loanTypeFallback: LoanType?): String = when {
        source == CommitmentDashboardSource.CREDIT_CARD -> context.getString(R.string.entities_credit_card_last4, maskedLast4(displayName))
        loanTypeFallback != null -> context.getString(loanTypeRes(loanTypeFallback))
        else -> displayName
    }

    /**
     * A DUE alert's name from its snapshot due; once the due has left the snapshot, its id
     * still marks a card, whose stored title is only last4.
     */
    fun dueAlert(context: Context, alert: AnalysisAlertRow, due: AnalysisDue?): String = when {
        due != null -> name(context, due.source, due.name, due.loanTypeFallback)
        alert.isCardDue() -> name(context, CommitmentDashboardSource.CREDIT_CARD, alert.title, null)
        else -> alert.title
    }
}
