package com.baraa.masroof.lab.beta.presentation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.BetaIo
import com.baraa.masroof.lab.beta.application.BetaDashboardWorkflow
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.data.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.dashboard.producer.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.math.BigDecimal

@OptIn(ExperimentalTestApi::class)
class BetaDashboardScreenTest {
    // Real Room IO must return to the Android UI dispatcher before applying a test frame.
    @get:Rule val compose = createComposeRule(effectContext = Dispatchers.Main.immediate)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val at = Instant.parse("2026-10-17T10:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val zone = ZoneId.of("Asia/Riyadh")
    private val bank = BankId("BANK_ALJAZIRA")
    private val account = OwnedAsset(AssetId(Environment.BETA, bank, "synthetic-account"), AssetKind.ACCOUNT, "1234")
    private lateinit var db: BetaDatabase
    private val statements = CopyOnWriteArrayList<String>()

    @Before fun seed() = runBlocking {
        BetaContainer.resetOffline(context)
        db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME)
            .setQueryCallback({ sql, _ -> statements += sql }, Executor { it.run() }).build()
        BetaAssetRegistry(db).register(account)
        val input = FinancialClassificationInput(PostingKey(Environment.BETA, "synthetic-ui"), PostingRevision(0, 0),
            FinancialType.EXPENSE, Money.of("42.35", Currency.SAR), TransactionTime(at, zone),
            FinancialEndpoint.Owned(account.id), FinancialEndpoint.External, mapOf(account.id to account), setOf("synthetic-evidence"), true)
        assertTrue(RoomBetaFinancialLedger(db).post(input) is FinancialPostingResult.Applied)
        statements.clear()
    }
    @After fun cleanup() { db.close(); BetaContainer.resetOffline(context) }
    private fun workflow(fail: AtomicBoolean? = null) = BetaDashboardWorkflow({ query, periods ->
        withContext(Dispatchers.IO) {
            if (fail?.get() == true) error("synthetic read error")
            BetaDashboardProducer(RoomBetaDashboardSource(db, periods, clock = clock)).readWithCoverage(query)
        }
    }, clock)
    private fun waitLoaded(expectedMonth: String? = null) {
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("dash-refresh").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("dash-loading").fetchSemanticsNodes().isEmpty() &&
                (expectedMonth == null || compose.onAllNodesWithTag("dash-month").fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.any { it.text == expectedMonth } == true)
        }
        compose.waitForIdle()
    }
    private fun route(value: String) { compose.onNodeWithTag("dash-routes").performScrollTo(); compose.onNodeWithTag("dash-route-$value").performScrollTo().performClick() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        shell("mkdir -p /sdcard/beta-dashboard-screenshots")
        shell("screencap -p /sdcard/beta-dashboard-screenshots/$name.png")
        // UiAutomation executes a process directly; shell operators/quoting are not interpreted.
        val png = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat /sdcard/beta-dashboard-screenshots/$name.png")).use { it.readBytes() }
        assertTrue("A captured PNG must contain image data", png.size > 8)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), png.copyOfRange(0, 8))
    }

    @Test fun realRoomReadShowsUnavailableBalanceAndNeverWritesWhenNavigating() {
        val workflow = workflow()
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { BetaDashboardPanel(workflow) } } }
        waitLoaded()
        compose.onNode(hasText("42.35 SAR") and hasAnyAncestor(hasTestTag("dash-summary-spending"))).assertExists()
        route("ACCOUNTS")
        compose.onNodeWithTag("dash-asset-BANK_ALJAZIRA-synthetic-account").performScrollTo().performClick()
        compose.onNodeWithTag("dash-detail-back").assertExists()
        compose.onAllNodesWithText(context.getString(com.baraa.masroof.lab.beta.R.string.beta_dash_unavailable)).fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
        compose.onNodeWithTag("dash-detail-back").performScrollTo().performClick()
        route("CARDS"); compose.onNodeWithTag("dash-empty").assertExists()
        route("LOANS"); compose.onNodeWithTag("dash-empty").assertExists()
        route("COMMITMENTS"); compose.onNodeWithTag("dash-unavailable").assertExists()
        assertTrue(statements.none { sql -> sql.contains("beta_") && listOf("INSERT", "UPDATE", "DELETE").any { sql.trimStart().startsWith(it, true) } })
        runBlocking { assertEquals(1, RoomBetaFinancialLedger(db).history(PostingKey(Environment.BETA, "synthetic-ui")).size) }
    }

    @Test fun rtlSavedMonthRouteAndQualifiedSelectionSurviveStateRestore() {
        val state = StateRestorationTester(compose)
        val workflow = workflow()
        val arabic = Configuration(context.resources.configuration).apply { setLocale(Locale("ar")); setLayoutDirection(Locale("ar")) }
        val arabicContext = context.createConfigurationContext(arabic)
        state.setContent { MaterialTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalContext provides arabicContext, LocalConfiguration provides arabic) {
            Column(Modifier.verticalScroll(rememberScrollState())) { BetaDashboardPanel(workflow) }
        } } }
        waitLoaded(); screenshot("rtl-overview")
        route("ACCOUNTS")
        compose.onNodeWithTag("dash-asset-BANK_ALJAZIRA-synthetic-account").performScrollTo().performClick()
        compose.onNodeWithTag("dash-detail-back").performScrollTo(); screenshot("rtl-account-unknown")
        state.emulateSavedInstanceStateRestore(); waitLoaded("2026-10")
        compose.onNodeWithTag("dash-detail-back").assertExists()
        compose.onNodeWithTag("dash-month").assertTextEquals("2026-10")
        compose.onNodeWithTag("dash-detail-back").performScrollTo().performClick()
        compose.onNodeWithTag("dash-route-ACCOUNTS").assertIsSelected()
        compose.onNodeWithTag("dash-month-previous").performScrollTo().performClick(); waitLoaded("2026-09")
        state.emulateSavedInstanceStateRestore(); waitLoaded("2026-09")
        compose.onNodeWithTag("dash-month").assertTextEquals("2026-09")
        compose.onNodeWithTag("dash-route-ACCOUNTS").assertIsSelected()
    }

    @Test fun failedNewPeriodClearsOldMoneyAndExplicitRetryRecovers() {
        val fail = AtomicBoolean(false)
        val workflow = workflow(fail)
        val arabic = Configuration(context.resources.configuration).apply { setLocale(Locale("ar")); setLayoutDirection(Locale("ar")) }
        val arabicContext = context.createConfigurationContext(arabic)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalContext provides arabicContext, LocalConfiguration provides arabic) {
            Column(Modifier.verticalScroll(rememberScrollState())) { BetaDashboardPanel(workflow) }
        } } }
        waitLoaded(); compose.onNode(hasText("42.35 SAR") and hasAnyAncestor(hasTestTag("dash-summary-spending"))).assertExists()
        fail.set(true)
        compose.onNodeWithTag("dash-month-previous").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("dash-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dash-error").performScrollTo(); screenshot("period-error")
        compose.onAllNodesWithText("42.35 SAR").assertCountEquals(0)
        fail.set(false); compose.onNodeWithTag("dash-retry").performScrollTo().performClick(); waitLoaded()
        compose.onNodeWithTag("dash-month").assertTextEquals("2026-09")
        compose.onAllNodesWithText("42.35 SAR").assertCountEquals(0)
        compose.onNodeWithTag("dash-month-next").performScrollTo().performClick(); waitLoaded()
        compose.onNode(hasText("42.35 SAR") and hasAnyAncestor(hasTestTag("dash-summary-spending"))).assertExists()
    }

    @Test fun removedQualifiedSelectionDoesNotOpenSameLocalIdAtAnotherBank() = runBlocking {
        val other = account.copy(id = account.id.copy(bank = BankId("SYNTHETIC_OTHER_BANK")))
        BetaAssetRegistry(db).register(other)
        val workflow = workflow()
        val state = StateRestorationTester(compose)
        state.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { BetaDashboardPanel(workflow) } } }
        waitLoaded(); route("ACCOUNTS")
        compose.onNodeWithTag("dash-asset-SYNTHETIC_OTHER_BANK-synthetic-account").performScrollTo().performClick()
        db.openHelper.writableDatabase.execSQL("DELETE FROM beta_asset WHERE bankId='SYNTHETIC_OTHER_BANK'")
        state.emulateSavedInstanceStateRestore(); waitLoaded()
        compose.onNodeWithTag("dash-missing").assertExists()
        compose.onNodeWithTag("dash-detail-back").performScrollTo().performClick()
        compose.onNodeWithTag("dash-asset-BANK_ALJAZIRA-synthetic-account").assertExists()
        Unit
    }

    @Test fun approvedParserFxAndFeeReachActualDashboardWithoutReposting() = runBlocking<Unit> {
        db.close(); BetaContainer.resetOffline(context)
        db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
        val facility = OwnedAsset(AssetId(Environment.BETA, bank, "synthetic-facility"), AssetKind.CREDIT_FACILITY)
        val card = OwnedAsset(AssetId(Environment.BETA, bank, "synthetic-card"), AssetKind.CREDIT_CARD, "7271", facility.id, CreditCardRole.PRIMARY)
        BetaAssetRegistry(db).register(facility); BetaAssetRegistry(db).register(card)
        db.bankSelectionDao().upsert(BetaBankSelectionEntity(bank.value, "ENABLED"))
        val body = """شراء عبر الانترنت
بطاقة ائتمانية: 7271
لدى: Synthetic Store
بمبلغ: USD 23.00
في: 2026-10-17 13:00
الدولة: USA
رسوم العمليات الدولية: 1.99
سعر الصرف: 3.756957
الرصيد المتاح: SAR 16958.89
"""
        val stored = ProcessBetaEvidence(RoomBetaEvidenceStore(db), RoomBetaBankSelectionStore(db), LabEvidenceRouter(), LabEvidenceComparer())
            .importBatch(EvidenceRequest("synthetic-ui-fx", EvidenceOrigin.EXPLICIT_SAMPLE), listOf(
                EvidenceMessage("synthetic-provider", "AlJazira", body, at, ReceiptTimeSource.USER_SUPPLIED))).single() as EvidenceResult.Stored
        val key = RoomBetaFinancialAdoption.key(stored.summary.id)
        db.close()
        BetaContainer(context).use { beta ->
            val preview = checkNotNull(beta.financialApprovalPreview(stored.summary.id))
            val request = checkNotNull(betaApprovalDraft(preview, FinancialType.EXPENSE.name, "asset:" + card.id.localId, "external", null))
            assertNull(beta.financialPosting(key))
            assertTrue(beta.approveEvidenceFinancial(request) is BetaFinancialAdoptionResult.Posted)
            val original = checkNotNull(beta.financialPosting(key))
            val history = beta.financialHistory(key)
            assertEquals(AppliedFx(BigDecimal("3.756957"), "SMS"), original.command.appliedFx)
            val workflow = BetaDashboardWorkflow({ query, periods -> withContext(BetaIo.dispatcher) { beta.dashboard(query, periods) } }, clock)
            val query = workflow.query(workflow.currentMonth())
            val facts = beta.dashboard(query).snapshot.transactions.single()
            assertEquals(BigDecimal("86.41"), facts.sarEquivalent!!.amount) // Original fee-free equivalent, not spending.
            assertEquals(Money.of("1.99", Currency.SAR), facts.internationalFee)
            assertNull(beta.dashboard(query).snapshot.creditFacilities.single().statementDue)
            val arabic = Configuration(context.resources.configuration).apply { setLocale(Locale("ar")); setLayoutDirection(Locale("ar")) }
            val arabicContext = context.createConfigurationContext(arabic)
            compose.setContent { MaterialTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalContext provides arabicContext, LocalConfiguration provides arabic) {
                Column(Modifier.verticalScroll(rememberScrollState())) { BetaDashboardPanel(workflow) }
            } } }
            waitLoaded()
            assertTrue(compose.onAllNodesWithText("88.40 SAR").fetchSemanticsNodes().isNotEmpty())
            compose.onAllNodesWithText("86.41 SAR").assertCountEquals(0)
            screenshot("rtl-approved-overview")
            route("CARDS")
            compose.onAllNodesWithText("2694.32 SAR").assertCountEquals(0)
            compose.onNodeWithTag("dash-asset-BANK_ALJAZIRA-synthetic-facility").performScrollTo().performClick()
            compose.onAllNodesWithText("2694.32 SAR").assertCountEquals(0) // A purchase does not establish a statement due.
            compose.onNodeWithTag("dash-detail-back").performScrollTo(); screenshot("rtl-approved-facility")
            compose.onNodeWithTag("dash-asset-BANK_ALJAZIRA-synthetic-card").performScrollTo().performClick()
            compose.onNodeWithText("16958.89 SAR").assertExists()
            compose.onAllNodesWithText("2694.32 SAR").assertCountEquals(0)
            compose.onNodeWithTag("dash-detail-back").performScrollTo(); screenshot("rtl-approved-card")
            compose.onNodeWithTag("dash-month-previous").performScrollTo().performClick(); waitLoaded()
            compose.onNodeWithTag("dash-month-next").performScrollTo().performClick(); waitLoaded()
            assertEquals(original, beta.financialPosting(key)); assertEquals(history, beta.financialHistory(key))
            assertEquals(BigDecimal("86.41"), beta.dashboard(query).snapshot.transactions.single().sarEquivalent!!.amount)
        }
    }

    @Test fun authoritativeFacilityDueIsDisplayedOnceAndNeverCopiedToMemberCards() {
        val facilityId = AssetId(Environment.BETA, bank, "statement-facility")
        fun card(id: String, last4: String, role: CreditCardRole) = CreditCardSummary(
            AssetId(Environment.BETA, bank, id), role, null, last4, null, null, null, null, null)
        val facility = FacilitySummary(facilityId, Money.of("2694.32", Currency.SAR), LocalDate.of(2026, 10, 25), at,
            cards = listOf(card("statement-primary", "7271", CreditCardRole.PRIMARY), card("statement-supplementary", "7288", CreditCardRole.SUPPLEMENTARY)))
        val loanId = AssetId(Environment.BETA, bank, "TECH_LOAN_ID")
        val loan = LoanSummary(loanId, "fixture", "", Money.of("1000", Currency.SAR), at, Money.of("125", Currency.SAR), at, null, null)
        val commitments = CommitmentsSummary(Currency.SAR, listOf(
            CommitmentRow(Environment.BETA, "fixture-facility", "TECH_FACILITY_ID", Money.of("2694.32", Currency.SAR), null, PaymentStatus.UNPAID, facilityId = facilityId),
            CommitmentRow(Environment.BETA, "fixture-loan", "TECH_LOAN_ID", Money.of("125", Currency.SAR), null, PaymentStatus.UNPAID, loanId = loanId),
            CommitmentRow(Environment.BETA, "fixture-user", "التزامي الشخصي", Money.of("50", Currency.SAR), null, PaymentStatus.UNPAID, userCommitmentId = "user-fixture")), null, null, null)
        // An authoritative read-contract fixture tests rendering; actual statement authority is the producer's responsibility.
        val workflow = BetaDashboardWorkflow({ query, _ -> DashboardProduction(
            DashboardSnapshot(query, emptyList(), emptyList(), listOf(facility), loans = listOf(loan), commitments = commitments), emptySet(), null)
        }, clock)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { BetaDashboardPanel(workflow) } } }
        waitLoaded(); route("CARDS")
        compose.onAllNodesWithText("2694.32 SAR").assertCountEquals(1)
        compose.onNodeWithTag("dash-asset-BANK_ALJAZIRA-statement-facility").performScrollTo().performClick()
        compose.onAllNodesWithText("2694.32 SAR").assertCountEquals(1)
        compose.onNodeWithTag("dash-asset-BANK_ALJAZIRA-statement-primary").performScrollTo().performClick()
        compose.onAllNodesWithText("2694.32 SAR").assertCountEquals(0)
        compose.onNodeWithTag("dash-detail-back").performScrollTo().performClick()
        compose.onNodeWithTag("dash-asset-BANK_ALJAZIRA-statement-facility").performScrollTo().performClick()
        compose.onNodeWithTag("dash-asset-BANK_ALJAZIRA-statement-supplementary").performScrollTo().performClick()
        compose.onAllNodesWithText("2694.32 SAR").assertCountEquals(0)
        compose.onNodeWithTag("dash-detail-back").performScrollTo().performClick()
        route("LOANS")
        compose.onNodeWithText(context.getString(com.baraa.masroof.lab.beta.R.string.beta_asset_loan)).assertExists()
        route("COMMITMENTS")
        compose.onNodeWithText("TECH_FACILITY_ID").assertDoesNotExist()
        compose.onNodeWithText("TECH_LOAN_ID").assertDoesNotExist()
        compose.onNodeWithText("التزامي الشخصي").assertExists()
    }

    @Test fun partialCoverageNeverClaimsKnownEmptyRegistryOrCommitments() {
        val workflow = BetaDashboardWorkflow({ query, _ ->
            val period = DisplayPeriod(query.from, query.until, query.zone, PeriodKind.CALENDAR_MONTH, null)
            val amount = SignedAmount(BigDecimal("42.35"), Currency.SAR)
            val trend = SpendingTrend(null, null, period, Currency.SAR,
                listOf(DailySpending(at.atZone(query.zone).toLocalDate(), amount, amount, SignedAmount(BigDecimal.ZERO, Currency.SAR), emptySet())), amount)
            DashboardProduction(
                DashboardSnapshot(query, emptyList(), emptyList(), emptyList(), commitments = CommitmentsSummary(Currency.SAR, emptyList(), null, null, null),
                    analysis = AnalysisSummary(Currency.SAR, SummaryMetric.entries.associateWith { null }, 0, 0, 0, listOf(trend), emptyList())),
                setOf(DashboardSection.CONFIRMED_REGISTRY, DashboardSection.COMMITMENTS, DashboardSection.TRENDS, DashboardSection.MERCHANT_TOTALS), null)
        }, clock)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { BetaDashboardPanel(workflow) } } }
        waitLoaded()
        listOf("ACCOUNTS", "CARDS", "LOANS", "COMMITMENTS").forEach { section ->
            route(section); compose.onNodeWithTag("dash-empty").assertDoesNotExist()
            compose.onNodeWithTag("dash-unavailable").assertExists()
        }
        route("ANALYSIS")
        compose.onAllNodesWithTag("dash-unavailable").assertCountEquals(1) // Merchant coverage only; this ordinary series is complete.
        compose.onAllNodesWithText("42.35 SAR").assertCountEquals(2) // Precomputed average and point; no UI-derived money.
    }
}
