package com.baraa.masroof.presentation.statement

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.domain.statement.StatementComparisonStatus
import com.baraa.masroof.domain.statement.StatementRejection
import com.baraa.masroof.presentation.common.MasroofAmountRole
import com.baraa.masroof.presentation.common.MasroofAmountText
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.common.MasroofSectionHeader
import com.baraa.masroof.presentation.theme.MasroofIconSizes
import com.baraa.masroof.presentation.theme.MasroofSpacing

const val STATEMENT_RECONCILIATION_PICK_TAG: String = "statement_reconciliation_pick"
const val STATEMENT_COUNTS_HEADER_TAG: String = "statement_counts_header"

fun statementOutcomeTag(status: StatementComparisonStatus): String =
    "statement_outcome_${status.name}"

fun statementSectionTag(status: StatementComparisonStatus): String =
    "statement_section_${status.name}"

fun statementLineTag(status: StatementComparisonStatus, index: Int): String =
    "statement_line_${status.name}_$index"

@Composable
fun StatementReconciliationRoute(
    viewModel: StatementReconciliationViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val stream = context.contentResolver.openInputStream(uri)
        if (stream == null) {
            viewModel.onUnreadable()
        } else {
            viewModel.compare(stream)
        }
    }
    StatementReconciliationScreen(
        state = state,
        onBack = onBack,
        onPickFile = {
            picker.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "*/*"))
        },
    )
}

@Composable
fun StatementReconciliationScreen(
    state: StatementReconciliationUiState,
    onBack: () -> Unit,
    onPickFile: () -> Unit,
) {
    MasroofSecondaryScaffold(
        title = stringResource(R.string.settings_statement_reconciliation_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap),
            ) {
                Icon(
                    imageVector = MasroofIcons.recentTransactions,
                    contentDescription = null,
                    modifier = Modifier.size(MasroofIconSizes.lg),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.settings_statement_reconciliation_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.settings_statement_reconciliation_limits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onPickFile,
                enabled = !state.busy,
                modifier = Modifier.testTag(STATEMENT_RECONCILIATION_PICK_TAG),
            ) {
                Text(stringResource(R.string.settings_statement_pick_file))
            }
            if (state.busy) {
                CircularProgressIndicator()
            }
            rejectionText(state)?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.hasReport) {
                ReportBody(state)
            }
        }
    }
}

@Composable
private fun ReportBody(state: StatementReconciliationUiState) {
    MasroofSectionHeader(
        title = stringResource(R.string.settings_statement_counts),
        modifier = Modifier.testTag(STATEMENT_COUNTS_HEADER_TAG),
    )
    MasroofCard {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
            CountRow(
                R.string.settings_statement_matched,
                state.matchedCount,
                StatementComparisonStatus.MATCHED,
            )
            CountRow(
                R.string.settings_statement_statement_only,
                state.statementOnlyCount,
                StatementComparisonStatus.STATEMENT_ONLY,
            )
            CountRow(
                R.string.settings_statement_ledger_only,
                state.ledgerOnlyCount,
                StatementComparisonStatus.LEDGER_ONLY,
            )
            CountRow(
                R.string.settings_statement_ambiguous,
                state.ambiguousCount,
                StatementComparisonStatus.AMBIGUOUS,
            )
            CountRow(
                R.string.settings_statement_unsupported,
                state.unsupportedCount,
                StatementComparisonStatus.UNSUPPORTED,
            )
            CountRow(R.string.settings_statement_fleet_payments, state.matchedFleetPaymentCount)
            CountRow(R.string.settings_statement_self_transfers, state.matchedSelfTransferCount)
        }
    }
    if (state.balanceUnsupported) {
        Text(
            text = stringResource(R.string.settings_statement_balance_unsupported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    MasroofSectionHeader(title = stringResource(R.string.settings_statement_totals))
    state.totals.forEach { total ->
        MasroofCard {
            Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                Text(
                    text = stringResource(
                        R.string.settings_statement_total_heading,
                        total.bankId,
                        total.accountMasked,
                        total.periodStart,
                        total.periodEnd,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AmountRow(R.string.settings_statement_matched_debit, total.matchedDebit)
                AmountRow(R.string.settings_statement_matched_credit, total.matchedCredit)
                AmountRow(R.string.settings_statement_statement_only_debit, total.statementOnlyDebit)
                AmountRow(R.string.settings_statement_ledger_only_debit, total.ledgerOnlyDebit)
            }
        }
    }
    LineSection(
        R.string.settings_statement_matched,
        StatementComparisonStatus.MATCHED,
        state.matchedLines,
    )
    LineSection(
        R.string.settings_statement_statement_only,
        StatementComparisonStatus.STATEMENT_ONLY,
        state.statementOnlyLines,
    )
    LineSection(
        R.string.settings_statement_ledger_only,
        StatementComparisonStatus.LEDGER_ONLY,
        state.ledgerOnlyLines,
    )
    LineSection(
        R.string.settings_statement_ambiguous,
        StatementComparisonStatus.AMBIGUOUS,
        state.ambiguousLines,
    )
    LineSection(
        R.string.settings_statement_unsupported,
        StatementComparisonStatus.UNSUPPORTED,
        state.unsupportedLines,
    )
}

@Composable
private fun LineSection(
    titleRes: Int,
    status: StatementComparisonStatus,
    lines: List<StatementLineUi>,
) {
    if (lines.isEmpty()) return
    MasroofSectionHeader(
        title = stringResource(titleRes) + " " + status.name,
        modifier = Modifier.testTag(statementSectionTag(status)),
    )
    lines.forEachIndexed { index, line ->
        MasroofCard(modifier = Modifier.testTag(statementLineTag(status, index))) {
            Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
                Text(
                    text = line.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MasroofAmountText(amount = line.amountLabel, role = MasroofAmountRole.List)
                Text(
                    text = line.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CountRow(labelRes: Int, count: Int, status: StatementComparisonStatus? = null) {
    val label = stringResource(labelRes)
    val value = if (status == null) "$label $count" else "$label ${status.name} $count"
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = if (status == null) Modifier else Modifier.testTag(statementOutcomeTag(status)),
    )
}

@Composable
private fun AmountRow(labelRes: Int, amount: String) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    MasroofAmountText(amount = amount, role = MasroofAmountRole.List)
}

@Composable
private fun rejectionText(state: StatementReconciliationUiState): String? {
    if (state.unreadable && state.rejection == null) {
        return stringResource(R.string.settings_statement_rejection_unreadable)
    }
    return when (state.rejection) {
        null -> null
        StatementRejection.OVERSIZED -> stringResource(R.string.settings_statement_rejection_oversized)
        StatementRejection.TRUNCATED -> stringResource(R.string.settings_statement_rejection_truncated)
        StatementRejection.UNRECOGNIZED -> stringResource(R.string.settings_statement_rejection_unrecognized)
        StatementRejection.AMBIGUOUS_HEADER -> stringResource(R.string.settings_statement_rejection_ambiguous)
        StatementRejection.UNSUPPORTED_SEPARATOR -> stringResource(R.string.settings_statement_rejection_separator)
        StatementRejection.DUPLICATE_ROW -> stringResource(R.string.settings_statement_rejection_duplicate)
        StatementRejection.OUT_OF_RANGE -> stringResource(R.string.settings_statement_rejection_range)
        StatementRejection.UNKNOWN_BANK -> stringResource(R.string.settings_statement_rejection_bank)
        StatementRejection.FORMULA -> stringResource(R.string.settings_statement_rejection_formula)
        StatementRejection.UNREADABLE -> stringResource(R.string.settings_statement_rejection_unreadable)
    }
}
