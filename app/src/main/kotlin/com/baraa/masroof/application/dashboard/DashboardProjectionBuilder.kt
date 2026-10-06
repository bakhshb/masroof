package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.LoanRegistryRepository
import com.baraa.masroof.domain.repository.CommitmentRepository
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Composes the dashboard read model: loads the shared [DashboardProjectionContext] once,
 * then delegates to the section projections (analysis, accounts, cards, commitments).
 * Business rules live in the section projections' specialist builders/calculators.
 */
class DashboardProjectionBuilder(
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val reviewRepository: ReviewRepository,
    private val accountRegistryRepository: AccountRegistryRepository,
    private val cardRegistryRepository: CardRegistryRepository,
    private val loanRegistryRepository: LoanRegistryRepository,
    private val commitmentRepository: CommitmentRepository,
    private val appLocaleRepository: AppLocaleRepository,
    private val sarEquivalentResolver: TransactionSarEquivalentResolver,
    private val evidenceSource: DashboardEvidenceSource,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val primaryCurrency: Currency = Currency.SAR,
) {
    private val cardsProjection = CardsDashboardProjection(
        financialTransactionRepository = financialTransactionRepository,
        evidenceSource = evidenceSource,
        sarEquivalentResolver = sarEquivalentResolver,
    )
    private val commitmentsProjection = CommitmentsDashboardProjection(
        financialTransactionRepository = financialTransactionRepository,
        commitmentRepository = commitmentRepository,
        evidenceSource = evidenceSource,
        sarEquivalentResolver = sarEquivalentResolver,
    )

    /** [transactions] are the selected period's stored transactions. */
    suspend fun build(
        period: FinancialPeriod,
        transactions: List<FinancialTransaction>,
    ): DashboardProjection {
        val context = loadContext(period, transactions)
        val analysis = AnalysisDashboardProjection.build(context)
        val accounts = AccountsDashboardProjection.build(context)
        val cards = cardsProjection.build(context)
        val commitments = commitmentsProjection.build(context, cards.creditFacilities)
        val bankHierarchy = BankHierarchyBuilder.build(
            ownedAccounts = context.ownedAccounts,
            accountsFleet = accounts.accountsFleet,
            creditFacilities = cards.creditFacilities,
            loans = context.loans,
        )

        return DashboardProjection(
            period = period,
            isCurrentPeriod = period == FinancialPeriodPolicy.periodContaining(context.today),
            summary = analysis.summary,
            fleet = accounts.fleet,
            spendingSplit = accounts.spendingSplit,
            accountsFleet = accounts.accountsFleet,
            perAccount = accounts.perAccount,
            creditFacilities = cards.creditFacilities,
            loansOverview = commitments.loansOverview,
            commitmentsOverview = commitments.commitmentsOverview,
            merchantSpending = analysis.merchantSpending,
            dailySpendingTrend = analysis.dailySpendingTrend,
            bankHierarchy = bankHierarchy,
            flowDetail = accounts.flowDetail,
            transactionAccountInvolvement = accounts.transactionAccountInvolvement,
            transactionCardInvolvement = cards.transactionCardInvolvement,
            transactionLoanInvolvement = commitments.transactionLoanInvolvement,
            transactionDebitSpendInvolvement = cards.transactionDebitSpendInvolvement,
            transactions = context.transactions,
            meta = DashboardMeta(
                transactionCount = analysis.summary.transactionCount,
                reviewRequiredCount = context.reviewRequiredCount,
                excludedOtherCurrencyCount = analysis.summary.excludedOtherCurrencyCount,
            ),
            accountRegistry = context.ownedAccounts,
            cardRegistry = context.cardRegistry,
        )
    }

    private suspend fun loadContext(
        period: FinancialPeriod,
        transactions: List<FinancialTransaction>,
    ): DashboardProjectionContext {
        val cardRegistry = cardRegistryRepository.listAll()
        val periodEndExclusive = FinancialPeriodPolicy.toExclusiveEndInstant(period.endDateExclusive, zoneId)
        val evidence = evidenceSource.load(
            transactions = transactions,
            registryCards = cardRegistry,
            periodEndExclusive = periodEndExclusive,
        )
        val enrichedTransactions = TransactionDisplayEnricher.enrichMerchants(
            transactions = transactions,
            parsedRecords = evidence.parsedRecords,
        )
        val sarResolutions = sarEquivalentResolver.resolve(
            transactions = enrichedTransactions,
            parsedRecords = evidence.parsedRecords,
            rawSmsById = evidence.rawSmsById,
            primaryCurrency = primaryCurrency,
        )
        val displayedTransactions = SelfTransferDeduplicator.filter(
            transactions = AppliedExchangeRateSyncer.applyInMemory(enrichedTransactions, sarResolutions),
            parsedRecords = evidence.parsedRecords,
        )
        val ownedAccounts = accountRegistryRepository.listAll()
            .filter { it.bank != Bank.UNKNOWN && it.ownership == OwnershipStatus.OWNED }
        return DashboardProjectionContext(
            period = period,
            periodEndExclusive = periodEndExclusive,
            today = LocalDate.now(clock),
            evidence = evidence,
            transactions = displayedTransactions,
            sarEquivalents = sarResolutions.sarAmounts(),
            reviewRequiredCount = reviewRepository.listRequired().size,
            ownedAccounts = ownedAccounts,
            ownedAccountContainerIds = ownedAccounts
                .mapNotNull { FinancialContainerIdFactory.accountId(it.bank, it.maskedNumber) }
                .toSet(),
            ownedAccountLast4s = CurrentAccountTransactionScope.ownedAccountLast4sFromMaskedNumbers(
                ownedAccounts.map { it.maskedNumber },
            ),
            cardRegistry = cardRegistry,
            loans = loanRegistryRepository.listAll(),
            debitCardScope = DebitCardScopeFactory.fromRegistry(
                cards = cardRegistry,
                parsedRecords = evidence.parsedRecords,
                rawSmsById = evidence.rawSmsById,
                registryAccounts = ownedAccounts,
            ),
            displayLocale = AppLocale.displayLocale(appLocaleRepository.getLanguageTag()),
            zoneId = zoneId,
            primaryCurrency = primaryCurrency,
        )
    }
}
