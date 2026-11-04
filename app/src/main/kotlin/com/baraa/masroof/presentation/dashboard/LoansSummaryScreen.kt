package com.baraa.masroof.presentation.dashboard

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.LoanOverview

@Composable
fun LoansSummaryRoute(
    viewModel: DashboardViewModel,
    initialSelectedLoanKey: String? = null,
    detailBackExitsSummary: Boolean = false,
    onInitialSelectionConsumed: () -> Unit = {},
    onBack: () -> Unit,
    onOpenTransaction: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var selectedLoanKey by rememberSaveable { mutableStateOf<String?>(null) }
    val followedLoans = state.followedLoansOverview()

    androidx.compose.runtime.LaunchedEffect(initialSelectedLoanKey) {
        if (initialSelectedLoanKey != null) {
            selectedLoanKey = initialSelectedLoanKey
            onInitialSelectionConsumed()
        }
    }

    val selectedLoan = selectedLoanKey?.let { key ->
        followedLoans?.loans?.find { LoanOwnershipKey.of(it) == key }
    }

    fun clearLoanSelection() {
        if (detailBackExitsSummary) {
            onBack()
        } else {
            selectedLoanKey = null
        }
    }

    BackHandler {
        when {
            selectedLoan != null -> clearLoanSelection()
            else -> onBack()
        }
    }

    when {
        selectedLoan != null -> {
            LoanDetailScreen(
                loan = selectedLoan,
                state = state,
                onBack = { clearLoanSelection() },
                onOpenTransaction = onOpenTransaction,
            )
        }

        else -> {
            LoansSummaryScreen(
                state = state,
                onBack = onBack,
                onOpenLoan = { loan -> selectedLoanKey = LoanOwnershipKey.of(loan) },
            )
        }
    }
}

@Composable
fun LoansSummaryScreen(
    state: DashboardUiState,
    onBack: () -> Unit,
    onOpenLoan: (LoanOverview) -> Unit,
) {
    val followedLoans = state.followedLoansOverview()

    DashboardSummaryScaffold(
        title = stringResource(R.string.dashboard_loans_summary_screen_title),
        state = state,
        onBack = onBack,
        showPeriod = false,
    ) {
        if (followedLoans == null || !followedLoans.hasContent) {
            Text(
                stringResource(R.string.dashboard_loans_summary_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            DashboardSummaryHeroCard(spec = loansSummaryHeroSpec(overview = followedLoans))
            followedLoans.loans.forEach { loan ->
                LoanCompactListRow(
                    loan = loan,
                    onClick = { onOpenLoan(loan) },
                )
            }
        }
    }
}

@Composable
fun LoanCompactListRow(loan: LoanOverview, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LoanSummaryTile(loan, modifier, onClick)
}
