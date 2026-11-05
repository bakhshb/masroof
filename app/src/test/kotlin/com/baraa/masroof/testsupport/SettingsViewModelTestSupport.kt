package com.baraa.masroof.testsupport

import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.settings.SettingsRegistryWorkflow
import com.baraa.masroof.application.transaction.TransactionReclassificationService
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.application.transaction.TransactionRestoreService
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserCorrectionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.time.InstantClock

internal object SettingsViewModelTestSupport {
    fun settingsRegistryWorkflow(
        cards: CardRegistryRepository = emptyCardRegistry(),
        accounts: AccountRegistryRepository = emptyAccountRegistry(),
        loans: com.baraa.masroof.domain.repository.LoanRegistryRepository = NoOpLoanRegistryRepository,
        transactions: FinancialTransactionRepository? = null,
    ): SettingsRegistryWorkflow =
        SettingsRegistryWorkflow(
            cardRegistryRepository = cards,
            accountRegistryRepository = accounts,
            loanRegistryRepository = loans,
            ownershipConfirmationService = OwnershipConfirmationService(
                accountRegistry = accounts,
                cardRegistry = cards,
                loanRegistry = loans,
            ),
            transactions = transactions,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

    fun restoreService(
        reviewRepository: ReviewRepository = emptyReviewRepository(),
        financialTransactionRepository: FinancialTransactionRepository = emptyFinancialTransactionRepository(),
        clock: InstantClock = InstantClock.System,
        reclassificationEvents: ParsedEventRepository = emptyParsedEventRepository(),
    ): TransactionRestoreService {
        val ownershipResolver = OwnershipResolver(
            accountRegistry = emptyAccountRegistry(),
            cardRegistry = emptyCardRegistry(),
            loanRegistry = NoOpLoanRegistryRepository,
        )
        return TransactionRestoreService(
            reviewRepository = reviewRepository,
            financialTransactionRepository = financialTransactionRepository,
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = emptyParsedEventRepository(),
                rawSmsRepository = emptyRawSmsRepository(),
                financialTransactionRepository = financialTransactionRepository,
                ownershipResolver = ownershipResolver,
            ),
            reclassification = TransactionReclassificationService(
                financialTransactionRepository = financialTransactionRepository,
                effectiveParsedEventProvider = EffectiveParsedEventProvider(
                    parsedEventRepository = reclassificationEvents,
                    userCorrectionRepository = emptyUserCorrectionRepository(),
                ),
                ownershipResolver = ownershipResolver,
            ),
            clock = clock,
        )
    }

    fun emptyUserCorrectionRepository(): UserCorrectionRepository =
        object : UserCorrectionRepository {
            override suspend fun save(correction: UserCorrection) = Unit
            override suspend fun latestForRawSmsId(rawSmsId: String) = null
            override suspend fun listForRawSmsId(rawSmsId: String): List<UserCorrection> = emptyList()
            override suspend fun listForRawSmsIds(rawSmsIds: Collection<String>): List<UserCorrection> = emptyList()
            override suspend fun listAll(): List<UserCorrection> = emptyList()
        }

    fun emptyRawSmsRepository(): RawSmsRepository = EmptyRawSmsRepository()


    fun emptyReviewRepository(): ReviewRepository = EmptyReviewRepository()


    internal fun emptyFinancialTransactionRepository(): FinancialTransactionRepository =
        EmptyFinancialTransactionRepository()


    internal fun emptyParsedEventRepository(): ParsedEventRepository = EmptyParsedEventRepository()

    internal fun emptyAccountRegistry(): AccountRegistryRepository = StubAccountRegistryRepository()

    internal fun emptyCardRegistry(): CardRegistryRepository = NoOpCardRegistryRepository()
}
