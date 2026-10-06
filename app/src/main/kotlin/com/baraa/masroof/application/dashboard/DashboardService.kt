package com.baraa.masroof.application.dashboard

import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.CommitmentRepository
import com.baraa.masroof.domain.repository.LoanRegistryRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

data class DashboardOverview(
    val period: FinancialPeriod,
    val summary: MonthlyFinancialSummary,
    val currentAccount: CurrentAccountSummary,
    val spendingSplit: SpendingSplitSummary,
    /** All transactions in the selected period, newest first. */
    val transactions: List<com.baraa.masroof.domain.model.FinancialTransaction>,
    val creditFacilities: CreditFacilitiesOverview,
    val loansOverview: LoansOverview? = null,
    val commitmentsOverview: CommitmentsOverview = CommitmentsOverview.empty(),
    val merchantSpending: MerchantSpendingOverview = MerchantSpendingOverview.empty(),
    val dailySpendingTrend: DailySpendingTrend = DailySpendingTrend.empty(period),
    val bankHierarchy: BankHierarchyOverview? = null,
    val accountsFleet: AccountsSummary? = null,
    val ownedAccountPeriodSummaries: List<OwnedAccountPeriodSummary>,
    val flowDetailGrouping: CurrentAccountFlowDetailGrouping,
    /** Transaction id → owned account container ids (SMS-resolved, SingleAccount scope). */
    val transactionAccountInvolvement: Map<String, Set<String>> = emptyMap(),
    /** Transaction id → card keys (`bankId:last4`) from parsed SMS card refs. */
    val transactionCardInvolvement: Map<String, Set<String>> = emptyMap(),
    /** Transaction id → loan container ids for SMS-attributed loan repayments. */
    val transactionLoanInvolvement: Map<String, Set<String>> = emptyMap(),
    val transactionDebitSpendInvolvement: Map<String, Set<String>> = emptyMap(),
    val isCurrentPeriod: Boolean,
)

/**
 * Application service that loads period transactions and builds a dashboard projection.
 *
 * SMS evidence comes from [evidenceSource] — by default the bounded [DashboardEvidenceScope],
 * never a whole-history ParsedEvent scan. Loading is read-only; exchange-rate persistence
 * belongs to `ExchangeRateEnrichmentWorkflow`.
 */
class DashboardService(
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val reviewRepository: ReviewRepository,
    parsedEventRepository: ParsedEventRepository,
    rawSmsRepository: RawSmsRepository,
    private val appLocaleRepository: AppLocaleRepository,
    private val accountRegistryRepository: AccountRegistryRepository,
    private val cardRegistryRepository: CardRegistryRepository,
    private val loanRegistryRepository: LoanRegistryRepository,
    private val commitmentRepository: CommitmentRepository,
    private val sarEquivalentResolver: TransactionSarEquivalentResolver,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val primaryCurrency: Currency = Currency.SAR,
    private val evidenceSource: DashboardEvidenceSource = DashboardEvidenceScope(
        financialTransactionRepository = financialTransactionRepository,
        parsedEventRepository = parsedEventRepository,
        rawSmsRepository = rawSmsRepository,
    ),
) : DashboardOverviewLoader {
    private val projectionBuilder by lazy {
        DashboardProjectionBuilder(
            financialTransactionRepository = financialTransactionRepository,
            reviewRepository = reviewRepository,
            accountRegistryRepository = accountRegistryRepository,
            cardRegistryRepository = cardRegistryRepository,
            loanRegistryRepository = loanRegistryRepository,
            commitmentRepository = commitmentRepository,
            appLocaleRepository = appLocaleRepository,
            sarEquivalentResolver = sarEquivalentResolver,
            evidenceSource = evidenceSource,
            zoneId = zoneId,
            clock = clock,
            primaryCurrency = primaryCurrency,
        )
    }

    override suspend fun loadOverview(period: FinancialPeriod): DashboardOverview {
        val projection = loadProjection(period)
        return projection.toOverview().copy(
            creditFacilities = projection.creditFacilities,
            loansOverview = projection.loansOverview,
            commitmentsOverview = projection.commitmentsOverview,
            merchantSpending = projection.merchantSpending,
            dailySpendingTrend = projection.dailySpendingTrend,
            bankHierarchy = projection.bankHierarchy,
            accountsFleet = projection.accountsFleet,
        )
    }

    suspend fun loadProjection(period: FinancialPeriod): DashboardProjection {
        val startInclusive = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zoneId)
        val endExclusive = FinancialPeriodPolicy.toExclusiveEndInstant(period.endDateExclusive, zoneId)
        val transactions = financialTransactionRepository.listOccurredBetween(
            startInclusive = startInclusive,
            endExclusive = endExclusive,
        )
        return projectionBuilder.build(period = period, transactions = transactions)
    }

    suspend fun loadCurrentOverview(): DashboardOverview =
        loadOverview(FinancialPeriodPolicy.periodContaining(LocalDate.now(clock)))
}
