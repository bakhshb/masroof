package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.bank.*
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.application.corrections.*
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.verification.FinancialArchiveFixture
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.corrections.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.dashboard.producer.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class RoomBetaAuxFactsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: BetaDatabase
    private val bank = BankId("BANK_ALJAZIRA")
    private val account = OwnedAsset(AssetId(Environment.BETA, bank, "account"), AssetKind.ACCOUNT, "3001")
    private val facility = OwnedAsset(AssetId(Environment.BETA, bank, "facility"), AssetKind.CREDIT_FACILITY)
    private val card = OwnedAsset(AssetId(Environment.BETA, bank, "credit"), AssetKind.CREDIT_CARD, "7271", facility.id, CreditCardRole.PRIMARY)
    private val loan = OwnedAsset(AssetId(Environment.BETA, bank, "personal"), AssetKind.LOAN)
    private val at = Instant.parse("2026-08-06T17:22:00Z")
    private val zone = ZoneId.of("Asia/Riyadh")
    private val query = DashboardQuery(Environment.BETA, at.minusSeconds(3600), at.plusSeconds(7200), zone, Currency.SAR)
    private fun openDb() = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
    @Before fun open() = runBlocking {
        BetaContainer.resetOffline(context); db = openDb()
        db.bankSelectionDao().upsert(BetaBankSelectionEntity(bank.value, "ENABLED"))
        for (asset in listOf(account, facility, card, loan)) BetaAssetRegistry(db).register(asset)
    }
    @After fun close() { db.close(); BetaContainer.resetOffline(context) }
    private fun source() = RoomBetaDashboardSource(db, DashboardPeriods(salary = DisplayPeriod(query.from, query.until, zone, PeriodKind.SALARY, null)),
        clock = Clock.fixed(at.plusSeconds(10800), ZoneOffset.UTC))
    @Test fun reviewDefaultsRespectPersistedTransferSidesWithTwoOwnedAccounts()=runBlocking {
        val other=OwnedAsset(account.id.copy(localId="destination"),AssetKind.ACCOUNT,"5002")
        BetaAssetRegistry(db).register(other)
        val facts=Facts(MessageFamily.TRANSFER_IN,amount=Money.of("50",Currency.SAR),direction=MoneyDirection.INCOMING,
            sourceAccount=AccountReference(bank,"3001"),destinationAccount=AccountReference(bank,"5002"),occurredAt=at)
        val identityEvidence=listOf(Evidence("fixture.source","SOURCE_ACCOUNT",0,4),Evidence("fixture.destination","DESTINATION_ACCOUNT",5,9))
        val body="3001 5002 50 2026-08-06 20:22"
        capture("in-role",facts,ParseStatus.SUCCESS,body,identityEvidence)
        val incoming=BetaReviewResolutionWorkflow(db).detail("in-role")
        assertEquals(other.id,incoming.destination);assertNull(incoming.source)
        capture("out-role",facts.copy(family=MessageFamily.TRANSFER_OUT,direction=MoneyDirection.OUTGOING),ParseStatus.SUCCESS,body,identityEvidence)
        val outgoing=BetaReviewResolutionWorkflow(db).detail("out-role")
        assertEquals(account.id,outgoing.source);assertNull(outgoing.destination)
        capture("missing-role",facts.copy(destinationAccount=null),ParseStatus.SUCCESS,body,identityEvidence)
        assertNull(BetaReviewResolutionWorkflow(db).detail("missing-role").destination)
    }

    private fun notice() = Facts(MessageFamily.NON_FINANCIAL, card = CardReference(bank, "7271"), cardChannel = CardChannel.STATEMENT,
        outstandingBalance = Money.of("1250", Currency.SAR), paymentDueDate = LocalDate.of(2026, 8, 6), occurredAt = at)
    private suspend fun capture(id: String, facts: Facts = notice(), status: ParseStatus = ParseStatus.NON_FINANCIAL,
        body: String="synthetic",identityEvidence: List<Evidence> = emptyList(),change: (org.json.JSONObject) -> Unit = {}) {
        val store = RoomBetaEvidenceStore(db)
        val route = EvidenceRoute(RouteKind.MATCHED, setOf(bank), "fixture")
        store.capture(CapturedEvidence(id, EvidenceRequest("fixture", EvidenceOrigin.EXPLICIT_SAMPLE),
            EvidenceMessage(id, "AlJazira", body, at, ReceiptTimeSource.USER_SUPPLIED), at, route, emptyList()))
        store.begin(id, at)
        val archive = org.json.JSONObject(FinancialArchiveFixture.encode(Interpretation(id, FinancialArchiveFixture.VERSION, bank, facts,
            status, null,if(identityEvidence.isEmpty()) emptyList() else listOf(Evidence("fixture.family","family",0,1),Evidence("fixture.amount","AMOUNT",10,12),Evidence("fixture.time","occurredAtLocal",13,body.length))+identityEvidence,emptySet()))).apply(change).toString()
        store.complete(id, route, EvidenceComparison(EvidenceDisposition.COMPARISON_ONLY, emptySet(), "{}", archive), at)
    }
    private suspend fun actual(body: String, id: String): String {
        val result = ProcessBetaEvidence(RoomBetaEvidenceStore(db), RoomBetaBankSelectionStore(db), LabEvidenceRouter(), LabEvidenceComparer(com.baraa.masroof.lab.beta.application.interpretation.BetaInterpretationService.independent()))
            .importBatch(EvidenceRequest("actual", EvidenceOrigin.EXPLICIT_SAMPLE), listOf(EvidenceMessage(id, "AlJazira", body, at,
                ReceiptTimeSource.USER_SUPPLIED))).single() as EvidenceResult.Stored
        return result.summary.id
    }

    @Test fun currentNoticeAndOtp() = runBlocking {
        val statement = actual("بطاقة إئتمانية: إصدار كشف حساب\nبطاقة: 7271 بطاقة إئتمانية\nإجمالي المبلغ المستحق: SAR 1250.00\nتاريخ الاستحقاق: 2026-08-25", "statement")
        val otp = actual("رمز التحقق الخاص بك هو 482911. لا تشاركه مع أي شخص.", "otp")
        assertTrue(db.dashboardAuxFactsDao().evidence(statement)!!.nonFinancial)
        assertTrue(db.dashboardAuxFactsDao().evidence(otp)!!.nonFinancial)
        val archived = org.json.JSONObject(db.evidenceDao().outcome(statement)!!.candidateJson).getJSONObject("facts")
        assertTrue(archived.isNull("occurredAt")); assertTrue(archived.isNull("occurredAtLocal"))
        assertEquals("7271", archived.getJSONObject("card").getString("number"))
        assertEquals("STATEMENT", archived.getString("cardChannel"))
        assertEquals("1250.00", archived.getJSONObject("outstandingBalance").getString("amount"))
        assertTrue(db.dashboardAuxFactsDao().evidence(statement)!!.statementProved)
        val batch = source().load(query)
        assertTrue(batch.coverage.completeLedger); assertEquals(0, batch.coverage.reviewRequiredCount)
        assertTrue(batch.rows.isEmpty()); assertNotNull(BetaDashboardBuilder.build(batch).snapshot.analysis)
        assertEquals(Money.of("1250", Currency.SAR), BetaDashboardBuilder.build(batch).snapshot.creditFacilities.single().statementDue)
        assertEquals(at, BetaDashboardBuilder.build(batch).snapshot.creditFacilities.single().statementIssuedAt)
        for (id in listOf(statement, otp)) {
            assertNull(db.financialAdoptionDao().evidence(id))
            assertNull(RoomBetaFinancialLedger(db).readAudit(RoomBetaFinancialAdoption.key(id)))
        }
        db.close(); db = openDb(); assertEquals(batch, source().load(query))
    }

    @Test fun untrustedNeverCompletes() = runBlocking {
        capture("version", change = { it.put("engineVersion", "candidate") })
        capture("partial", status = ParseStatus.PARTIAL)
        capture("success", status = ParseStatus.SUCCESS)
        val store = RoomBetaEvidenceStore(db)
        for (id in listOf("version", "partial", "success")) {
            assertNull(db.dashboardAuxFactsDao().evidence(id))
            assertTrue(store.decide(id, 0, EvidenceReviewDecision.ACKNOWLEDGE_COMPARISON, at))
        }
        val batch = source().load(query)
        assertFalse(batch.coverage.completeLedger); assertNull(batch.coverage.reviewRequiredCount)
        assertTrue(batch.assetFacts.isEmpty()); assertNull(BetaDashboardBuilder.build(batch).snapshot.analysis)
    }

    @Test fun reparseBlocksFallback() = runBlocking {
        capture("older", notice().copy(occurredAt = at.minusSeconds(30), outstandingBalance = Money.of("900", Currency.SAR)))
        capture("newer")
        assertEquals(Money.of("1250", Currency.SAR), BetaDashboardBuilder.build(source().load(query)).snapshot.creditFacilities.single().statementDue)
        val store = RoomBetaEvidenceStore(db); store.begin("newer", at.plusSeconds(60))
        val pending = source().load(query)
        assertFalse(pending.coverage.completeLedger)
        assertNull(BetaDashboardBuilder.build(pending).snapshot.creditFacilities.single().statementDue)
        capture("newer", change = { it.put("engineVersion", "untrusted") })
        assertNull(BetaDashboardBuilder.build(source().load(query)).snapshot.creditFacilities.single().statementDue)
        assertNull(source().load(query).coverage.reviewRequiredCount)
        capture("newer", notice().copy(outstandingBalance = Money.of("1300", Currency.SAR)))
        assertEquals(Money.of("1300", Currency.SAR), BetaDashboardBuilder.build(source().load(query)).snapshot.creditFacilities.single().statementDue)
    }

    @Test fun registryRoles() = runBlocking {
        capture("one")
        val other = BankId("BANK_OTHER")
        val secondFacility = facility.copy(id = facility.id.copy(localId = "second"))
        BetaAssetRegistry(db).register(secondFacility)
        BetaAssetRegistry(db).register(card.copy(id = card.id.copy(localId = "second-card"), parentId = secondFacility.id))
        val invalidated = source().load(query)
        assertTrue(invalidated.coverage.completeLedger); assertEquals(0, invalidated.coverage.reviewRequiredCount)
        assertTrue(invalidated.assetFacts.isEmpty())
        capture("collision")
        assertTrue(source().load(query).assetFacts.isEmpty())
        capture("foreign", notice().copy(card = CardReference(other, "7271")))
        assertTrue(source().load(query).assetFacts.isEmpty())
        assertTrue(source().load(query).rows.isEmpty())
    }

    @Test fun approvedLoanEdit() = runBlocking {
        val id = actual("خصم: قسط تمويل\nمن: 3001\nالقسط: SAR 3,036.11\nالمبلغ المتبقي: SAR 33,397.25\nلـ: تمويل شخصي\nفي: 2026-08-06 20:22", "loan")
        assertNull(db.dashboardAuxFactsDao().evidence(id))
        assertFalse(source().load(query).coverage.completeLedger)
        val service = RoomBetaFinancialAdoption(db)
        val item = service.reviewPage().single { it.evidenceId == id }
        assertTrue(service.approve(BetaFinancialApproval(id, item.archiveRevision, null, FinancialType.LOAN_REPAYMENT, account.id, loan.id)) is BetaFinancialAdoptionResult.Posted)
        val result = BetaDashboardBuilder.build(source().load(query)).snapshot
        assertEquals(Money.of("33397.25", Currency.SAR), result.loans.single().remaining)
        assertEquals(Money.of("3036.11", Currency.SAR), result.loans.single().latestInstallment)
        assertEquals("PERSONAL", result.loans.single().type)
        assertEquals(PaymentStatus.PAID, result.commitments!!.rows.single().status)
        assertNull(result.commitments!!.total) // No authoritative user recurrence store yet.
        val key = RoomBetaFinancialAdoption.key(id)
        val detail = BetaReviewWorkflow(db).detail(key)!!
        assertTrue(BetaReviewWorkflow(db).save(detail.token, "manual", FinancialDecision.EditFacts(time = TransactionTime(at.plusSeconds(30), zone))) is BetaCorrectionResult.Applied)
        val edited = BetaDashboardBuilder.build(source().load(query)).snapshot.loans.single()
        assertNull(edited.remaining); assertNull(edited.latestInstallment)
        assertEquals(2, RoomBetaFinancialLedger(db).history(key).size)
    }

    @Test fun boundedBackfill() = runBlocking {
        capture("one")
        db.openHelper.writableDatabase.execSQL("UPDATE beta_parsed_event SET a_present=0")
        assertFalse(source().load(query).coverage.completeLedger)
        val refresh = RoomBetaDashboardFacts(db).refreshPage(limit = 1)
        assertEquals(1, refresh.refreshed)
        val before = source().load(query)
        assertTrue(before.coverage.completeLedger)
        assertEquals(1, RoomBetaDashboardFacts(db).refreshPage(limit = 1).refreshed)
        assertEquals(before, source().load(query))
        capture("two", notice().copy(occurredAt = at.plusSeconds(30)))
        try { RoomBetaDashboardSource(db, limits = BetaDashboardReadLimits(evidenceIds = 1)).load(query); fail("No truncation") }
        catch (expected: BetaDashboardReadLimitExceeded) { assertEquals("auxiliary facts", expected.collection) }
    }

    @Test fun cardParentProof() = runBlocking {
        val other = account.copy(id = account.id.copy(localId = "other"), last4 = "4002")
        BetaAssetRegistry(db).register(other)
        val matching = OwnedAsset(account.id.copy(localId = "mada"), AssetKind.DEBIT_CARD, "1111", account.id)
        val contradictory = matching.copy(id = matching.id.copy(localId = "other-mada"), last4 = "8888", parentId = other.id)
        BetaAssetRegistry(db).register(matching); BetaAssetRegistry(db).register(contradictory)
        val facts = Facts(MessageFamily.BALANCE_NOTICE, sourceAccount = AccountReference(bank, "3001"),
            card = CardReference(bank, "1111"), cardChannel = CardChannel.DEBIT, availableBalance = Money.of("100", Currency.SAR), occurredAt = at)
        capture("valid", facts)
        assertEquals(0, java.math.BigDecimal("100").compareTo(BetaDashboardBuilder.build(source().load(query)).snapshot.accounts.single { it.id == account.id }.balance!!.amount))
        capture("wrong-parent", facts.copy(card = CardReference(bank, "8888"), occurredAt = at.plusSeconds(30)))
        val batch = source().load(query)
        assertTrue(batch.coverage.completeLedger); assertEquals(0, batch.coverage.reviewRequiredCount)
        assertNull(BetaDashboardBuilder.build(batch).snapshot.accounts.single { it.id == account.id }.balance)
        assertNull(BetaDashboardBuilder.build(batch).snapshot.accounts.single { it.id == other.id }.balance)
        assertTrue(batch.rows.isEmpty())
    }

    @Test fun malformedDueDate() = runBlocking {
        capture("malformed", change = { it.getJSONObject("facts").put("paymentDueDate", "not-an-iso-date") })
        assertNull(db.dashboardAuxFactsDao().evidence("malformed"))
        val batch = source().load(query)
        assertFalse(batch.coverage.completeLedger); assertNull(batch.coverage.reviewRequiredCount)
        assertTrue(batch.assetFacts.isEmpty())
    }

    @Test fun atomicAuxCompletion() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER aux_fault BEFORE UPDATE ON beta_parsed_event WHEN NEW.a_present=1 BEGIN SELECT RAISE(ABORT, 'synthetic auxiliary fault'); END")
        try { capture("fault"); fail("Expected auxiliary write failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertNull(db.evidenceDao().outcome("fault")); assertNotNull(db.evidenceDao().retry("fault"))
        assertNull(db.dashboardAuxFactsDao().evidence("fault")); assertFalse(source().load(query).coverage.completeLedger)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER aux_fault")
        capture("fault")
        assertTrue(source().load(query).coverage.completeLedger)
        assertNull(RoomBetaFinancialLedger(db).readAudit(RoomBetaFinancialAdoption.key("fault")))
    }

    @Test fun ordinaryNoticeIsNotStatement() = runBlocking {
        capture("statement", notice().copy(occurredAt = at.minusSeconds(30)))
        capture("ordinary", notice().copy(cardChannel = CardChannel.CREDIT, outstandingBalance = Money.of("9000", Currency.SAR)))
        assertFalse(db.dashboardAuxFactsDao().evidence("ordinary")!!.statementProved)
        assertNull(db.dashboardAuxFactsDao().evidence("ordinary")!!.facilityAsset)
        assertEquals(Money.of("1250", Currency.SAR), BetaDashboardBuilder.build(source().load(query)).snapshot.creditFacilities.single().statementDue)
    }

    @Test fun purchaseNeverSuppliesOrBlocksDue() = runBlocking {
        val id = actual("شراء عبر الانترنت\nبطاقة ائتمانية: 7271\nلدى: Synthetic Store\nبمبلغ: SAR 23.00\nفي: 2026-08-06 20:22\nإجمالي المبلغ المستحق: SAR 9000.00", "purchase")
        val service = RoomBetaFinancialAdoption(db)
        val item = service.reviewPage().single { it.evidenceId == id }
        assertTrue(service.approve(BetaFinancialApproval(id, item.archiveRevision, null, FinancialType.EXPENSE, card.id, null)) is BetaFinancialAdoptionResult.Posted)
        assertNull(db.dashboardFactsDao().evidence(id)!!.dueAmount)
        assertNull(BetaDashboardBuilder.build(source().load(query)).snapshot.creditFacilities.single().statementDue)
        // Simulate an untouched schema8 projection: its family was nullable and it stored a
        // purchase outstanding amount. Neither the value nor the newer timestamp is a statement.
        val old = db.dashboardFactsDao().evidence(id)!!
        db.dashboardFactsDao().put(old.copy(facilityAsset = facility.id.localId, dueAmount = "9000.00", dueCurrency = "SAR", messageFamily = null))
        assertNull(BetaDashboardBuilder.build(source().load(query)).snapshot.creditFacilities.single().statementDue)
        capture("older-statement", notice().copy(occurredAt = at.minusSeconds(60)))
        assertEquals(Money.of("1250", Currency.SAR), BetaDashboardBuilder.build(source().load(query)).snapshot.creditFacilities.single().statementDue)
        val detail = BetaReviewWorkflow(db).detail(RoomBetaFinancialAdoption.key(id))!!
        assertTrue(BetaReviewWorkflow(db).save(detail.token, "suppress-purchase", FinancialDecision.SuppressAsNonFinancial) is BetaCorrectionResult.Applied)
        assertEquals(Money.of("1250", Currency.SAR), BetaDashboardBuilder.build(source().load(query)).snapshot.creditFacilities.single().statementDue)
        assertEquals(1, RoomBetaFinancialLedger(db).history(RoomBetaFinancialAdoption.key(id)).size)
    }
}
