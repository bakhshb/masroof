package com.baraa.masroof.application.transaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomManualReviewResolutionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.application.ingestion.ProcessRawSmsUseCase
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IntraBankSelfTransferIngestionTest {

    private val zoneId = ZoneId.of("Asia/Riyadh")

    private val outgoingBody =
        """
        حوالة صادرة الى حسابك الجاري
        من: 3001
        مبلغ: SAR 5,500.00
        إلى: 3002
        في: 2026-08-27 07:36
        """.trimIndent()

    private val incomingBody =
        """
        حوالة واردة داخلية
        مبلغ: SAR 5,500.00
        إلى: 3002
        اسم المرسل: براء بخش
        رقم حساب المرسل: 3001
        البنك المرسل: بنك الجزيرة
        في: 2026-08-27 07:36
        """.trimIndent()

  private lateinit var db: MasroofDatabase
  private lateinit var rawRepo: RoomRawSmsRepository
  private lateinit var parsedRepo: RoomParsedEventRepository
  private lateinit var ftRepo: RoomFinancialTransactionRepository
  private lateinit var reviewRepo: ReviewRepository
  private lateinit var accounts: RoomAccountRegistryRepository
  private lateinit var cards: RoomCardRegistryRepository
  private lateinit var confirmation: OwnershipConfirmationService
  private lateinit var ingestion: ProcessRawSmsUseCase
  private lateinit var reviewWorkflow: ReviewWorkflowService

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    rawRepo = RoomRawSmsRepository(db.rawSmsDao())
    parsedRepo = RoomParsedEventRepository(db.parsedEventDao())
    ftRepo = RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao())
    reviewRepo = RoomReviewRepository(db.reviewItemDao())
    accounts = RoomAccountRegistryRepository.from(db)
    cards = RoomCardRegistryRepository.from(db)
    confirmation = OwnershipConfirmationService(accounts, cards, NoOpLoanRegistryRepository)

    val ownershipResolver = OwnershipResolver(accounts, cards, NoOpLoanRegistryRepository)
    val reconciliation = TransactionReconciliationService(
      parsedEventRepository = parsedRepo,
      rawSmsRepository = rawRepo,
      financialTransactionRepository = ftRepo,
      ownershipResolver = ownershipResolver,
      reviewRepository = reviewRepo,
      zoneId = zoneId,
    )
    val reviewQueueUpdater = ReviewQueueUpdater(
      reviewRepository = reviewRepo,
      financialTransactionRepository = ftRepo,
      clock = InstantClock.System,
    )
    ingestion = ProcessRawSmsUseCase(
      rawSmsRepository = rawRepo,
      parsedEventRepository = parsedRepo,
      bankSmsRegistry = BankSmsRegistry(listOf(AlJaziraSmsAdapter())),
      ownershipDiscovery = OwnershipDiscoveryService(accounts, cards, NoOpLoanRegistryRepository),
      reconciliation = reconciliation,
      reviewQueueUpdater = reviewQueueUpdater,
    )
    reviewWorkflow = ReviewWorkflowService(
      reviewRepository = reviewRepo,
      userCorrectionRepository = RoomUserCorrectionRepository(db.userCorrectionDao()),
      financialTransactionRepository = ftRepo,
      rawSmsRepository = rawRepo,
      ownershipResolver = ownershipResolver,
      ownershipConfirmationService = confirmation,
      effectiveParsedEventProvider = com.baraa.masroof.application.review.EffectiveParsedEventProvider(
        parsedEventRepository = parsedRepo,
        userCorrectionRepository = RoomUserCorrectionRepository(db.userCorrectionDao()),
      ),
      parsedEventRepository = parsedRepo,
      reconciliationService = reconciliation,
      reviewQueueUpdater = reviewQueueUpdater,
      manualReviewResolutionRepository = RoomManualReviewResolutionRepository(
        database = db,
        financialTransactionRepository = ftRepo,
      ),
      clock = InstantClock.System,
    )
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun ingest_exactBugSms_bothOwned_selfTransferInSalaryPeriod() = runBlocking {
    confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
    confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))

    val receivedAt = Instant.parse("2026-08-26T20:00:00Z") // inbox DATE before SMS body local time
    ingest("sms-out", outgoingBody, receivedAt)
    ingest("sms-in", incomingBody, receivedAt)

    assertEquals(1, ftRepo.listAll().size)
    val tx = ftRepo.listAll().single()
    assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
    assertEquals(
      FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
      tx.sourceContainerId,
    )
    assertEquals(
      FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002"),
      tx.destinationContainerId,
    )
    assertEquals(Money.of("5500.00", Currency.SAR), tx.amount)
    assertEquals(setOf("sms-in", "sms-out"), ftRepo.listRawSmsIds(tx.id).toSet())

    val period = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-27"), 27)
    val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zoneId)
    val end = FinancialPeriodPolicy.toExclusiveEndInstant(period.endDateExclusive, zoneId)
    val inPeriod = ftRepo.listOccurredBetween(start, end)
    assertEquals(1, inPeriod.size)
    assertEquals(tx.id, inPeriod.single().id)
    assertEquals(
      Instant.parse("2026-08-27T04:36:00Z"),
      tx.occurredAt,
    )
  }

  @Test
  fun ingest_unknownOwnership_reviewQueueThenSelfTransferAfterConfirm() = runBlocking {
    ingest("sms-out", outgoingBody)
    ingest("sms-in", incomingBody)

    assertTrue(ftRepo.listAll().isEmpty())
    val required = reviewRepo.listRequired()
    assertEquals(2, required.size)
    assertTrue(required.all { it.kind == ReviewKind.PENDING_MATCH })

    confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
    confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))
    reviewWorkflow.refreshReviewQueue()

    assertEquals(1, ftRepo.listAll().size)
    assertEquals(FinancialTransactionType.SELF_TRANSFER, ftRepo.listAll().single().type)
    assertTrue(reviewRepo.listRequired().isEmpty())
  }

  @Test
  fun duplicateReimport_thenReparse_pairsSelfTransfer() = runBlocking {
    confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
    confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))

    val out = rawSms("sms-out", outgoingBody)
    val inSms = rawSms("sms-in", incomingBody)
    assertTrue(ingestion.ingest(out) is SmsIngestionResult.Parsed)
    assertTrue(ingestion.ingest(inSms) is SmsIngestionResult.Parsed)

    assertEquals(1, ftRepo.listAll().size)

    assertTrue(ingestion.ingest(out) is SmsIngestionResult.Duplicate)
    assertTrue(ingestion.ingest(inSms) is SmsIngestionResult.Duplicate)

    assertTrue(ingestion.reparseStored(out) is SmsIngestionResult.Parsed)
    assertTrue(ingestion.reparseStored(inSms) is SmsIngestionResult.Parsed)

    assertEquals(1, ftRepo.listAll().size)
    assertEquals(FinancialTransactionType.SELF_TRANSFER, ftRepo.listAll().single().type)
  }

  @Test
  fun ingest_bareDateTimeInSmsBody_bothOwned_selfTransferInSalaryPeriod() = runBlocking {
    confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3001"))
    confirmation.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA, "3002"))

    val outgoingBare =
      """
      حوالة صادرة الى حسابك الجاري
      من: 3001
      مبلغ: SAR 5,500.00
      إلى: 3002
      07:36 27-08-2026
      """.trimIndent()

    val incomingBare =
      """
      حوالة واردة داخلية
      مبلغ: SAR 5,500.00
      إلى: 3002
      اسم المرسل: براء بخش
      رقم حساب المرسل: 3001
      البنك المرسل: بنك الجزيرة
      07:36 27-08-2026
      """.trimIndent()

    val receivedAt = Instant.parse("2026-08-26T20:00:00Z")
    ingest("sms-out-bare", outgoingBare, receivedAt)
    ingest("sms-in-bare", incomingBare, receivedAt)

    assertEquals(1, ftRepo.listAll().size)
    val tx = ftRepo.listAll().single()
    assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
    assertEquals(Instant.parse("2026-08-27T04:36:00Z"), tx.occurredAt)

    val period = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-27"), 27)
    val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zoneId)
    val end = FinancialPeriodPolicy.toExclusiveEndInstant(period.endDateExclusive, zoneId)
    assertEquals(1, ftRepo.listOccurredBetween(start, end).size)
  }

  private suspend fun ingest(id: String, body: String, receivedAt: Instant = Instant.parse("2026-08-27T04:36:00Z")) {
    val result = ingestion.ingest(rawSms(id, body, receivedAt))
    assertTrue(result is SmsIngestionResult.Parsed)
  }

  private fun rawSms(id: String, body: String, receivedAt: Instant = Instant.parse("2026-08-27T04:36:00Z")) =
    RawSms(
      id = id,
      sender = "AlJazira",
      body = body,
      receivedAt = receivedAt,
      deviceMessageId = id,
      bodyHash = SmsBodyHasher.sha256Hex(body),
    )
}
