package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
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
    /** [transactions] are the selected period's stored transactions. */
    suspend fun build(
        period: FinancialPeriod,
        transactions: List<FinancialTransaction>,
    ): DashboardProjection {
        val reviewRequiredCount = reviewRepository.listRequired().size
        val commitments = commitmentRepository.listAll()
        val cardRegistry = cardRegistryRepository.listAll()
        val periodEndExclusive = FinancialPeriodPolicy.toExclusiveEndInstant(period.endDateExclusive, zoneId)
        val evidence = evidenceSource.load(
            transactions = transactions,
            registryCards = cardRegistry,
            periodEndExclusive = periodEndExclusive,
        )
        val parsedRecords = evidence.parsedRecords
        val rawSmsById = evidence.rawSmsById
        val enrichedTransactions = TransactionDisplayEnricher.enrichMerchants(
            transactions = transactions,
            parsedRecords = parsedRecords,
        )
        val sarResolutions = sarEquivalentResolver.resolve(
            transactions = enrichedTransactions,
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            primaryCurrency = primaryCurrency,
        )
        val syncedTransactions = AppliedExchangeRateSyncer.sync(
            transactions = enrichedTransactions,
            resolutions = sarResolutions,
            repository = financialTransactionRepository,
        )
        val dedupedTransactions = SelfTransferDeduplicator.filter(
            transactions = syncedTransactions,
            parsedRecords = parsedRecords,
        )
        val sarEquivalents = sarResolutions.sarAmounts()
        val periodTransactionIds = dedupedTransactions.mapTo(mutableSetOf()) { it.id }
        val commitmentSourceTransactions = commitments.mapNotNull { commitment ->
            if (commitment.sourceTransactionId in periodTransactionIds) {
                null
            } else {
                financialTransactionRepository.getById(commitment.sourceTransactionId)
            }
        }
        val commitmentEvidence = evidenceSource.extend(evidence, commitmentSourceTransactions)
        val commitmentSarEquivalents = sarEquivalentResolver.resolve(
            transactions = commitmentSourceTransactions,
            parsedRecords = commitmentEvidence.parsedRecords,
            rawSmsById = commitmentEvidence.rawSmsById,
            primaryCurrency = primaryCurrency,
        ).sarAmounts()
        val commitmentSarEquivalentAmounts = sarEquivalents + commitmentSarEquivalents

        val ownedAccounts = accountRegistryRepository.listAll()
            .filter { it.bank != Bank.UNKNOWN && it.ownership == OwnershipStatus.OWNED }
        val ownedAccountContainerIds = ownedAccounts
            .mapNotNull { FinancialContainerIdFactory.accountId(it.bank, it.maskedNumber) }
            .toSet()
        val ownedAccountLast4s = CurrentAccountTransactionScope.ownedAccountLast4sFromMaskedNumbers(
            ownedAccounts.map { it.maskedNumber },
        )
        val debitCardScope = DebitCardScopeFactory.fromRegistry(
            cards = cardRegistry,
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            registryAccounts = ownedAccounts,
        )

        val summary = MonthlyFinancialSummaryCalculator.summarize(
            period = period,
            transactions = dedupedTransactions,
            reviewRequiredCount = reviewRequiredCount,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            parsedRecords = parsedRecords,
        )
        val fleet = CurrentAccountSummaryCalculator.summarize(
            transactions = dedupedTransactions,
            parsedRecords = parsedRecords,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            ownedAccountContainerIds = ownedAccountContainerIds,
            ownedAccountLast4s = ownedAccountLast4s,
            rawSmsById = rawSmsById,
            debitCardScope = debitCardScope,
        )
        val spendingSplit = CurrentAccountSummaryCalculator.spendingSplit(
            transactions = dedupedTransactions,
            parsedRecords = parsedRecords,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            ownedAccountContainerIds = ownedAccountContainerIds,
            ownedAccountLast4s = ownedAccountLast4s,
            rawSmsById = rawSmsById,
            debitCardScope = debitCardScope,
        )
        val perAccount = OwnedAccountPeriodSummaryCalculator.summarize(
            ownedAccounts = ownedAccounts,
            transactions = dedupedTransactions,
            parsedRecords = parsedRecords,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            rawSmsById = rawSmsById,
            debitCardScope = debitCardScope,
        )
        val accountsFleet = AccountsSummary.fromSummaries(
            accounts = ownedAccounts.map { it.bank to it.maskedNumber },
            summaries = perAccount.map { it.summary },
        )
        val flowDetail = CurrentAccountFlowDetailGrouper.group(
            transactions = dedupedTransactions,
            parsedRecords = parsedRecords,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            ownedAccountContainerIds = ownedAccountContainerIds,
            ownedAccountLast4s = ownedAccountLast4s,
            rawSmsById = rawSmsById,
            debitCardScope = debitCardScope,
        )
        val transactionAccountInvolvement = AccountTransactionInvolvementResolver.buildIndex(
            transactions = dedupedTransactions,
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            ownedAccounts = ownedAccounts,
        )
        val transactionCardInvolvement = CardTransactionInvolvementResolver.buildIndex(
            transactions = dedupedTransactions,
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
        )
        val transactionLoanInvolvement = LoanRepaymentAttribution.buildInvolvementIndex(
            transactions = dedupedTransactions,
            parsedRecords = parsedRecords,
        )

        val statementStart = CreditCardOverviewBuilder.resolveStatementSpendingStart(
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            zoneId = zoneId,
            periodEndExclusive = periodEndExclusive,
        )
        val cardQueryStart = minOf(
            FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zoneId),
            statementStart,
        )
        val cardTransactions = financialTransactionRepository.listOccurredBetween(
            startInclusive = cardQueryStart,
            endExclusive = periodEndExclusive,
        )
        val cardEvidence = evidenceSource.extend(evidence, cardTransactions)
        val enrichedCardTransactions = TransactionDisplayEnricher.enrichMerchants(
            transactions = cardTransactions,
            parsedRecords = cardEvidence.parsedRecords,
        )
        val cardSarResolutions = sarEquivalentResolver.resolve(
            transactions = enrichedCardTransactions,
            parsedRecords = cardEvidence.parsedRecords,
            rawSmsById = cardEvidence.rawSmsById,
            primaryCurrency = primaryCurrency,
        )
        AppliedExchangeRateSyncer.sync(
            transactions = enrichedCardTransactions,
            resolutions = cardSarResolutions,
            repository = financialTransactionRepository,
        )
        val cardSarEquivalents = cardSarResolutions.sarAmounts()
        val displayLocale = AppLocale.displayLocale(appLocaleRepository.getLanguageTag())
        val creditCardsFlat = CreditCardOverviewBuilder.build(
            salaryPeriod = period,
            cardTransactions = enrichedCardTransactions,
            parsedRecords = cardEvidence.parsedRecords,
            rawSmsById = cardEvidence.rawSmsById,
            zoneId = zoneId,
            primaryCurrency = primaryCurrency,
            sarEquivalents = cardSarEquivalents,
            displayLocale = displayLocale,
        )
        val debitSpend = DebitCardOverviewBuilder.buildSpendingByCardKey(
            salaryPeriod = period,
            debitCards = cardRegistry,
            transactions = dedupedTransactions,
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            ownedAccountContainerIds = ownedAccountContainerIds,
            ownedAccountLast4s = ownedAccountLast4s,
            zoneId = zoneId,
            displayLocale = displayLocale,
        )
        val creditFacilities = CreditFacilityOverviewBuilder.build(
            overview = creditCardsFlat,
            registryCards = cardRegistry,
            registryAccounts = ownedAccounts,
            debitSpendingByCardKey = debitSpend.spendingByCardKey,
            debitSalaryPeriodLabel = debitSpend.salaryPeriodLabel ?: creditCardsFlat.salaryPeriodLabel,
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
        )
        val loansOverview = LoanOverviewBuilder.build(
            salaryPeriod = period,
            loans = loanRegistryRepository.listAll(),
            transactions = dedupedTransactions,
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            zoneId = zoneId,
            displayLocale = displayLocale,
        )
        val creditCardPaymentTransactions = creditFacilityPaymentTransactionsOutsidePeriod(
            creditFacilities = creditFacilities,
            salaryPeriod = period,
            periodTransactionIds = periodTransactionIds,
            zoneId = zoneId,
        )
        val creditCardPaymentEvidence = evidenceSource.extend(evidence, creditCardPaymentTransactions)
        val creditCardPaymentSarEquivalents = sarEquivalentResolver.resolve(
            transactions = creditCardPaymentTransactions,
            parsedRecords = creditCardPaymentEvidence.parsedRecords,
            rawSmsById = creditCardPaymentEvidence.rawSmsById,
            primaryCurrency = primaryCurrency,
        ).sarAmounts()
        val commitmentTransactions = buildList {
            addAll(dedupedTransactions)
            addAll(commitmentSourceTransactions)
            addAll(creditCardPaymentTransactions)
        }.distinctBy { it.id }
        val commitmentsOverview = CommitmentsOverviewBuilder.build(
            salaryPeriod = period,
            commitments = commitments,
            creditFacilities = creditFacilities,
            loansOverview = loansOverview,
            transactions = commitmentTransactions,
            primaryCurrency = primaryCurrency,
            sarEquivalents = commitmentSarEquivalentAmounts + creditCardPaymentSarEquivalents,
            zoneId = zoneId,
        )
        val merchantSpending = MerchantSpendingOverviewBuilder.build(
            transactions = dedupedTransactions,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
        )
        val dailySpendingTrend = DailySpendingTrendBuilder.build(
            period = period,
            transactions = dedupedTransactions,
            parsedRecords = parsedRecords,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            zoneId = zoneId,
            today = LocalDate.now(clock),
        )
        val bankHierarchy = BankHierarchyBuilder.build(
            ownedAccounts = ownedAccounts,
            accountsFleet = accountsFleet,
            creditFacilities = creditFacilities,
            loans = loanRegistryRepository.listAll(),
        )

        val current = FinancialPeriodPolicy.periodContaining(LocalDate.now(clock))
        return DashboardProjection(
            period = period,
            isCurrentPeriod = period == current,
            summary = summary,
            fleet = fleet,
            spendingSplit = spendingSplit,
            accountsFleet = accountsFleet,
            perAccount = perAccount,
            creditFacilities = creditFacilities,
            loansOverview = loansOverview,
            commitmentsOverview = commitmentsOverview,
            merchantSpending = merchantSpending,
            dailySpendingTrend = dailySpendingTrend,
            bankHierarchy = bankHierarchy,
            flowDetail = flowDetail,
            transactionAccountInvolvement = transactionAccountInvolvement,
            transactionCardInvolvement = transactionCardInvolvement,
            transactionLoanInvolvement = transactionLoanInvolvement,
            transactionDebitSpendInvolvement = debitSpend.transactionDebitSpendInvolvement,
            transactions = dedupedTransactions,
            meta = DashboardMeta(
                transactionCount = summary.transactionCount,
                reviewRequiredCount = reviewRequiredCount,
                excludedOtherCurrencyCount = summary.excludedOtherCurrencyCount,
            ),
            accountRegistry = ownedAccounts,
            cardRegistry = cardRegistry,
        )
    }

    private suspend fun creditFacilityPaymentTransactionsOutsidePeriod(
        creditFacilities: CreditFacilitiesOverview,
        salaryPeriod: FinancialPeriod,
        periodTransactionIds: Set<String>,
        zoneId: ZoneId,
    ): List<FinancialTransaction> {
        val relevantFacilities = creditFacilities.facilities.filter { facility ->
            val due = facility.facilityDue ?: return@filter false
            CommitmentsOverviewBuilder.isStatementDueInPeriod(due, salaryPeriod, zoneId)
        }
        if (relevantFacilities.isEmpty()) return emptyList()

        val earliestDueUpdate = relevantFacilities.mapNotNull { it.facilityDue?.updatedAt }.min()
        val creditCardPayments = financialTransactionRepository.listByTypesOccurredSince(
            types = listOf(FinancialTransactionType.CREDIT_CARD_PAYMENT),
            startInclusive = earliestDueUpdate,
        )
        return relevantFacilities.flatMap { facility ->
            val due = facility.facilityDue ?: return@flatMap emptyList()
            val cardIds = facility.allCards.mapNotNull { card ->
                FinancialContainerIdFactory.cardId(card.bank, card.last4)
            }.toSet()
            creditCardPayments.filter { transaction ->
                transaction.id !in periodTransactionIds &&
                    transaction.destinationContainerId in cardIds &&
                    !transaction.occurredAt.isBefore(due.updatedAt)
            }
        }.distinctBy { it.id }
    }
}
