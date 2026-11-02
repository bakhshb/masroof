package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.hash.SmsBodyHasher
import org.junit.After
import org.junit.Before
import java.math.BigDecimal
import java.time.Instant

/** One fresh in-memory Room ledger per scenario; inherited setup/teardown never share state. */
abstract class ReconciliationTestFixture {
    protected lateinit var db: MasroofDatabase
    protected lateinit var rawRepo: RoomRawSmsRepository
    protected lateinit var parsedRepo: RoomParsedEventRepository
    protected lateinit var ftRepo: RoomFinancialTransactionRepository
    protected lateinit var accounts: RoomAccountRegistryRepository
    protected lateinit var cards: RoomCardRegistryRepository
    protected lateinit var loans: RoomLoanRegistryRepository
    protected lateinit var confirmation: OwnershipConfirmationService
    protected lateinit var reconciliation: TransactionReconciliationService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        accounts = RoomAccountRegistryRepository.from(db)
        cards = RoomCardRegistryRepository.from(db)
        loans = RoomLoanRegistryRepository.from(db)
        confirmation = OwnershipConfirmationService(accounts, cards, loans)
        reconciliation = TransactionReconciliationService(
            parsedEventRepository = parsedRepo,
            rawSmsRepository = rawRepo,
            financialTransactionRepository = ftRepo,
            ownershipResolver = OwnershipResolver(accounts, cards, loans),
            ownershipConfirmationService = confirmation,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    protected fun serviceIn(zone: java.time.ZoneId) = TransactionReconciliationService(
        parsedEventRepository = parsedRepo,
        rawSmsRepository = rawRepo,
        financialTransactionRepository = ftRepo,
        ownershipResolver = com.baraa.masroof.domain.ownership.OwnershipResolver(accounts, cards, loans),
        ownershipConfirmationService = confirmation,
        zoneId = zone,
    )

    protected suspend fun persistEvent(
        smsId: String,
        event: ParsedEvent,
        details: ParsedEventDetails = ParsedEventDetails(),
        at: Instant = Instant.parse("2026-08-01T12:00:00Z"),
        body: String = "body-$smsId",
    ) {
        rawRepo.insertIfAbsent(
            RawSms(
                id = smsId,
                sender = "AlJazira",
                body = body,
                receivedAt = at,
                deviceMessageId = smsId.removePrefix("sms-"),
                bodyHash = SmsBodyHasher.sha256Hex(body),
            ),
        )
        parsedRepo.save(event, details)
    }

    protected fun money(v: String) = Money.of(BigDecimal(v), Currency.SAR)

    protected fun event(
        id: String,
        rawSmsId: String,
        family: MessageFamily,
        amount: Money?,
        bank: Bank = Bank.BANK_ALJAZIRA,
        source: AccountReference? = null,
        destination: AccountReference? = null,
        card: CardReference? = null,
        network: BankNetworkType? = null,
        channel: PurchaseChannel? = null,
        merchant: String? = null,
        counterparty: String? = null,
        status: ParseStatus = ParseStatus.SUCCESS,
    ) = ParsedEvent(
        id = id,
        rawSmsId = rawSmsId,
        bank = bank,
        messageFamily = family,
        direction = MoneyDirection.OUTGOING,
        amount = amount,
        purchaseChannel = channel,
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
}
