package com.baraa.masroof.presentation.review

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.BuildConfig
import com.baraa.masroof.R
import com.baraa.masroof.application.review.UnparsedMessageReport
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.dashboard.SharePlainText
import com.baraa.masroof.presentation.settings.settingsBankLabel

internal const val REVIEW_REPORT_PREVIEW_TEST_TAG = "review-report-preview"

/** Shows the exact text that will be shared, every digit masked, then hands it to the system share sheet. */
@Composable
internal fun ReviewReportSheet(detail: ReviewDetailUi, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = stringResource(
        R.string.review_report_text,
        UnparsedMessageReport.mask(detail.sender.orEmpty()),
        detail.bankId?.let { settingsBankLabel(Bank(it)) } ?: stringResource(R.string.review_report_unknown_bank),
        BuildConfig.VERSION_NAME,
        UnparsedMessageReport.mask(detail.body),
    )
    val title = stringResource(R.string.review_report_title)
    MasroofBottomSheet(
        title = title, onDismiss = onDismiss,
        onApply = { SharePlainText.share(context, text, title); onDismiss() },
        applyLabel = stringResource(R.string.transaction_share),
    ) {
        MasroofGuidanceLabel(stringResource(R.string.review_report_preview), stringResource(R.string.review_report_help))
        MasroofText(text, modifier = Modifier.testTag(REVIEW_REPORT_PREVIEW_TEST_TAG))
    }
}
