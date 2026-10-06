package com.baraa.masroof.presentation.dashboard

import androidx.compose.ui.graphics.vector.ImageVector
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.BankHierarchyOverview
import com.baraa.masroof.application.dashboard.CommitmentsOverview
import com.baraa.masroof.application.dashboard.CreditFacilitiesOverview
import com.baraa.masroof.application.dashboard.LoansOverview
import com.baraa.masroof.application.dashboard.AccountsSummary
import com.baraa.masroof.application.dashboard.DashboardLayoutSnapshot
import com.baraa.masroof.application.dashboard.DailySpendingTrend
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.application.dashboard.CurrentAccountSummary
import com.baraa.masroof.application.dashboard.MonthlyFinancialSummary
import com.baraa.masroof.application.dashboard.MerchantSpendingOverview
import com.baraa.masroof.application.dashboard.SpendingSplitSummary
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.period.FinancialPeriod
import java.math.BigDecimal
import java.time.LocalDate

data class UnknownCardCandidateUi(
    val bank: Bank,
    val last4: String,
)

/** Confirmed owned card — can offer «stop tracking». */
data class OwnedCardUi(
    val bank: Bank,
    val last4: String,
    val displayName: String? = null,
    val cardNetwork: com.baraa.masroof.domain.model.CardNetwork? = null,
)

data class OwnedAccountUi(
    val bank: Bank,
    val maskedNumber: String,
    val displayName: String? = null,
    val periodSummary: CurrentAccountSummary? = null,
)

enum class TransactionDirectionUi {
    OUTWARD,
    INWARD,
    INCOME,
    TRANSFER_IN,
    NEUTRAL,
}

data class TransactionSmsEvidenceUi(
    val body: String,
    val sender: String?,
)

data class TransactionPreviewUi(
    val id: String,
    /** Merchant/counterparty when present; Compose falls back to Arabic type label when null. */
    val title: String?,
    val amount: Money,
    val localDate: LocalDate,
    val amountLabel: String,
    val dateLabel: String,
    val type: FinancialTransactionType,
    val typeLabelResHint: FinancialTransactionType,
    val direction: TransactionDirectionUi,
    val cardLast4: String?,
    val sourceContainerId: String? = null,
    val destinationContainerId: String? = null,
    /** Lowercase merchant/counterparty text for in-memory search. */
    val searchText: String,
    val sarEquivalent: Money? = null,
    val appliedExchangeRate: BigDecimal? = null,
    val exchangeRateSource: ExchangeRateSource? = null,
)

data class DashboardUiState(
    val loading: Boolean = true,
    val period: FinancialPeriod? = null,
    val periodLabel: String = "",
    val periodAdjustmentHint: String? = null,
    val summary: MonthlyFinancialSummary? = null,
    val currentAccount: CurrentAccountSummary? = null,
    val spendingSplit: SpendingSplitSummary? = null,
    val creditFacilities: CreditFacilitiesOverview? = null,
    val loansOverview: LoansOverview? = null,
    val commitmentsOverview: CommitmentsOverview = CommitmentsOverview.empty(),
    val merchantSpending: MerchantSpendingOverview = MerchantSpendingOverview.empty(),
    val dailySpendingTrend: DailySpendingTrend? = null,
    val bankHierarchy: BankHierarchyOverview? = null,
    val accountsFleet: AccountsSummary? = null,
    val recentTransactions: List<TransactionPreviewUi> = emptyList(),
    val allTransactions: List<TransactionPreviewUi> = emptyList(),
    val isCurrentPeriod: Boolean = true,
    val error: DashboardError? = null,
    val rescanning: Boolean = false,
    val rescanStatus: SmsRescanStatus? = null,
    val reparsingStored: Boolean = false,
    val selectedTransactionId: String? = null,
    val selectedTransactionSms: List<TransactionSmsEvidenceUi> = emptyList(),
    val selectedTransactionSmsLoading: Boolean = false,
    val reclassifying: Boolean = false,
    val reclassifyError: String? = null,
    val reclassifySuccess: Boolean = false,
    val ignoring: Boolean = false,
    val ignoreError: String? = null,
    val markingCommitment: Boolean = false,
    val markCommitmentError: String? = null,
    val markCommitmentSuccess: Boolean = false,
    val committedSourceTransactionIds: Set<String> = emptySet(),
    val unknownCards: List<UnknownCardCandidateUi> = emptyList(),
    val ownedCards: List<OwnedCardUi> = emptyList(),
    val ownedAccounts: List<OwnedAccountUi> = emptyList(),
    val ownedAccountContainerIds: Set<String> = emptySet(),
    val flowDetailGrouping: com.baraa.masroof.application.dashboard.CurrentAccountFlowDetailGrouping? = null,
    val transactionAccountInvolvement: Map<String, Set<String>> = emptyMap(),
    val transactionCardInvolvement: Map<String, Set<String>> = emptyMap(),
    val transactionLoanInvolvement: Map<String, Set<String>> = emptyMap(),
    val transactionDebitSpendInvolvement: Map<String, Set<String>> = emptyMap(),
    val smsPermissionGranted: Boolean = true,
    val dashboardLayout: DashboardLayoutSnapshot = DashboardLayoutSnapshot.default(),
    val customizeSheetOpen: Boolean = false,
    val customizeDraft: DashboardLayoutSnapshot? = null,
)

