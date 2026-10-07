package com.baraa.masroof.testsupport

import android.content.Context
import androidx.room.Room
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.matching.TransactionMatcher
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.hash.SmsBodyHasher
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Five independent reconciliation baselines on one empty in-memory database.
 *
 * Each seed assumes it is the only case loaded. Full reconciliation is the
 * behavior later scoped passes must match. Ids are namespaced so a future
 * combined database does not collide.
 */
class ReconciliationCharacterizationFixture private constructor(
    private val db: MasroofDatabase,
) : AutoCloseable {
    private val rawRepo = RoomRawSmsRepository(db.rawSmsDao())
    private val parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
    private val ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
    private val accounts = RoomAccountRegistryRepository.from(db)
    private val cards = RoomCardRegistryRepository.from(db)
    private val loans = RoomLoanRegistryRepository.from(db)
    private val corrections = RoomUserCorrectionRepository(db.userCorrectionDao())
    private val confirmation = OwnershipConfirmationService(accounts, cards, loans)

    fun reconciliation(): TransactionReconciliationService = service(withCorrections = false)

    fun reconciliationWithCorrections(): TransactionReconciliationService = service(withCorrections = true)

    suspend fun seedNonTransfer() {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, PURCHASE_CARD))
        persist(
            smsId = PURCHASE_SMS,
            at = Instant.parse("2026-08-04T09:00:00Z"),
            event = event(
                id = PURCHASE_EVENT,
                rawSmsId = PURCHASE_SMS,
                family = MessageFamily.PURCHASE,
                amount = Money.of(PURCHASE_AMOUNT, Currency.SAR),
                card = CardReference(Bank.BANK_ALJAZIRA, PURCHASE_CARD),
                merchant = "Keeta",
            ),
        )
    }

    suspend fun seedExternalTransfer() {
        confirmation.confirmAccountOwned(source(EXTERNAL_SOURCE))
        persist(
            smsId = EXTERNAL_SMS,
            at = Instant.parse("2026-08-05T09:00:00Z"),
            details = ParsedEventDetails(occurredAtLocal = LocalDateTime.parse("2026-08-05T12:00:00")),
            event = event(
                id = EXTERNAL_EVENT,
                rawSmsId = EXTERNAL_SMS,
                family = MessageFamily.TRANSFER_OUT,
                amount = Money.of(EXTERNAL_AMOUNT, Currency.SAR),
                source = source(EXTERNAL_SOURCE),
                destination = source(EXTERNAL_DESTINATION),
                network = BankNetworkType.INTRA_BANK,
                counterparty = "Other Person",
            ),
        )
    }

    suspend fun seedMatchedSelfTransfer() {
        confirmation.confirmAccountOwned(source(SELF_SOURCE))
        confirmation.confirmAccountOwned(source(SELF_DESTINATION))
        val at = Instant.parse("2026-08-06T04:36:00Z")
        persist(
            smsId = SELF_OUT_SMS,
            at = at,
            event = event(
                id = SELF_OUT_EVENT,
                rawSmsId = SELF_OUT_SMS,
                family = MessageFamily.TRANSFER_OUT,
                amount = Money.of(SELF_AMOUNT, Currency.SAR),
                source = source(SELF_SOURCE),
                destination = source(SELF_DESTINATION),
                network = BankNetworkType.INTRA_BANK,
                counterparty = "براء بخش",
            ),
        )
        persist(
            smsId = SELF_IN_SMS,
            at = at,
            event = event(
                id = SELF_IN_EVENT,
                rawSmsId = SELF_IN_SMS,
                family = MessageFamily.TRANSFER_IN,
                amount = Money.of(SELF_AMOUNT, Currency.SAR),
                source = source(SELF_SOURCE),
                destination = source(SELF_DESTINATION),
                network = BankNetworkType.INTRA_BANK,
            ),
        )
    }

    /**
     * Posts the outgoing leg as an external transfer. The incoming counterpart
     * is not stored yet, so the row is a stale single leg.
     */
    suspend fun prepareStaleSingleLeg(): PostedTransactionShape {
        confirmation.confirmAccountOwned(source(STALE_SOURCE))
        persist(
            smsId = STALE_OUT_SMS,
            at = STALE_OUT_RECEIVED_AT,
            details = ParsedEventDetails(occurredAtLocal = STALE_OCCURRED_LOCAL),
            event = event(
                id = STALE_OUT_EVENT,
                rawSmsId = STALE_OUT_SMS,
                family = MessageFamily.TRANSFER_OUT,
                amount = Money.of(STALE_AMOUNT, Currency.SAR),
                source = source(STALE_SOURCE),
                destination = source(STALE_DESTINATION),
                network = BankNetworkType.INTRA_BANK,
                counterparty = "براء بخش",
            ),
        )
        reconciliation().reconcileStoredEvents()
        return snapshot().single()
    }

    suspend fun seedStaleIncomingCounterpart() {
        confirmation.confirmAccountOwned(source(STALE_DESTINATION))
        persist(
            smsId = STALE_IN_SMS,
            at = STALE_OUT_RECEIVED_AT.plus(TransactionMatcher.TRANSFER_MATCH_WINDOW.multipliedBy(3)),
            details = ParsedEventDetails(occurredAtLocal = STALE_OCCURRED_LOCAL.plusMinutes(2)),
            event = event(
                id = STALE_IN_EVENT,
                rawSmsId = STALE_IN_SMS,
                family = MessageFamily.TRANSFER_IN,
                amount = Money.of(STALE_AMOUNT, Currency.SAR),
                source = source(STALE_SOURCE),
                destination = source(STALE_DESTINATION),
                network = BankNetworkType.INTRA_BANK,
            ),
        )
    }

    suspend fun seedReviewRequiredPurchase() {
        confirmation.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, CORRECTION_CARD))
        persist(
            smsId = CORRECTION_SMS,
            at = Instant.parse("2026-08-08T09:00:00Z"),
            event = event(
                id = CORRECTION_EVENT,
                rawSmsId = CORRECTION_SMS,
                family = MessageFamily.PURCHASE,
                amount = null,
                card = CardReference(Bank.BANK_ALJAZIRA, CORRECTION_CARD),
                merchant = "Keeta",
                status = ParseStatus.REVIEW_REQUIRED,
            ),
        )
    }

    suspend fun saveAmountCorrection() {
        corrections.save(
            UserCorrection(
                id = CORRECTION_ID,
                targetRawSmsId = CORRECTION_SMS,
                correctedType = null,
                correctedAmount = Money.of(CORRECTION_AMOUNT, Currency.SAR),
                correctedMerchant = null,
                correctedCounterparty = null,
                createdAt = Instant.parse("2026-08-08T12:00:00Z"),
            ),
        )
    }

    suspend fun snapshot(): List<PostedTransactionShape> =
        ftRepo.listAll().map { transaction ->
            PostedTransactionShape(
                id = transaction.id,
                type = transaction.type,
                amount = transaction.amount,
                rawSmsIds = ftRepo.listRawSmsIds(transaction.id).toSet(),
                linkedParsedEventIds = transaction.linkedParsedEventIds,
                sourceContainerId = transaction.sourceContainerId,
                destinationContainerId = transaction.destinationContainerId,
                merchant = transaction.merchant,
            )
        }.sortedBy { it.id }

    override fun close() {
        db.close()
    }

    private fun service(withCorrections: Boolean) = TransactionReconciliationService(
        parsedEventRepository = parsedRepo,
        rawSmsRepository = rawRepo,
        financialTransactionRepository = ftRepo,
        ownershipResolver = OwnershipResolver(accounts, cards, loans),
        ownershipConfirmationService = confirmation,
        effectiveParsedEventProvider = if (withCorrections) {
            EffectiveParsedEventProvider(parsedRepo, corrections)
        } else {
            null
        },
        zoneId = ZONE,
    )

    private suspend fun persist(
        smsId: String,
        event: ParsedEvent,
        details: ParsedEventDetails = ParsedEventDetails(),
        at: Instant,
    ) {
        val body = "body-$smsId"
        rawRepo.insertIfAbsent(
            RawSms(
                id = smsId,
                sender = "AlJazira",
                body = body,
                receivedAt = at,
                deviceMessageId = smsId,
                bodyHash = SmsBodyHasher.sha256Hex(body),
            ),
        )
        parsedRepo.save(event, details)
    }

    private fun source(masked: String) = AccountReference(Bank.BANK_ALJAZIRA, masked)

    private fun event(
        id: String,
        rawSmsId: String,
        family: MessageFamily,
        amount: Money?,
        source: AccountReference? = null,
        destination: AccountReference? = null,
        card: CardReference? = null,
        network: BankNetworkType? = null,
        merchant: String? = null,
        counterparty: String? = null,
        status: ParseStatus = ParseStatus.SUCCESS,
    ) = ParsedEvent(
        id = id,
        rawSmsId = rawSmsId,
        bank = Bank.BANK_ALJAZIRA,
        messageFamily = family,
        direction = MoneyDirection.OUTGOING,
        amount = amount,
        purchaseChannel = null,
        sourceAccountRef = source,
        destinationAccountRef = destination,
        cardRef = card,
        merchant = merchant,
        counterparty = counterparty,
        occurredAt = null,
        bankNetworkType = network,
        confidence = Confidence(1.0),
        parseStatus = status,
    )

    data class PostedTransactionShape(
        val id: String,
        val type: FinancialTransactionType,
        val amount: Money,
        val rawSmsIds: Set<String>,
        val linkedParsedEventIds: List<String>,
        val sourceContainerId: String?,
        val destinationContainerId: String?,
        val merchant: String?,
    )

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Riyadh")

        const val PURCHASE_SMS = "sms-char-purchase"
        const val PURCHASE_EVENT = "pe-char-purchase"
        const val PURCHASE_CARD = "7271"
        const val PURCHASE_AMOUNT = "51.99"

        const val EXTERNAL_SMS = "sms-char-external"
        const val EXTERNAL_EVENT = "pe-char-external"
        const val EXTERNAL_SOURCE = "4101"
        const val EXTERNAL_DESTINATION = "4102"
        const val EXTERNAL_AMOUNT = "80.00"

        const val SELF_OUT_SMS = "sms-char-self-out"
        const val SELF_IN_SMS = "sms-char-self-in"
        const val SELF_OUT_EVENT = "pe-char-self-out"
        const val SELF_IN_EVENT = "pe-char-self-in"
        const val SELF_SOURCE = "4201"
        const val SELF_DESTINATION = "4202"
        const val SELF_AMOUNT = "5500.00"

        const val STALE_OUT_SMS = "sms-char-stale-out"
        const val STALE_IN_SMS = "sms-char-stale-in"
        const val STALE_OUT_EVENT = "pe-char-stale-out"
        const val STALE_IN_EVENT = "pe-char-stale-in"
        const val STALE_SOURCE = "4301"
        const val STALE_DESTINATION = "4302"
        const val STALE_AMOUNT = "4445.67"
        val STALE_OCCURRED_LOCAL: LocalDateTime = LocalDateTime.parse("2026-08-07T12:00:00")
        val STALE_OUT_RECEIVED_AT: Instant = Instant.parse("2026-08-07T09:00:00Z")

        const val CORRECTION_SMS = "sms-char-corrected"
        const val CORRECTION_EVENT = "pe-char-corrected"
        const val CORRECTION_CARD = "7272"
        const val CORRECTION_ID = "corr-char-amount"
        const val CORRECTION_AMOUNT = "42.00"

        fun open(context: Context): ReconciliationCharacterizationFixture {
            val db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            return ReconciliationCharacterizationFixture(db)
        }

        fun purchaseContainerId(): String =
            FinancialContainerIdFactory.cardId(Bank.BANK_ALJAZIRA, PURCHASE_CARD)

        fun accountContainerId(masked: String): String =
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, masked)
    }
}
