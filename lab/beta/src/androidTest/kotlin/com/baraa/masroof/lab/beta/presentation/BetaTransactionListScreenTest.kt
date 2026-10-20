package com.baraa.masroof.lab.beta.presentation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.BetaTransactionListWorkflow
import com.baraa.masroof.lab.finance.dashboard.DashboardQuery
import com.baraa.masroof.lab.finance.posting.PostingKey
import com.baraa.masroof.lab.finance.value.*
import com.baraa.masroof.ui.theme.MasroofTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.*

class BetaTransactionListScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val query=DashboardQuery(Environment.BETA,Instant.parse("2026-09-26T21:00:00Z"),Instant.parse("2026-10-26T21:00:00Z"),ZoneId.of("Asia/Riyadh"),Currency.SAR)
    private val snapshot=BetaFrozenProductionRead.snapshot(query)
    private fun advanced() {
        compose.onNodeWithTag("transactions-filters").performClick()
        compose.onNodeWithTag("transactions-filters-${BetaTransactionListWorkflow.types(snapshot.transactions).size+1}").performScrollTo().performClick()
    }
    private fun count(value: Int)=compose.onNodeWithText(compose.activity.getString(R.string.beta_list_dashboard_summary_transactions_title,value))
    @Test fun filterDraftRestoresAndCancelDiscardsWhileApplyAndClearUpdateOnlyTheList() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent {BetaLocaleProvider("en") {MasroofTheme {Box(Modifier.fillMaxSize()) {
            BetaDashboardTransactions(snapshot,enabled=true,onTransaction={},bounded=true)
        }}}}
        count(17).assertExists()
        advanced()
        compose.onNodeWithTag("transaction-type-EXPENSE").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("transaction-type-EXPENSE").performScrollTo().assertIsOn()
        compose.onNodeWithTag("transactions-filter-cancel").performClick()
        count(17).assertExists()
        advanced()
        compose.onNodeWithTag("transaction-type-EXPENSE").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("transaction-type-REFUND").performScrollTo().performClick()
        compose.onNodeWithTag("transactions-filter-apply").performClick()
        count(7).assertExists()
        compose.onNodeWithTag("transactions-search").performTextReplacement("NOON")
        count(2).assertExists()
        compose.onNodeWithTag("transactions-search").performTextReplacement("٨٠٠")
        count(1).assertExists()
        compose.onNodeWithTag("transactions-clear").performClick()
        count(17).assertExists()
        assertEquals(17,snapshot.transactions.size)
        compose.onNodeWithTag("transactions-more").assertDoesNotExist()
    }
    @Test fun sharingOpensOnlyAChooserContainingTheCurrentFilteredRows() {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        var chooser: android.content.Intent?=null
        val monitor=object: android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if(intent.action!=android.content.Intent.ACTION_CHOOSER) return null
                chooser=intent
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.setContent {BetaLocaleProvider("en") {MasroofTheme {
                var share by remember {mutableStateOf<BetaListShare?>(null)}
                Column(Modifier.fillMaxSize()) {
                    share?.let {BetaListShareAction(it)}
                    Box(Modifier.weight(1f)) {BetaDashboardTransactions(snapshot,enabled=true,onTransaction={},bounded=true,onShare={share=it})}
                }
            }}}
            compose.onNodeWithTag("transactions-search").performTextReplacement("NOON")
            count(2).assertExists()
            compose.onNodeWithTag("transactions-share").performClick()
            compose.runOnIdle {
                val send=chooser?.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)
                assertNotNull(send)
                assertEquals(android.content.Intent.ACTION_SEND,send!!.action)
                assertEquals("text/plain",send.type)
                val text=send.getStringExtra(android.content.Intent.EXTRA_TEXT)!!
                assertTrue(text.contains("NOON"))
                assertFalse(text.contains("AMAZON"))
                assertTrue(text.contains("Count: 2"))
                assertTrue(text.contains("1,400"))
            }
        } finally {instrumentation.removeMonitor(monitor)}
    }
    @Test fun largeListScrollsIndependentlyAndLastRowOpensItsExactIdentity() {
        val rows=(0 until 120).map {index -> snapshot.transactions.first().copy(key=PostingKey(Environment.BETA,"long-${index.toString().padStart(3,'0')}"))}
        var opened: String?=null
        compose.setContent {BetaLocaleProvider("en") {MasroofTheme {Box(Modifier.fillMaxSize()) {
            BetaDashboardTransactions(snapshot,rows,enabled=true,onTransaction={opened=it.transactionId},bounded=true)
        }}}}
        compose.onNodeWithTag("transactions-list").performScrollToIndex(119)
        compose.onNodeWithTag("transactions-search").assertIsDisplayed()
        compose.onNodeWithTag("dash-transaction-long-119").performClick()
        compose.runOnIdle {assertEquals("long-119",opened)}
    }
}
