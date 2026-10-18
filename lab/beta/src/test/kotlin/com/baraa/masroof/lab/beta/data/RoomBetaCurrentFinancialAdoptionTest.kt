package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.bank.*
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.application.interpretation.BetaInterpretationService
import com.baraa.masroof.lab.beta.application.interpretation.LegacyInterpretationAdapter
import com.baraa.masroof.lab.bank.interpretation.RuleInterpreter
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Current bank-engine archives can be explicitly posted. Historical baselines and model text cannot. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class RoomBetaCurrentFinancialAdoptionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: BetaDatabase
    private val bank = BankId("BANK_ALJAZIRA")
    private val account = OwnedAsset(AssetId(Environment.BETA, bank, "account"), AssetKind.ACCOUNT, "8901")
    private val debit = OwnedAsset(AssetId(Environment.BETA, bank, "mada"), AssetKind.DEBIT_CARD, "4567", account.id)
    private val facility = OwnedAsset(AssetId(Environment.BETA, bank, "facility"), AssetKind.CREDIT_FACILITY)
    private val credit = OwnedAsset(AssetId(Environment.BETA, bank, "credit"), AssetKind.CREDIT_CARD, "4567", facility.id, CreditCardRole.PRIMARY)
    private val loan = OwnedAsset(AssetId(Environment.BETA, bank, "loan"), AssetKind.LOAN)
    private val instant = Instant.parse("2026-10-17T10:30:00Z")
    private val mada = """
        شراء عبر نقاط البيع
        بطاقة مدى: 4567
        لدى: متجر مستقل
        بمبلغ: SAR 32.50
        خصمت من حساب: 8901
        في: 14:32 03-08-2026
    """.trimIndent()

    @Before fun open() = runBlocking {
        BetaContainer.resetOffline(context)
        db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
        db.bankSelectionDao().upsert(BetaBankSelectionEntity(bank.value, "ENABLED"))
        listOf(account, debit, facility, credit, loan).forEach { BetaAssetRegistry(db).register(it) }
    }
    @After fun close() { db.close(); BetaContainer.resetOffline(context) }
    private fun reopen() { db.close(); db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build() }

    @Test fun explicitApprovalPostsCurrentMadaPurchaseAndLinksTheDebitCard() = runBlocking {
        val interpreted = import("mada", mada)
        assertEquals(interpreted.diagnostics.toString(), ParseStatus.SUCCESS, interpreted.status)
        assertEquals(MessageFamily.PURCHASE, interpreted.facts.family)
        assertEquals(CardChannel.DEBIT, interpreted.facts.cardChannel)
        assertEquals("4567", interpreted.facts.card!!.last4)
        assertEquals("8901", interpreted.facts.debitSourceAccountLast4)
        assertTrue(interpreted.evidence.isNotEmpty())
        assertNull(posting("mada"))
        assertNull(RoomBetaFinancialAdoption(db).preview("mada")?.blockedReason)
        approve("mada", FinancialType.EXPENSE, account.id, null)
        val posted = requireNotNull(posting("mada"))
        assertEquals(Money.of("32.50", Currency.SAR), posted.command.amount)
        assertEquals(Instant.parse("2026-08-03T11:32:00Z"), posted.command.time.instant)
        assertEquals(CorrectionAuthority.USER_FINANCIAL_TYPE, posted.classification.input.authority)
        val facts = requireNotNull(db.dashboardFactsDao().evidence("mada"))
        assertEquals("mada", facts.debitCardAsset)
        assertEquals("4567", facts.debitCardLast4)
        assertEquals(account.id.localId, facts.debitCardParent)
        assertEquals("PURCHASE", facts.messageFamily)
        assertEquals("متجر مستقل", facts.merchant)
        val again = RoomBetaFinancialAdoption(db).approve(approval("mada", FinancialType.EXPENSE, account.id, null))
        assertTrue((again as BetaFinancialAdoptionResult.Posted).result is FinancialPostingResult.AlreadyApplied)
        assertEquals(1, RoomBetaFinancialLedger(db).history(posted.command.key).size)
    }

    @Test fun currentFamiliesPostOnlyAfterExplicitEndpoints() = runBlocking {
        postFamily("credit", "Card payment\nAmount: SAR 100\nCard: 5678\nFrom account: 1234\n2026-10-17 13:00",
            MessageFamily.CARD_PAYMENT, FinancialType.CREDIT_CARD_PAYMENT, account.id, credit.id, null)
        postFamily("refund", "استرداد مبلغ عملية شراء عبر نقاط البيع\nبطاقة: 4567\nبمبلغ: SAR 32.50\nفي: 2026-08-03 14:32",
            MessageFamily.REFUND, FinancialType.REFUND, null, account.id, null)
        postFamily("bill", "Bill payment\nAmount: SAR 50\nFrom account: 1234\nBiller: Synthetic Utilities\nBiller code: 001\n2026-10-17 13:00",
            MessageFamily.BILL_PAYMENT, FinancialType.BILL_PAYMENT, account.id, null, null)
        postFamily("loan", "Loan repayment\nAmount: SAR 500\nFrom account: 1234\nCounterparty: Synthetic Finance\nLoan type: personal\n2026-10-17 13:00",
            MessageFamily.FINANCING_INSTALLMENT, FinancialType.LOAN_REPAYMENT, account.id, loan.id, null)
        postFamily("transfer", "Outgoing transfer\nAmount: SAR 100\nFrom account: 1234\nTo account: 5678\nNetwork: inter-bank\n2026-10-18 13:00",
            MessageFamily.TRANSFER_OUT, FinancialType.EXTERNAL_TRANSFER_OUT, account.id, null, null)
        val loanFacts = requireNotNull(db.dashboardAuxFactsDao().evidence("loan"))
        assertEquals(loan.id.localId, loanFacts.loanAsset)
        assertEquals("PERSONAL", loanFacts.loanType)
        assertEquals(posting("loan")!!.command.amount.amount.toPlainString(), loanFacts.loanInstallment)
    }

    @Test fun smsExchangeRatePostsAsOneImmutablePair() = runBlocking {
        val interpreted = import("fx", "Purchase\nAmount: USD 23.00\nCard: 4567\nMerchant: Synthetic Shop\nExchange rate: 3.756957\nTime: 2026-10-17 13:30")
        assertEquals(interpreted.diagnostics.toString(), ParseStatus.SUCCESS, interpreted.status)
        assertEquals(BigDecimal("3.756957"), interpreted.facts.exchangeRate)
        assertNull(posting("fx"))
        approve("fx", FinancialType.EXPENSE, account.id, null)
        assertEquals(AppliedFx(requireNotNull(interpreted.facts.exchangeRate), "SMS"), posting("fx")!!.command.appliedFx)
        val enriched = posting("fx")!!
        val again = RoomBetaFinancialAdoption(db).enrich("fx", enriched.command.revision,
            com.baraa.masroof.lab.finance.fx.FxMarketRateProvider { _, _ -> error("complete pair") })
        assertTrue((again as BetaFinancialAdoptionResult.Posted).result is FinancialPostingResult.AlreadyApplied)
        assertEquals(enriched, posting("fx"))
    }

    @Test fun humanAmountLabelReplacesEngineAmountAndInvalidatesThePreviousDraft() = runBlocking {
        import("mada", mada)
        val before = approval("mada", FinancialType.EXPENSE, account.id, null)
        val review = requireNotNull(BetaInterpretationReviewWorkflow(db).detail("mada"))
        val span = requireNotNull(review.spans.firstOrNull { it.field == "AMOUNT" })
        BetaInterpretationReviewWorkflow(db).save(BetaLabelDraft(review.token, "correct-amount", BetaLabelField.AMOUNT,
            "40.00", span.start, span.end, true))
        assertEquals("STALE_ARCHIVE", (RoomBetaFinancialAdoption(db).approve(before) as BetaFinancialAdoptionResult.Review).reason)
        assertNull(posting("mada"))
        assertEquals(Money.of("40.00", Currency.SAR), RoomBetaFinancialAdoption(db).preview("mada")?.amount)
        approve("mada", FinancialType.EXPENSE, account.id, null)
        assertEquals(Money.of("40.00", Currency.SAR), posting("mada")!!.command.amount)
    }

    @Test fun staleOrNonHumanLabelsCannotChangeOrAuthorizeMoney() = runBlocking {
        val interpreted = import("mada", mada)
        val hash = MessageDigest.getInstance("SHA-256").digest(db.evidenceDao().outcome("mada")!!.candidateJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val span = interpreted.evidence.first { it.field == "AMOUNT" }
        db.learningDao().append(BetaLearningLabelEntity("mada", 1, "model-guess", hash, 1,
            interpreted.version.engine, interpreted.version.rules, BetaLabelField.AMOUNT.name, "99.00",
            span.start, span.end, mada.substring(span.start, span.end), "MODEL_SUGGESTED", instant.toString()))
        db.learningDao().append(BetaLearningLabelEntity("mada", 2, "old-human", "0".repeat(64), 1,
            interpreted.version.engine, interpreted.version.rules, BetaLabelField.AMOUNT.name, "77.00",
            span.start, span.end, mada.substring(span.start, span.end), "USER_EXPLICIT_ORIGINAL_SPAN_V1", instant.toString()))
        approve("mada", FinancialType.EXPENSE, account.id, null)
        assertEquals(Money.of("32.50", Currency.SAR), posting("mada")!!.command.amount)
    }

    @Test fun brokenHumanSpanAndUnsafeCurrentArchivesStayUnposted() = runBlocking {
        val interpreted = import("mada", mada)
        val outcome = requireNotNull(db.evidenceDao().outcome("mada"))
        val hash = MessageDigest.getInstance("SHA-256").digest(outcome.candidateJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        db.learningDao().append(BetaLearningLabelEntity("mada", 1, "bad-span", hash, outcome.attempts,
            interpreted.version.engine, interpreted.version.rules, BetaLabelField.CARD.name, "4567",
            0, 2, "no", "USER_EXPLICIT_ORIGINAL_SPAN_V1", instant.toString()))
        assertEquals("UNSAFE_BASELINE", RoomBetaFinancialAdoption(db).preview("mada")?.blockedReason)
        assertEquals(BetaFinancialAdoptionResult.Review("UNSAFE_BASELINE"), RoomBetaFinancialAdoption(db).approve(approval("mada", FinancialType.EXPENSE, account.id, null)))
        assertNull(posting("mada"))
        assertEquals(1, db.learningDao().history("mada").size)
        val current = JSONObject(outcome.candidateJson)
        listOf<(JSONObject) -> Unit>(
            { it.put("authority", "MODEL_SUGGESTED") },
            { it.put("evidence", JSONArray()) },
            { it.getJSONObject("facts").put("executionState", "UNEXECUTED") },
            { it.getJSONObject("facts").put("family", "OTP"); it.put("status", "SUCCESS") },
            { it.getJSONArray("diagnostics").put(JSONObject().put("code", "OTP_HINT").put("message", "fixture").put("severity", "WARNING")) },
        ).forEachIndexed { index, mutate ->
            val id = "unsafe$index"
            store(id, "{}", JSONObject(current.toString()).put("evidenceId", id).apply(mutate).toString(), "SAR 10")
            assertEquals(id, "UNSAFE_BASELINE", RoomBetaFinancialAdoption(db).preview(id)?.blockedReason)
            assertEquals(BetaFinancialAdoptionResult.Review("UNSAFE_BASELINE"),
                RoomBetaFinancialAdoption(db).approve(approval(id, FinancialType.EXPENSE, account.id, null)))
            assertNull(posting(id))
        }
    }

    @Test fun otpAndUnexecutedEngineResultsCannotBeApproved() = runBlocking {
        val otp = import("otp", "OTP 123456\n$mada")
        assertEquals(ParseStatus.NON_FINANCIAL, otp.status)
        assertEquals(MessageFamily.OTP, otp.facts.family)
        assertEquals("UNSAFE_BASELINE", RoomBetaFinancialAdoption(db).preview("otp")?.blockedReason)
        assertNull(posting("otp"))
        val declined = import("declined", "لم تتم\n$mada")
        assertNotEquals(declined.diagnostics.toString(), ParseStatus.SUCCESS, declined.status)
        assertEquals("UNSAFE_BASELINE", RoomBetaFinancialAdoption(db).preview("declined")?.blockedReason)
        assertEquals(BetaFinancialAdoptionResult.Review("UNSAFE_BASELINE"),
            RoomBetaFinancialAdoption(db).approve(approval("otp", FinancialType.EXPENSE, account.id, null)))
        assertEquals(BetaFinancialAdoptionResult.Review("UNSAFE_BASELINE"),
            RoomBetaFinancialAdoption(db).approve(approval("declined", FinancialType.EXPENSE, account.id, null)))
        assertNull(posting("otp")); assertNull(posting("declined"))
    }

    @Test fun historicalBaselineStaysExplicitAndIsNotReplacedByACurrentCandidate() = runBlocking {
        val legacy = InterpretationArchive.encode(Interpretation("legacy", LegacyInterpretationAdapter.VERSION, bank,
            Facts(MessageFamily.PURCHASE, Money.of("23", Currency.USD), MoneyDirection.OUTGOING,
                occurredAtLocal = LocalDateTime.parse("2026-10-17T13:30:00")),
            ParseStatus.SUCCESS, null, emptyList(), emptySet()))
        val current = currentArchive("legacy", "99.00")
        store("legacy", legacy, current, "legacy 23")
        assertNull(posting("legacy"))
        approve("legacy", FinancialType.EXPENSE, account.id, null)
        assertEquals(Money.of("23", Currency.USD), posting("legacy")!!.command.amount)
    }

    @Test fun committedCurrentApprovalSurvivesProcessDeathWithoutASecondPost() = runBlocking {
        import("mada", mada)
        val request = approval("mada", FinancialType.EXPENSE, account.id, null)
        RoomBetaFinancialAdoption(db).approve(request)
        val adopted = requireNotNull(db.financialAdoptionDao().evidence("mada"))
        db.financialAdoptionDao().retry(BetaFinancialApprovalRetryEntity("mada", adopted.approvalRequest))
        reopen()
        assertTrue((RoomBetaFinancialAdoption(db).retryApproval("mada") as BetaFinancialAdoptionResult.Posted).result is FinancialPostingResult.AlreadyApplied)
        assertNull(db.financialAdoptionDao().retry("mada"))
        assertEquals(1, RoomBetaFinancialLedger(db).history(RoomBetaFinancialAdoption.key("mada")).size)
        assertEquals(Money.of("32.50", Currency.SAR), posting("mada")!!.command.amount)
    }

    private suspend fun postFamily(id: String, body: String, family: MessageFamily, type: FinancialType,
        source: AssetId?, destination: AssetId?, income: com.baraa.masroof.lab.finance.classification.IncomeAttribution?) {
        val interpreted = import(id, body)
        assertEquals(interpreted.diagnostics.toString(), ParseStatus.SUCCESS, interpreted.status)
        assertEquals(family, interpreted.facts.family)
        assertNull(posting(id))
        approve(id, type, source, destination, income)
        assertNotNull(posting(id))
    }

    private suspend fun import(id: String, body: String): Interpretation {
        val sender = "AlJazira"
        val message = EvidenceMessage(id, sender, body, instant, ReceiptTimeSource.USER_SUPPLIED)
        val route = LabEvidenceRouter().route(message)
        val comparison = LabEvidenceComparer().compare(MessageInput(id, sender, body, instant), route)
        val store = RoomBetaEvidenceStore(db)
        store.capture(CapturedEvidence(id, EvidenceRequest("fixture", EvidenceOrigin.EXPLICIT_SAMPLE), message, instant, route, emptyList()))
        store.begin(id, instant)
        store.complete(id, route, comparison, instant)
        val saved = requireNotNull(db.evidenceDao().outcome(id))
        assertEquals("{}", saved.baselineJson)
        assertEquals("BANK_ENGINE", JSONObject(saved.candidateJson).getString("authority"))
        return comparison.let { JSONObject(it.candidateJson).let { _ ->
            RuleInterpreter(BetaInterpretationService.currentSchema()).interpret(MessageInput(id, sender, body, instant),
                com.baraa.masroof.lab.beta.application.evidence.BetaEngineRouting.router.catalog(sender))
        } }
    }

    private fun currentArchive(id: String, amount: String) = InterpretationArchive.encode(Interpretation(id,
        RuleInterpreter(BetaInterpretationService.currentSchema()).version, bank,
        Facts(MessageFamily.PURCHASE, Money.of(amount, Currency.SAR), MoneyDirection.OUTGOING,
            occurredAtLocal = LocalDateTime.parse("2026-10-17T13:30:00"), executionState = "EXECUTED"),
        ParseStatus.SUCCESS, null, listOf(Evidence("amount.principal", "AMOUNT", 0, 3)), emptySet()))

    private suspend fun store(id: String, baseline: String, candidate: String, body: String) {
        val route = EvidenceRoute(RouteKind.MATCHED, setOf(bank), "test-router")
        val message = EvidenceMessage(id, "AlJazira", body, instant, ReceiptTimeSource.USER_SUPPLIED)
        val evidence = RoomBetaEvidenceStore(db)
        evidence.capture(CapturedEvidence(id, EvidenceRequest("fixture", EvidenceOrigin.EXPLICIT_SAMPLE), message, instant, route, emptyList()))
        evidence.begin(id, instant)
        evidence.complete(id, route, EvidenceComparison(EvidenceDisposition.INTERPRETED, emptySet(), baseline, candidate), instant)
    }

    private suspend fun approval(id: String, type: FinancialType, source: AssetId?, destination: AssetId?,
        income: com.baraa.masroof.lab.finance.classification.IncomeAttribution? = null): BetaFinancialApproval {
        val item = RoomBetaFinancialAdoption(db).reviewPage(limit = 100).single { it.evidenceId == id }
        return BetaFinancialApproval(id, item.archiveRevision, null, type, source, destination, income)
    }

    private suspend fun approve(id: String, type: FinancialType, source: AssetId?, destination: AssetId?,
        income: com.baraa.masroof.lab.finance.classification.IncomeAttribution? = null) {
        val result = RoomBetaFinancialAdoption(db).approve(approval(id, type, source, destination, income))
        assertTrue(result.toString(), result is BetaFinancialAdoptionResult.Posted &&
            (result as BetaFinancialAdoptionResult.Posted).result is FinancialPostingResult.Applied)
    }

    private suspend fun posting(id: String) = RoomBetaFinancialLedger(db).read(RoomBetaFinancialAdoption.key(id))
}