enum class DashboardError {
    LOAD_FAILED,
}

enum class SmsRescanStatus {
    OK,
    ALREADY_UP_TO_DATE,
    NEEDS_REVIEW,
    NO_MESSAGES,
    NO_BANK_SMS,
    NO_TRANSACTIONS,
    PERMISSION_DENIED,
    FAILED,
}

object TransactionDirectionPresentation {
    fun icon(direction: TransactionDirectionUi): ImageVector =
        when (direction) {
            TransactionDirectionUi.OUTWARD -> MasroofIcons.externalOut
            TransactionDirectionUi.INWARD -> MasroofIcons.refunds
            TransactionDirectionUi.INCOME -> MasroofIcons.income
            TransactionDirectionUi.TRANSFER_IN -> MasroofIcons.externalIn
            TransactionDirectionUi.NEUTRAL -> MasroofIcons.selfTransfer
        }

    fun labelRes(direction: TransactionDirectionUi): Int =
        when (direction) {
            TransactionDirectionUi.OUTWARD -> R.string.dashboard_direction_out
            TransactionDirectionUi.INWARD -> R.string.dashboard_direction_in
            TransactionDirectionUi.INCOME -> R.string.dashboard_direction_income
            TransactionDirectionUi.TRANSFER_IN -> R.string.dashboard_direction_transfer_in
            TransactionDirectionUi.NEUTRAL -> R.string.dashboard_direction_neutral
        }
}

object TransactionTypePresentation {
    fun direction(type: FinancialTransactionType): TransactionDirectionUi =
        when (type) {
            FinancialTransactionType.EXPENSE,
            FinancialTransactionType.FEE,
            FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
            FinancialTransactionType.CREDIT_CARD_PAYMENT,
            FinancialTransactionType.CASH_WITHDRAWAL,
            FinancialTransactionType.BILL_PAYMENT,
            FinancialTransactionType.LOAN_REPAYMENT,
            -> TransactionDirectionUi.OUTWARD

            FinancialTransactionType.INCOME,
            -> TransactionDirectionUi.INCOME

            FinancialTransactionType.EXTERNAL_TRANSFER_IN,
            -> TransactionDirectionUi.TRANSFER_IN

            FinancialTransactionType.REFUND,
            -> TransactionDirectionUi.INWARD

            FinancialTransactionType.SELF_TRANSFER,
            FinancialTransactionType.ADJUSTMENT,
            FinancialTransactionType.UNKNOWN,
            -> TransactionDirectionUi.NEUTRAL
        }
}

object ExchangeRateSourcePresentation {
    fun labelRes(source: ExchangeRateSource): Int =
        when (source) {
            ExchangeRateSource.SMS -> R.string.exchange_rate_source_sms
            ExchangeRateSource.HISTORICAL_MERCHANT -> R.string.exchange_rate_source_historical_merchant
            ExchangeRateSource.MARKET -> R.string.exchange_rate_source_market
        }
}
