package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.parsing.model.ParsedEventDetails
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.math.BigDecimal
import com.baraa.masroof.testsupport.NoOpAppLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionReclassificationServiceTest {
    private lateinit var db: MasroofDatabase
    private lateinit var ftRepo: RoomFinancialTransactionRepository
    private lateinit var parsedRepo: RoomParsedEventRepository
    private lateinit var rawRepo: RoomRawSmsRepository
    private lateinit var confirmation: OwnershipConfirmationService
    private lateinit var loans: RoomLoanRegistryRepository
    private lateinit var service: TransactionReclassificationService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
        parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
        rawRepo = RoomRawSmsRepository(db.rawSmsDao())
        val accounts = RoomAccountRegistryRepository.from(db)
        val cards = RoomCardRegistryRepository.from(db)
        loans = RoomLoanRegistryRepository.from(db)
        confirmation = OwnershipConfirmationService(accounts, cards, loans)
        service = TransactionReclassificationService(
            financialTransactionRepository = ftRepo,
            effectiveParsedEventProvider = EffectiveParsedEventProvider(
                parsedRepo,
                RoomUserCorrectionRepository(db.userCorrectionDao()),
            ),
            ownershipResolver = OwnershipResolver(accounts, cards, loans),
            ownershipConfirmationService = confirmation,
            persistence = com.baraa.masroof.data.repository.RoomTransactionReclassificationRepository(
                db,ftRepo,com.baraa.masroof.data.room.DatabaseAccessGate()),
                appLogService = NoOpAppLog,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun incomeToExternalTransferIn_updatesType() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        persistIncomeTransfer(smsId = "sms-in", counterparty = "DERAYAH", amount = "28093.33")
        val txId = TransactionIdFactory.fromRawSmsIds(listOf("sms-in"))
        val result = service.reclassify(txId, FinancialTransactionType.EXTERNAL_TRANSFER_IN)
        assertTrue(result is ReclassificationResult.Success)
        val updated = ftRepo.getById(txId)!!
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_IN, updated.type)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            updated.destinationContainerId,
        )
        val decision = db.reviewItemDao().findByRawSmsId("sms-in")!!
        assertEquals("USER_FINANCIAL_TYPE",decision.resolutionKind)
        assertEquals(txId,decision.resolvedTransactionId)
    }

    @Test fun moneyFromAnotherOwnedAccountIsATransferBetweenMyAccounts() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))
        persistIncomeTransfer(smsId = "sms-own", counterparty = "ME", amount = "100.00",
            source = AccountReference(Bank.BANK_ALJAZIRA, "3002"))
        val txId = TransactionIdFactory.fromRawSmsIds(listOf("sms-own"))
        assertTrue(service.reclassify(txId, FinancialTransactionType.EXTERNAL_TRANSFER_IN) is ReclassificationResult.Success)
        val updated = ftRepo.getById(txId)!!
        assertEquals(FinancialTransactionType.SELF_TRANSFER, updated.type)
        assertEquals(FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002"), updated.sourceContainerId)
        assertEquals(FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"), updated.destinationContainerId)
    }

    @Test fun decisionWriteFailureRollsBackTheFinancialEdit() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA,"3001"))
        persistIncomeTransfer(smsId="atomic-edit",counterparty="TEST",amount="100.00")
        val txId=TransactionIdFactory.fromRawSmsIds(listOf("atomic-edit"))
        val original=ftRepo.getById(txId)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_decision BEFORE INSERT ON review_item BEGIN SELECT RAISE(ABORT,'injected'); END")
        assertTrue(service.reclassify(txId,FinancialTransactionType.EXTERNAL_TRANSFER_IN) is ReclassificationResult.Rejected)
        assertEquals(original,ftRepo.getById(txId))
        assertEquals(null,db.reviewItemDao().findByRawSmsId("atomic-edit"))
    }

    @Test fun anAmountCorrectionBetweenAssemblyAndPostingInvalidatesTheOldEvidenceEvenWhenManuallyConfirmed() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA,"3001"))
        persistIncomeTransfer(smsId="racing-edit",counterparty="TEST",amount="100.00")
        val corrections=RoomUserCorrectionRepository(db.userCorrectionDao())
        val effective=EffectiveParsedEventProvider(parsedRepo,corrections)
        val assembledFrom=effective.findEffectiveByRawSmsId("racing-edit")!!
        corrections.save(com.baraa.masroof.domain.model.UserCorrection("new-amount","racing-edit",null,
            Money.of(BigDecimal("150.00"),Currency.SAR),null,null,Instant.now()))
        assertTrue(effective.findEffectiveByRawSmsId("racing-edit")!!.automationConfirmed)
        val guard=com.baraa.masroof.application.interpretation.AutomaticInterpretationWriteGuard(effective,
            com.baraa.masroof.data.repository.RoomReviewRepository(db.reviewItemDao()),ftRepo,
            inferenceCurrent={ true },inTransaction={ work -> db.withTransaction { work() } })
        var wrote=false
        val result=guard.write(listOf(assembledFrom)) { wrote=true;FinancialTransactionSaveResult.Saved }
        assertTrue(result is FinancialTransactionSaveResult.Conflict)
        assertTrue(!wrote)
        assertEquals("150.00",corrections.latestForRawSmsId("racing-edit")!!.correctedAmount!!.amount.toPlainString())
    }

    @Test
    fun pairedTransaction_isRejected() = runBlocking {
        listOf("sms-a", "sms-b").forEach { smsId ->
            rawRepo.insertIfAbsent(
                RawSms(
                    id = smsId,
                    sender = "AlJazira",
                    body = "body-$smsId",
                    bodyHash = SmsBodyHasher.sha256Hex("body-$smsId"),
                    receivedAt = Instant.parse("2026-08-01T10:00:00Z"),
                    deviceMessageId = smsId,
                ),
            )
        }
        val tx = FinancialTransaction(
            id = "tx-pair",
            type = FinancialTransactionType.SELF_TRANSFER,
            amount = Money.of("100.00", Currency.SAR),
            occurredAt = Instant.parse("2026-08-01T10:00:00Z"),
            sourceContainerId = null,
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = emptyList(),
        )
        ftRepo.save(tx, listOf("sms-a", "sms-b"))
        val result = service.reclassify("tx-pair", FinancialTransactionType.INCOME)
        assertTrue(result is ReclassificationResult.Rejected)
        assertEquals("paired_transaction_not_supported", (result as ReclassificationResult.Rejected).reason)
    }

    @Test
    fun feeToLoanRepayment_updatesTypeAndConfirmsLoan() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        loans.observe(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL), "sms-loan")
        persistFinancingFee(smsId = "sms-loan", amount = "3036.11")
        val txId = TransactionIdFactory.fromRawSmsIds(listOf("sms-loan"))
        val result = service.reclassify(txId, FinancialTransactionType.LOAN_REPAYMENT)
        assertTrue(result is ReclassificationResult.Success)
        val updated = ftRepo.getById(txId)!!
        assertEquals(FinancialTransactionType.LOAN_REPAYMENT, updated.type)
        assertEquals(
            FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            updated.sourceContainerId,
        )
        assertEquals(
            FinancialContainerIdFactory.loanId(Bank.BANK_ALJAZIRA, LoanType.PERSONAL),
            updated.destinationContainerId,
        )
        assertEquals(
            OwnershipStatus.OWNED,
            loans.resolve(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)),
        )
    }

    @Test
    fun loanRepaymentSaveFailure_leavesLoanOwnershipUnchanged() = runBlocking {
        confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
        val loan = LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)
        loans.observe(loan, "sms-loan-fail")
        persistFinancingFee(smsId = "sms-loan-fail", amount = "3036.11")
        val before = loans.resolve(loan)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_decision BEFORE INSERT ON review_item BEGIN SELECT RAISE(ABORT,'injected'); END")
        val result = service.reclassify(TransactionIdFactory.fromRawSmsIds(listOf("sms-loan-fail")), FinancialTransactionType.LOAN_REPAYMENT)
        assertTrue(result is ReclassificationResult.Rejected)
        assertEquals(OwnershipStatus.UNKNOWN, before)
        assertEquals(before, loans.resolve(loan))
    }

    private suspend fun persistFinancingFee(smsId: String, amount: String) {
        val body = "خصم: قسط تمويل\nمن: 3001\nالقسط: SAR $amount\nلـ: تمويل شخصي"
        rawRepo.insertIfAbsent(
            RawSms(
                id = smsId,
                sender = "AlJazira",
                body = body,
                bodyHash = SmsBodyHasher.sha256Hex(body),
                receivedAt = Instant.parse("2026-08-27T01:10:00Z"),
                deviceMessageId = smsId,
            ),
        )
        val event = ParsedEvent(
            id = "pe-$smsId",
            rawSmsId = smsId,
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.FINANCING_INSTALLMENT,
            direction = MoneyDirection.OUTGOING,
            parseStatus = ParseStatus.SUCCESS,
            amount = Money.of(amount, Currency.SAR),
            occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
            sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destinationAccountRef = null,
            cardRef = null,
            purchaseChannel = null,
            merchant = null,
            counterparty = "تمويل شخصي",
            bankNetworkType = null,
            confidence = Confidence(1.0),
        )
        parsedRepo.save(event, ParsedEventDetails(loanType = LoanType.PERSONAL))
        val tx = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf(smsId)),
            type = FinancialTransactionType.FEE,
            amount = Money.of(amount, Currency.SAR),
            occurredAt = event.occurredAt!!,
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = null,
            counterparty = "تمويل شخصي",
            categoryId = null,
            linkedParsedEventIds = listOf(event.id),
        )
        assertEquals(FinancialTransactionSaveResult.Saved, ftRepo.save(tx, listOf(smsId)))
    }

    private suspend fun persistIncomeTransfer(smsId: String, counterparty: String, amount: String,
        source: AccountReference = AccountReference(Bank.UNKNOWN, "9999")) {
        val body = "transfer-$smsId"
        rawRepo.insertIfAbsent(
            RawSms(
                id = smsId,
                sender = "Jazira Bank",
                body = body,
                bodyHash = SmsBodyHasher.sha256Hex(body),
                receivedAt = Instant.parse("2026-08-02T10:00:00Z"),
                deviceMessageId = smsId,
            ),
        )
        val event = ParsedEvent(
            id = "pe-$smsId",
            rawSmsId = smsId,
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.TRANSFER_IN,
            direction = MoneyDirection.INCOMING,
            parseStatus = ParseStatus.SUCCESS,
            amount = Money.of(amount, Currency.SAR),
            occurredAt = Instant.parse("2026-08-02T10:00:00Z"),
            sourceAccountRef = source,
            destinationAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            cardRef = null,
            purchaseChannel = null,
            merchant = null,
            counterparty = counterparty,
            bankNetworkType = null,
            confidence = Confidence(1.0),
        )
        parsedRepo.save(event, ParsedEventDetails())
        val tx = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf(smsId)),
            type = FinancialTransactionType.INCOME,
            amount = Money.of(amount, Currency.SAR),
            occurredAt = event.occurredAt!!,
            sourceContainerId = null,
            destinationContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            merchant = null,
            counterparty = counterparty,
            categoryId = null,
            linkedParsedEventIds = listOf(event.id),
        )
        assertEquals(FinancialTransactionSaveResult.Saved, ftRepo.save(tx, listOf(smsId)))
    }
}
