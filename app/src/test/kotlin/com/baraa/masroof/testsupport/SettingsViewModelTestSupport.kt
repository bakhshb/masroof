package com.baraa.masroof.testsupport

import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.settings.SettingsRegistryWorkflow
import com.baraa.masroof.application.transaction.TransactionReclassificationService
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.application.transaction.TransactionRestoreService
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.time.InstantClock

internal object SettingsViewModelTestSupport {
    fun settingsRegistryWorkflow(
        cards: CardRegistryRepository = emptyCardRegistry(),
        accounts: AccountRegistryRepository = emptyAccountRegistry(),
        loans: com.baraa.masroof.domain.repository.LoanRegistryRepository = NoOpLoanRegistryRepository,
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
        )

    fun noOpRestoreService(): TransactionRestoreService =
        TransactionRestoreService(
            reviewRepository = emptyReviewRepository(),
            financialTransactionRepository = emptyFinancialTransactionRepository(),
            reconciliation = TransactionReconciliationService(
                parsedEventRepository = emptyParsedEventRepository(),
                rawSmsRepository = emptyRawSmsRepository(),
                financialTransactionRepository = emptyFinancialTransactionRepository(),
                ownershipResolver = OwnershipResolver(
                    accountRegistry = emptyAccountRegistry(),
                    cardRegistry = emptyCardRegistry(),
                    loanRegistry = NoOpLoanRegistryRepository,
                ),
            ),
            reclassification = TransactionReclassificationService(
                financialTransactionRepository = emptyFinancialTransactionRepository(),
                effectiveParsedEventProvider = EffectiveParsedEventProvider(
                    parsedEventRepository = emptyParsedEventRepository(),
                    userCorrectionRepository = object : com.baraa.masroof.domain.repository.UserCorrectionRepository {
                        override suspend fun save(correction: com.baraa.masroof.domain.model.UserCorrection) = Unit
                        override suspend fun latestForRawSmsId(rawSmsId: String) = null
                        override suspend fun listForRawSmsId(rawSmsId: String): List<UserCorrection> =
                            emptyList()
                    },
                ),
                ownershipResolver = OwnershipResolver(
                    accountRegistry = emptyAccountRegistry(),
                    cardRegistry = emptyCardRegistry(),
                    loanRegistry = NoOpLoanRegistryRepository,
                ),
            ),
            clock = InstantClock.System,
        )

    fun emptyRawSmsRepository(): RawSmsRepository =
        object : RawSmsRepository {
            override suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult =
                RawSmsInsertResult.Inserted

            override suspend fun getById(id: String): RawSms? = null

            override suspend fun existsById(id: String): Boolean = false

            override suspend fun findByDeviceMessageId(deviceMessageId: String): RawSms? = null
            override suspend fun listIdsByReceivedAt(): List<String> = emptyList()

            override suspend fun findCrossSourceNearDuplicate(
                sender: String,
                bodyHash: String,
                fromInclusive: java.time.Instant,
                toInclusive: java.time.Instant,
                lookingForLiveRow: Boolean,
            ): RawSms? = null
        }

    private fun emptyReviewRepository(): ReviewRepository =
        object : ReviewRepository {
            override suspend fun getById(id: String) = null
            override suspend fun findByRawSmsId(rawSmsId: String) = null
            override suspend fun listRequired() = emptyList<com.baraa.masroof.domain.model.ReviewItem>()
            override suspend fun listIgnored() = emptyList<com.baraa.masroof.domain.model.ReviewItem>()
            override suspend fun listAll() = emptyList<com.baraa.masroof.domain.model.ReviewItem>()
            override suspend fun upsertRequired(
                rawSmsId: String,
                kind: com.baraa.masroof.domain.model.ReviewKind,
                reasons: List<String>,
                now: java.time.Instant,
            ) = error("unused")
            override suspend fun markResolved(
                id: String,
                resolutionKind: com.baraa.masroof.domain.model.ReviewResolutionKind,
                resolvedAt: java.time.Instant,
                resolvedTransactionId: String?,
            ) = null
        }

    internal fun emptyFinancialTransactionRepository(): FinancialTransactionRepository =
        object : FinancialTransactionRepository {
            override suspend fun getById(id: String) = null
            override suspend fun findByRawSmsId(rawSmsId: String) = null
            override suspend fun listAll() = emptyList<com.baraa.masroof.domain.model.FinancialTransaction>()
            override suspend fun listOccurredBetween(
                startInclusive: java.time.Instant,
                endExclusive: java.time.Instant,
            ) = emptyList<com.baraa.masroof.domain.model.FinancialTransaction>()
            override suspend fun save(
                transaction: com.baraa.masroof.domain.model.FinancialTransaction,
                rawSmsIds: Collection<String>,
            ) = FinancialTransactionSaveResult.Saved
            override suspend fun update(transaction: com.baraa.masroof.domain.model.FinancialTransaction) = false
            override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String) = false
            override suspend fun unlinkRawSms(rawSmsId: String) = false
            override suspend fun listRawSmsIds(transactionId: String) = emptyList<String>()
            override suspend fun isRawSmsLinked(rawSmsId: String) = false
            override suspend fun updateAppliedExchangeRate(
                id: String,
                exchangeRate: java.math.BigDecimal,
                source: com.baraa.masroof.domain.model.ExchangeRateSource,
            ) = false
            override suspend fun linkRawSmsIfAbsent(transactionId: String, rawSmsId: String) = false
        }

    internal fun emptyParsedEventRepository(): ParsedEventRepository =
        object : ParsedEventRepository {
            override suspend fun save(
                event: com.baraa.masroof.domain.model.ParsedEvent,
                details: ParsedEventDetails,
            ) = Unit
            override suspend fun getById(id: String) = null
            override suspend fun findByRawSmsId(rawSmsId: String) = null
            override suspend fun deleteByRawSmsId(rawSmsId: String) = Unit
            override suspend fun listAll() =
                emptyList<com.baraa.masroof.parsing.repository.ParsedEventRecord>()

            override suspend fun listReceivedBetween(
                startInclusive: java.time.Instant,
                endExclusive: java.time.Instant,
            ) = emptyList<com.baraa.masroof.parsing.repository.ParsedEventRecord>()
        }

    internal fun emptyAccountRegistry(): AccountRegistryRepository =
        object : AccountRegistryRepository {
            override suspend fun observe(
                reference: com.baraa.masroof.domain.model.AccountReference,
                rawSmsId: String,
            ) = Unit
            override suspend fun setOwnership(
                reference: com.baraa.masroof.domain.model.AccountReference,
                status: com.baraa.masroof.domain.model.OwnershipStatus,
            ) = Unit
            override suspend fun resolve(reference: com.baraa.masroof.domain.model.AccountReference) =
                com.baraa.masroof.domain.model.OwnershipStatus.UNKNOWN
            override suspend fun get(reference: com.baraa.masroof.domain.model.AccountReference) = null
            override suspend fun listAll() = emptyList<com.baraa.masroof.domain.model.AccountRegistryEntry>()
            override suspend fun updateDisplayName(
                reference: com.baraa.masroof.domain.model.AccountReference,
                displayName: String?,
            ) = Unit
            override suspend fun updateAccountType(
                reference: com.baraa.masroof.domain.model.AccountReference,
                accountType: com.baraa.masroof.domain.model.AccountType,
            ) = Unit
        }

    internal fun emptyCardRegistry(): CardRegistryRepository = NoOpCardRegistryRepository()
}
