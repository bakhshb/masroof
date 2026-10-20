package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.bank.*
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.verification.FinancialArchiveFixture
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.dashboard.producer.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.time.*
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class RoomBetaConfigTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: BetaDatabase
    private val sql = CopyOnWriteArrayList<String>()
    private val zone = ZoneId.of("Asia/Riyadh")
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val query = DashboardQuery(Environment.BETA, Instant.parse("2026-09-30T21:00:00Z"), Instant.parse("2026-10-31T21:00:00Z"), zone, Currency.SAR)
    private val bank = BankId("BANK_ALJAZIRA")
    private val account = OwnedAsset(AssetId(Environment.BETA, bank, "account"), AssetKind.ACCOUNT, "3001")
    private val facility = OwnedAsset(AssetId(Environment.BETA, bank, "facility"), AssetKind.CREDIT_FACILITY)
    private val card = OwnedAsset(AssetId(Environment.BETA, bank, "card"), AssetKind.CREDIT_CARD, "7271", facility.id, CreditCardRole.PRIMARY)
    private fun openDb() = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME)
        .setQueryCallback({ query, _ -> sql += query }, Executor { it.run() }).build()
    @Before fun open() { BetaContainer.resetOffline(context); db = openDb() }
    @After fun close() { db.close(); BetaContainer.resetOffline(context) }
    private fun controls() = RoomBetaDashboardConfiguration(db)
    private fun source() = RoomBetaDashboardSource(db, clock = Clock.fixed(now, ZoneOffset.UTC))
    private fun draft(paid: Boolean = false) = BetaDashboardConfigurationDraft(BetaSalaryCycle(27), commitments = listOf(
        BetaRecurringCommitment("rent", "Synthetic rent", Money.of("100", Currency.SAR), 5, YearMonth.of(2026, 1),
            paidMonths = if (paid) setOf(YearMonth.of(2026, 10)) else emptySet())))

    @Test fun salaryAndCommitmentEditsPreserveRegistryPauseAndSourceFacts() = runBlocking {
        val expected=com.baraa.masroof.lab.beta.verification.BetaCommitmentPreservationChecks.editsPreserveRegistryFacts(db)
        db.close();db=openDb()
        assertEquals(expected,db.dashboardConfigurationDao().commitments().single())
    }

    @Test fun durableAuthorityAndReadOnly() = runBlocking {
        val unknown = source().load(query)
        assertFalse(unknown.coverage.completeUserCommitments); assertNull(unknown.periods.salary)
        val saved = controls().save(0, draft(true)) as BetaDashboardConfigurationWrite.Saved
        assertEquals(1, saved.configuration.revision)
        db.close(); db = openDb(); assertEquals(saved.configuration, controls().read())
        sql.clear()
        val batch = source().load(query)
        val result = BetaDashboardBuilder.build(batch)
        assertTrue(batch.coverage.completeUserCommitments)
        assertEquals(Instant.parse("2026-09-26T21:00:00Z"), batch.periods.salary!!.from)
        assertEquals(Money.of("100", Currency.SAR), result.snapshot.commitments!!.paid)
        assertEquals(Money.of("0", Currency.SAR), result.snapshot.commitments!!.remaining)
        assertTrue(batch.rows.isEmpty())
        assertEquals(1, sql.count { it.startsWith("SELECT", true) && it.contains("FINANCIAL_CONFIGURATION") })
        assertFalse(sql.any { it.trimStart().matches(Regex("(?is)(INSERT|UPDATE|DELETE|ALTER|CREATE|REPLACE).*")) })
        assertFalse(sql.any { it.contains("baselineJson") || it.contains("candidateJson") || it.contains("body") || it.contains("beta_ledger_history") })
        val changed = controls().save(1, BetaDashboardConfigurationDraft(BetaSalaryCycle(27))) as BetaDashboardConfigurationWrite.Saved
        assertEquals(2, changed.configuration.revision)
        val empty = source().load(query)
        assertNotEquals(batch.revision.token, empty.revision.token)
        assertEquals(Money.of("0", Currency.SAR), BetaDashboardBuilder.build(empty).snapshot.commitments!!.total)
    }

    @Test fun postedSourcePaymentOutsideCalendarButInsideSalaryPaysTheUserCommitmentReadOnly()=runBlocking {
        db.bankSelectionDao().upsert(BetaBankSelectionEntity(bank.value,"ENABLED"))
        BetaAssetRegistry(db).register(account)
        val key=PostingKey(Environment.BETA,"purchase-source")
        val input=FinancialClassificationInput(key,PostingRevision(0,1),FinancialType.EXPENSE,
            Money.of("125",Currency.SAR),TransactionTime(Instant.parse("2026-09-30T12:00:00Z"),zone),
            FinancialEndpoint.Owned(account.id),FinancialEndpoint.External,mapOf(account.id to account),setOf("source-evidence"),true)
        assertTrue(RoomBetaFinancialLedger(db).post(input) is FinancialPostingResult.Applied)
        val definition=draft().commitments.single().copy(sourceTransactionId=key.transactionId)
        assertTrue(controls().save(0,draft().copy(commitments=listOf(definition))) is BetaDashboardConfigurationWrite.Saved)
        sql.clear()
        val batch=source().load(query)
        val user=BetaDashboardBuilder.build(batch).snapshot.commitments!!.rows.single()
        assertEquals(PaymentStatus.PAID,user.status);assertEquals(Money.of("125",Currency.SAR),user.amount)
        assertEquals(LocalDate.of(2026,9,30),user.expectedDate)
        assertFalse(sql.any {it.trimStart().matches(Regex("(?is)(INSERT|UPDATE|DELETE|ALTER|CREATE|REPLACE).*"))})
        assertEquals(1,batch.rows.size)
    }

    @Test fun atomicRevisionAndValidation() = runBlocking {
        assertTrue(controls().save(0, draft().copy(salary = BetaSalaryCycle(0))) is BetaDashboardConfigurationWrite.Invalid)
        assertTrue(controls().save(0, draft().copy(salary = BetaSalaryCycle(27, ZoneId.of("UTC")))) is BetaDashboardConfigurationWrite.Invalid)
        val item = draft().commitments.single()
        for (invalid in listOf(item.copy(amount = Money.of("1", Currency.USD)), item.copy(label = ""), item.copy(dueDay = 32),
            item.copy(ends = YearMonth.of(2025, 1)), item.copy(paidMonths = setOf(YearMonth.of(2025, 1))))) {
            assertTrue(controls().save(0, draft().copy(commitments = listOf(invalid))) is BetaDashboardConfigurationWrite.Invalid)
        }
        assertTrue(controls().save(0, draft().copy(commitments = listOf(item, item))) is BetaDashboardConfigurationWrite.Invalid)
        assertTrue(controls().save(0, draft().copy(statements = listOf(BetaFacilityCycle(facility.id, 10)))) is BetaDashboardConfigurationWrite.Invalid)
        assertEquals(0, controls().read().revision)
        val results = listOf(async { controls().save(0, draft()) }, async { controls().save(0, draft(true)) }).awaitAll()
        assertEquals(1, results.count { it is BetaDashboardConfigurationWrite.Saved })
        assertEquals(1, results.count { it is BetaDashboardConfigurationWrite.Conflict })
        val current = controls().read()
        assertEquals(current, (controls().save(current.revision, current.draft) as BetaDashboardConfigurationWrite.Saved).configuration)
        assertTrue(source().load(query).rows.isEmpty()) // Manual paid state creates no financial posting.
    }

    private suspend fun statement(at: Instant = Instant.parse("2026-09-20T12:00:00Z"), dueDate: LocalDate = LocalDate.of(2026, 10, 5)) {
        for (asset in listOf(account, facility, card)) BetaAssetRegistry(db).register(asset)
        captureStatement("statement", at, dueDate)
        assertTrue(controls().save(0, BetaDashboardConfigurationDraft(BetaSalaryCycle(27), listOf(BetaFacilityCycle(facility.id, 10)))) is BetaDashboardConfigurationWrite.Saved)
    }
    private suspend fun captureStatement(id: String, at: Instant, dueDate: LocalDate) {
        val store = RoomBetaEvidenceStore(db)
        val route = EvidenceRoute(RouteKind.MATCHED, setOf(bank), "synthetic")
        store.capture(CapturedEvidence(id, EvidenceRequest("fixture", EvidenceOrigin.EXPLICIT_SAMPLE),
            EvidenceMessage(id, "AlJazira", "synthetic statement", at, ReceiptTimeSource.USER_SUPPLIED), at, route, emptyList()))
        store.begin(id, at)
        val facts = Facts(MessageFamily.NON_FINANCIAL, card = CardReference(bank, "7271"), cardChannel = CardChannel.STATEMENT,
            outstandingBalance = Money.of("100", Currency.SAR), paymentDueDate = dueDate, occurredAt = at)
        val archive = FinancialArchiveFixture.encode(Interpretation(id, FinancialArchiveFixture.VERSION, bank, facts,
            ParseStatus.NON_FINANCIAL, null, emptyList(), emptySet()))
        store.complete(id, route, EvidenceComparison(EvidenceDisposition.COMPARISON_ONLY, emptySet(), "{}", archive), at)
        assertTrue(db.dashboardAuxFactsDao().evidence(id)!!.statementProved)
    }
    private suspend fun payment(id: String, amount: String, at: String, currency: Currency = Currency.SAR, fx: AppliedFx? = null) {
        val input = FinancialClassificationInput(PostingKey(Environment.BETA, id), PostingRevision(0, 1), FinancialType.CREDIT_CARD_PAYMENT,
            Money.of(amount, currency), TransactionTime(Instant.parse(at), zone), FinancialEndpoint.Owned(account.id),
            FinancialEndpoint.Owned(card.id), listOf(account, facility, card).associateBy { it.id }, setOf("evidence-$id"), true, appliedFx = fx)
        assertTrue(RoomBetaFinancialLedger(db).post(input) is FinancialPostingResult.Applied)
    }

    @Test fun settlementOutsideBothWindowsIsPaid() = runBlocking {
        statement()
        payment("before", "999", "2026-09-19T12:00:00Z")
        payment("outside", "100", "2026-09-22T12:00:00Z")
        val batch = source().load(query)
        assertEquals(listOf("outside"), batch.rows.map { it.posting.command.key.transactionId })
        val result = BetaDashboardBuilder.build(batch)
        assertTrue(result.snapshot.transactions.isEmpty())
        val commitments = result.snapshot.commitments!!
        assertEquals(PaymentStatus.PAID, commitments.rows.single().status)
        assertEquals(Money.of("100", Currency.SAR), commitments.paid)
        assertEquals(Money.of("0", Currency.SAR), commitments.remaining)
    }

    @Test fun futureSettlementDoesNotPay() = runBlocking {
        statement()
        payment("partial", "40", "2026-09-22T12:00:00Z")
        payment("future", "60", "2026-10-18T12:00:00Z")
        val result = BetaDashboardBuilder.build(source().load(query))
        val commitments = result.snapshot.commitments!!
        assertEquals(PaymentStatus.UNPAID, commitments.rows.single().status)
        assertEquals(Money.of("100", Currency.SAR), commitments.remaining)
    }

    @Test fun settlementAfterCalendarEndIsPaid() = runBlocking {
        statement()
        val saved = controls().read()
        assertTrue(controls().save(saved.revision, saved.draft.copy(salary = BetaSalaryCycle(15))) is BetaDashboardConfigurationWrite.Saved)
        payment("after", "100", "2026-10-02T12:00:00Z")
        val september = query.copy(from = Instant.parse("2026-08-31T21:00:00Z"), until = Instant.parse("2026-09-30T21:00:00Z"))
        val batch = source().load(september)
        assertEquals(LocalDate.of(2026, 9, 15), batch.periods.salary!!.from.atZone(zone).toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 15), batch.periods.salary!!.until.atZone(zone).toLocalDate())
        assertTrue(batch.rows.single().posting.command.time.instant >= september.until)
        val snapshot = BetaDashboardBuilder.build(batch).snapshot
        assertTrue(snapshot.transactions.isEmpty())
        assertEquals(PaymentStatus.PAID, snapshot.commitments!!.rows.single().status)
        assertEquals(Money.of("0", Currency.SAR), snapshot.commitments!!.remaining)
    }

    @Test fun newerStatementInSalaryTailNeverBecomesKnownZero() = runBlocking<Unit> {
        statement(Instant.parse("2026-09-02T12:00:00Z"), LocalDate.of(2026, 9, 12))
        val saved = controls().read()
        assertTrue(controls().save(saved.revision, saved.draft.copy(salary = BetaSalaryCycle(15))) is BetaDashboardConfigurationWrite.Saved)
        captureStatement("tail", Instant.parse("2026-10-02T12:00:00Z"), LocalDate.of(2026, 10, 12))
        val september = query.copy(from = Instant.parse("2026-08-31T21:00:00Z"), until = Instant.parse("2026-09-30T21:00:00Z"))
        val batch = source().load(september)
        val snapshot = BetaDashboardBuilder.build(batch).snapshot
        assertEquals(LocalDate.of(2026, 9, 12), snapshot.creditFacilities.single().dueDate)
        assertEquals(Instant.parse("2026-09-02T12:00:00Z"), snapshot.creditFacilities.single().statementIssuedAt)
        assertTrue(snapshot.commitments!!.rows.isEmpty())
        assertNull(snapshot.commitments!!.total)
        assertEquals(setOf(facility.id), batch.unavailableObligationAssets)
        captureStatement("tied-tail", Instant.parse("2026-10-02T12:00:00Z"), LocalDate.of(2026, 10, 13))
        assertEquals(setOf(facility.id), source().load(september).unavailableObligationAssets)
        RoomBetaEvidenceStore(db).begin("tail", now)
        assertEquals(setOf(facility.id), source().load(september).unavailableObligationAssets)
    }

    @Test fun outsideFeeGapDoesNotPoisonSelectedPurchases() = runBlocking<Unit> {
        statement()
        db.bankSelectionDao().upsert(BetaBankSelectionEntity(bank.value, "ENABLED"))
        val at = Instant.parse("2026-10-10T12:00:00Z")
        for (id in listOf("purchase1", "purchase2")) {
            val store = RoomBetaEvidenceStore(db)
            val route = EvidenceRoute(RouteKind.MATCHED, setOf(bank), "synthetic")
            store.capture(CapturedEvidence(id, EvidenceRequest("fixture", EvidenceOrigin.EXPLICIT_SAMPLE),
                EvidenceMessage(id, "AlJazira", "synthetic purchase", at, ReceiptTimeSource.USER_SUPPLIED), at, route, emptyList()))
            store.begin(id, at)
            val facts = Facts(MessageFamily.PURCHASE, Money.of("23", Currency.USD), MoneyDirection.OUTGOING,
                card = CardReference(bank, "7271"), cardChannel = CardChannel.CREDIT, merchant = "Synthetic Store",
                occurredAt = at, exchangeRate = BigDecimal("3.756957"), internationalFee = Money.of("1.99", Currency.SAR))
            val archive = FinancialArchiveFixture.encode(Interpretation(id, FinancialArchiveFixture.VERSION, bank, facts,
                ParseStatus.SUCCESS, null, emptyList(), emptySet()))
            store.complete(id, route, EvidenceComparison(EvidenceDisposition.COMPARISON_ONLY, emptySet(), "{}", archive), at)
            val service = RoomBetaFinancialAdoption(db)
            val review = service.reviewPage().single { it.evidenceId == id }
            assertTrue(service.approve(BetaFinancialApproval(id, review.archiveRevision, null, FinancialType.EXPENSE, card.id, null)) is BetaFinancialAdoptionResult.Posted)
        }
        payment("outside", "10", "2026-09-22T12:00:00Z", Currency.USD, AppliedFx(BigDecimal("3.75"), "SMS"))
        val keys = setOf(RoomBetaFinancialAdoption.key("purchase1"), RoomBetaFinancialAdoption.key("purchase2"))
        val before = keys.associateWith { RoomBetaFinancialLedger(db).read(it) }
        val batch = source().load(query)
        assertFalse(batch.coverage.completeFeeFacts)
        assertEquals(keys, batch.feeProofKeys)
        val result = BetaDashboardBuilder.build(batch)
        assertEquals(BigDecimal("176.80"), result.financialSummary!!.metrics.getValue(SummaryMetric.CREDIT_PURCHASES)!!.amount)
        assertEquals(BigDecimal("176.80"), result.snapshot.analysis!!.merchants.single().net.amount)
        assertFalse(DashboardSection.MERCHANT_TOTALS in result.unavailableSections)
        assertNull(BetaDashboardBuilder.build(batch.copy(feeProofKeys = emptySet())).financialSummary!!.metrics.getValue(SummaryMetric.CREDIT_PURCHASES))
        assertNull(result.snapshot.commitments!!.total) // The outside SMS payment has no proved fee amount/absence.
        assertTrue(result.snapshot.commitments!!.rows.isEmpty())
        assertEquals(before, keys.associateWith { RoomBetaFinancialLedger(db).read(it) })
        assertEquals(AppliedFx(BigDecimal("3.756957"), "SMS"), batch.rows.first { it.posting.command.key in keys }.posting.command.appliedFx)
        assertThrows(IllegalArgumentException::class.java) {
            BetaDashboardBuilder.build(batch.copy(feeProofKeys = batch.feeProofKeys + PostingKey(Environment.BETA, "outside")))
        }
    }

    @Test fun unknownSettlementFxIsNotKnownZero() = runBlocking {
        statement(Instant.parse("2026-09-28T12:00:00Z"))
        payment("foreign", "100", "2026-10-28T12:00:00Z", Currency.USD)
        val batch = RoomBetaDashboardSource(db, clock = Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC)).load(query)
        assertTrue(batch.assetFacts.single().statement!!.issuedAt >= batch.periods.salary!!.from)
        val result = BetaDashboardBuilder.build(batch)
        assertTrue(result.snapshot.commitments!!.rows.isEmpty())
        assertNull(result.snapshot.commitments!!.total)
        assertTrue(DashboardSection.COMMITMENTS in result.unavailableSections)
    }

    @Test fun backupRestoreRetainsConfiguration() = runBlocking {
        db.close()
        try {
            BetaContainer(context).use { beta ->
                val facade = betaDashboardControls(beta)
                assertTrue(facade.save(0, draft(true)) is BetaDashboardConfigurationWrite.Saved)
                val saved = facade.read()
                beta.backup()
                assertTrue(facade.save(saved.revision, BetaDashboardConfigurationDraft()) is BetaDashboardConfigurationWrite.Saved)
                assertTrue(beta.restore())
                assertEquals(saved, facade.read()) // Existing facade follows the reopened database.
                beta.reset()
                assertEquals(0, facade.read().revision)
                assertFalse(beta.backupDatabaseFile().exists())
            }
        } finally { db = openDb() }
    }
}
