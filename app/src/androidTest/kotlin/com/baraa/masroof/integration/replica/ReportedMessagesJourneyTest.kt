package com.baraa.masroof.integration.replica

import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.testsupport.*
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baraa.masroof.R
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.interpretation.InterpretationOrigin
import com.baraa.masroof.application.review.ReviewWorkflowResult
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.presentation.common.MasroofScreenBackground
import com.baraa.masroof.presentation.onboarding.*
import com.baraa.masroof.presentation.review.*
import com.baraa.masroof.presentation.dashboard.*
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.InterpretationTestAdapter
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.ui.theme.MasroofTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ReportedMessagesJourneyTest {
    @get:Rule val compose=createComposeRule()
    private val host=InstrumentationRegistry.getInstrumentation().targetContext
    private val bank=Bank.BANK_ALJAZIRA
    private val names=listOf("device_instant_transfer","device_google_pay","device_water_bill")
    private fun raw(id: String,body: String)=RawSms(id,"AlJazira",body,Instant.now(),id,SmsBodyHasher.sha256Hex(body))

    @Test fun allThreeReportedMessagesPostOnceAndPreserveAmountsRolesAndTimesAfterReopen() = runBlocking {
        TestRuntime.reset(host)
        val source=ReferenceHistory.source(host,names)
        var c=AppContainer(TestStorageContext(host),smsDataSourceOverride=source)
        val store=ViewModelStore()
        try {
            val account=AccountReference(bank,"3001"); val card=CardReference(bank,"8219")
            c.onboardingOwnershipWorkflow.confirmAccountOwned(account)
            c.onboardingOwnershipWorkflow.confirmCardOwned(card)
            c.cardRegistryRepository.configure(card,CardConfiguration(CardNetwork.MADA,CardType.DEBIT,linkedAccount=account))
            val import=c.historicalSmsScanner.scan(Instant.EPOCH)
            assertEquals(import.toString(),0,import.failed)
            val rows=c.financialTransactionRepository.listAll().sortedBy {it.id}
            assertEquals(rows.toString(),3,rows.size)
            assertTrue(c.reviewRepository.listRequired().toString(),c.reviewRepository.listRequired().isEmpty())
            assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT,rows.single {it.amount.amount.toPlainString()=="100.00"}.type)
            assertEquals(FinancialTransactionType.EXPENSE,rows.single {it.amount.amount.toPlainString()=="300.00"}.type)
            assertEquals(FinancialTransactionType.BILL_PAYMENT,rows.single {it.amount.amount.toPlainString()=="16.23"}.type)
            assertEquals("card:BANK_ALJAZIRA:8219",rows.single {it.amount.amount.toPlainString()=="300.00"}.sourceContainerId)
            assertTrue(rows.filter {it.amount.amount.toPlainString()!="300.00"}.all {it.sourceContainerId=="account:BANK_ALJAZIRA:3001"})
            val records=c.parsedEventRepository.listAll()
            assertTrue(records.all {it.details.occurredAtLocal!=null && it.event.occurredAt==null})
            for(record in records) assertEquals(InterpretationOrigin.RULES,c.interpretationProvenanceWorkflow.load(record.event.rawSmsId).origin)
            val before=c.dashboardService.loadOverview(ReferenceHistory.period())
            assertEquals(3,c.historicalSmsScanner.scan(Instant.EPOCH).duplicates)
            assertEquals(rows,c.financialTransactionRepository.listAll().sortedBy {it.id})
            c.close();c=AppContainer(TestStorageContext(host),smsDataSourceOverride=source)
            assertEquals(rows,c.financialTransactionRepository.listAll().sortedBy {it.id})
            assertEquals(before.summary,c.dashboardService.loadOverview(ReferenceHistory.period()).summary)
            lateinit var dashboard: DashboardViewModel
            compose.runOnUiThread {dashboard=ViewModelProvider(store,dashboardViewModelFactory(c,{true}))[DashboardViewModel::class.java]}
            compose.setContent {MasroofTheme(darkTheme=true) {MasroofScreenBackground {
                val state by dashboard.uiState.collectAsState()
                state.allTransactions.find {it.id==state.selectedTransactionId}?.let { selected ->
                    TransactionDetailScreen(selected,state.selectedTransactionSms,state.selectedTransactionSmsLoading,
                        false,false,false,onBack={},onReclassify={},onIgnore={})
                }
            }}}
            compose.runOnUiThread {dashboard.refresh()}
            compose.waitUntil(30_000) {!dashboard.uiState.value.loading && dashboard.uiState.value.allTransactions.size==3}
            for(row in rows) {
                compose.runOnUiThread {dashboard.openTransactionDetail(row.id)}
                compose.waitUntil(20_000) {!dashboard.uiState.value.selectedTransactionSmsLoading && dashboard.uiState.value.selectedTransactionSms.isNotEmpty()}
                compose.onNodeWithTag("interpretation_provenance").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(host.getString(R.string.lab_template_source)).assertDoesNotExist()
                val family=mapOf("100.00" to R.string.review_family_transfer_out,"300.00" to R.string.review_family_purchase,
                    "16.23" to R.string.review_family_bill_payment).getValue(row.amount.amount.toPlainString())
                compose.onNodeWithText(host.getString(R.string.lab_inferred_family,host.getString(family))).performScrollTo().assertIsDisplayed()
                screenshot("reported-${row.amount.amount.toPlainString().replace('.','-')}-dark")
            }
        } finally {compose.runOnUiThread {store.clear()};c.close();TestRuntime.reset(host)}
    }

    @Test fun unknownMessageOffersTransfersAndManualDecisionPostsOnceWithoutClaimingMl() {
        runBlocking {TestRuntime.reset(host)}
        val c=AppContainer(TestStorageContext(host))
        val store=ViewModelStore()
        try {
            runBlocking {
                c.onboardingOwnershipWorkflow.confirmAccountOwned(AccountReference(bank,"3001"))
                c.processRawSmsUseCase.ingest(raw("manual-transfer","حركة خاصة جديدة\nمن حساب: 3001\nالى حساب: 9466\nمبلغ: SAR 55\nفي: 2026-10-06 17:43"))
            }
            lateinit var viewModel: ReviewViewModel
            compose.runOnUiThread {viewModel=ViewModelProvider(store,reviewViewModelFactory(c))[ReviewViewModel::class.java]}
            compose.setContent {MasroofTheme(darkTheme=true) {MasroofScreenBackground {ReviewRoute(viewModel)}}}
            compose.waitUntil(20_000) {!viewModel.uiState.value.loading && viewModel.uiState.value.items.size==1}
            val item=viewModel.uiState.value.items.single()
            compose.runOnUiThread {viewModel.openDetail(item.id)}
            compose.waitUntil(20_000) {viewModel.uiState.value.selectedDetail!=null}
            compose.onNodeWithTag("review-why").performScrollTo().performClick()
            compose.onNodeWithTag("interpretation_provenance").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(host.getString(R.string.lab_unresolved_source)).assertExists()
            compose.onNodeWithTag("review-correct-type").performScrollTo().performClick()
            for(type in listOf(FinancialTransactionType.EXTERNAL_TRANSFER_OUT,FinancialTransactionType.EXTERNAL_TRANSFER_IN,FinancialTransactionType.SELF_TRANSFER))
                compose.onNodeWithTag(reviewResolveTypeTestTag(type)).assertExists()
            val action=compose.onNodeWithTag(reviewResolveTypeTestTag(FinancialTransactionType.EXTERNAL_TRANSFER_OUT))
            action.performScrollTo()
            screenshot("manual-transfer-dark")
            action.performClick()
            compose.onNodeWithTag("sheet-apply").performClick()
            compose.waitUntil(20_000) {viewModel.uiState.value.selectedDetail==null && viewModel.uiState.value.items.isEmpty()}
            runBlocking {
                val rows=c.financialTransactionRepository.listAll()
                assertEquals(1,rows.size)
                assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT,rows.single().type)
                assertEquals(InterpretationOrigin.MANUAL,c.interpretationProvenanceWorkflow.load("manual-transfer").origin)
                assertTrue(c.reviewWorkflowService.resolveAsFinancialType(item.id,FinancialTransactionType.EXTERNAL_TRANSFER_OUT) is ReviewWorkflowResult.Rejected)
                assertEquals(rows,c.financialTransactionRepository.listAll())
            }
        } finally {compose.runOnUiThread {store.clear()}; c.close();runBlocking {TestRuntime.reset(host)}}
    }

    @Test fun importDateTextHasVisibleGlyphsInArabicDarkAndLightThemes() {
        runBlocking {TestRuntime.reset(host)}
        val c=AppContainer(TestStorageContext(host))
        val store=ViewModelStore()
        val configuration=Configuration(host.resources.configuration).apply {setLocale(Locale.forLanguageTag("ar"))}
        val arabic=host.createConfigurationContext(configuration)
        var dark by mutableStateOf(true)
        try {
            lateinit var viewModel: OnboardingViewModel
            lateinit var settings: com.baraa.masroof.presentation.settings.SettingsViewModel
            compose.runOnUiThread {
                viewModel=ViewModelProvider(store,onboardingViewModelFactory(c,c.onboardingPreferencesRepository){true})[OnboardingViewModel::class.java]
                settings=ViewModelProvider(store,com.baraa.masroof.presentation.settings.settingsViewModelFactory(c,"test",{true}))[com.baraa.masroof.presentation.settings.SettingsViewModel::class.java]
            }
            compose.setContent {CompositionLocalProvider(LocalContext provides arabic,LocalConfiguration provides configuration) {
                MasroofTheme(darkTheme=dark) {MasroofScreenBackground {OnboardingRoute(viewModel,{},{},{}, settings)}}
            }}
            compose.runOnUiThread {viewModel.onStartClicked()}
            compose.waitUntil(20_000) {viewModel.uiState.value.bankCatalog != null}
            compose.runOnUiThread {viewModel.toggleBank(com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA.id);viewModel.continueFromBanks()}
            compose.waitUntil(20_000) {viewModel.uiState.value.step==OnboardingStep.IMPORT_DATE}
            val labels=listOf(R.string.onboarding_date_current_month,R.string.onboarding_date_last_30)
            for(isDark in listOf(true,false)) {
                compose.runOnUiThread {dark=isDark};compose.waitForIdle()
                for(label in labels) {
                    val pixels=compose.onNodeWithText(arabic.getString(label)).assertIsDisplayed().captureToImage().toPixelMap()
                    var glyphs=0
                    for(y in 0 until pixels.height) for(x in 0 until pixels.width)
                        if(if(isDark) pixels[x,y].luminance()>0.6f else pixels[x,y].luminance()<0.1f) glyphs++
                    assertTrue("Visible text glyphs in ${if(isDark) "dark" else "light"}: $label",glyphs>20)
                }
                screenshot(if(isDark) "import-date-ar-dark" else "import-date-ar-light")
            }
        } finally {compose.runOnUiThread {store.clear()}; c.close();runBlocking {TestRuntime.reset(host)}}
    }

    @Test fun manualTransfersRequireOwnedEndpointsAndRejectExternalClassificationBetweenOwnedAccounts() = runBlocking {
        TestRuntime.reset(host)
        val c=AppContainer(TestStorageContext(host))
        try {
            val source=AccountReference(bank,"3001");val destination=AccountReference(bank,"3002")
            c.onboardingOwnershipWorkflow.confirmAccountOwned(source)
            c.onboardingOwnershipWorkflow.confirmAccountOwned(destination)
            suspend fun prepare(id: String, from: AccountReference?, to: AccountReference?): String {
                c.processRawSmsUseCase.ingest(raw(id,"حركة خاصة جديدة\nمن حساب: 3001\nمبلغ: SAR 55\nفي: 2026-10-06 17:43"))
                val record=requireNotNull(c.parsedEventRepository.findByRawSmsId(id))
                c.parsedEventRepository.save(record.event.copy(sourceAccountRef=from,destinationAccountRef=to),record.details)
                return requireNotNull(c.reviewRepository.findByRawSmsId(id)).id
            }
            val own=prepare("manual-own",source,destination)
            assertTrue(c.reviewWorkflowService.resolveAsFinancialType(own,FinancialTransactionType.EXTERNAL_TRANSFER_OUT) is ReviewWorkflowResult.Rejected)
            assertTrue(c.reviewWorkflowService.resolveAsFinancialType(own,FinancialTransactionType.SELF_TRANSFER) is ReviewWorkflowResult.Success)
            val missing=prepare("manual-missing",null,destination)
            assertTrue(c.reviewWorkflowService.resolveAsFinancialType(missing,FinancialTransactionType.SELF_TRANSFER) is ReviewWorkflowResult.Rejected)
            val incoming=prepare("manual-incoming",AccountReference(Bank("BANK_RIYAD"),"3001"),destination)
            assertTrue(c.reviewWorkflowService.resolveAsFinancialType(incoming,FinancialTransactionType.EXTERNAL_TRANSFER_IN) is ReviewWorkflowResult.Success)
            val unowned=prepare("manual-unowned",AccountReference(bank,"3999"),null)
            assertTrue(c.reviewWorkflowService.resolveAsFinancialType(unowned,FinancialTransactionType.EXTERNAL_TRANSFER_OUT) is ReviewWorkflowResult.Rejected)
            val same=prepare("manual-same",source,source)
            assertTrue(c.reviewWorkflowService.resolveAsFinancialType(same,FinancialTransactionType.SELF_TRANSFER) is ReviewWorkflowResult.Rejected)
            assertEquals(2,c.financialTransactionRepository.listAll().size)
        } finally {c.close();TestRuntime.reset(host)}
    }

    @Test fun previouslyUnresolvedReportedMessagesRecoverAfterRuleUpgradeWithoutReset() = runBlocking {
        TestRuntime.reset(host)
        var c=TestRuntime.container(host)
        try {
            c.awaitPostStartupBackgroundWorkForTesting()
            val account=AccountReference(bank,"3001");val card=CardReference(bank,"8219")
            c.onboardingOwnershipWorkflow.confirmAccountOwned(account)
            c.onboardingOwnershipWorkflow.confirmCardOwned(card)
            c.cardRegistryRepository.configure(card,CardConfiguration(CardNetwork.MADA,CardType.DEBIT,linkedAccount=account))
            val service=requireNotNull(c.interpretationService)
            val adapter=InterpretationTestAdapter { service.interpret(it, "BANK_ALJAZIRA") }
            for(name in names) {
                val f=ReferenceHistory.fixture(name)
                val evidence=raw(name,f.getValue("body").jsonPrimitive.content)
                c.rawSmsRepository.insertIfAbsent(evidence)
                val current=adapter.parse(SmsParseInput(name,evidence.sender,evidence.body,evidence.receivedAt)) as ParseResult.Success
                // Persist the previous release's incomplete interpretation, retaining original SMS evidence.
                c.parsedEventRepository.save(current.event.copy(messageFamily=MessageFamily.UNKNOWN,parseStatus=ParseStatus.REVIEW_REQUIRED,
                    amount=null,confidence=Confidence(0.0,listOf("engine:previous","rules:previous"))),current.details)
                c.reviewRepository.upsertRequired(name,ReviewKind.NEEDS_REVIEW,listOf("unknown_message_family"),Instant.now())
            }
            assertTrue(c.financialTransactionRepository.listAll().isEmpty())
            TestRuntime.close()
            c=TestRuntime.container(host)
            c.awaitPostStartupBackgroundWorkForTesting()
            requireNotNull(c.interpretationService).synchronizeFeedback()
            // A worker admitted by the previous container can retry during close/reopen; cover one default backoff.
            withTimeout(androidx.work.WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS + 15_000) {while(c.financialTransactionRepository.listAll().size!=3 || c.reviewRepository.listRequired().isNotEmpty()) delay(50)}
            assertEquals(setOf("100.00","300.00","16.23"),c.financialTransactionRepository.listAll().map {it.amount.amount.toPlainString()}.toSet())
            assertEquals(3,c.rawSmsRepository.listIdsByReceivedAt().size)
        } finally {TestRuntime.reset(host)}
    }

    private fun screenshot(name: String) = ScreenshotArtifactWriter.capture(compose, "reported-fixes", name)
}
