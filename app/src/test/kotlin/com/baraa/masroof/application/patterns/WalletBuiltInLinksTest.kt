package com.baraa.masroof.application.patterns

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.engine.bank.patterns.PatternAccountRole
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.TestStorageContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** One confirmed account covers a wallet bank's built-in patterns. Fictional values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletBuiltInLinksTest {
    private val qattah = "Pay qattah\nAmount: 84.86 SAR\nTo: SECOND PERSON\nAt: 08/10/26 12:34"

    @Test fun approvingOneStcPatternLinksTheOtherStcBuiltInsThatNameNoAccount() = runBlocking {
        fixture { c ->
            val w = c.messagePatternsWorkflow
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", "stc-qattah", PatternOperation.TRANSFER_OUT,
                account.id, qattah, null, emptySet()))

            val stc = w.load().banks.single { it.bankId == "BANK_STC" }.currentPatterns
            assertEquals(8, stc.size)
            // An "Acc:", "Account" or "From <account>" number may be one of the user's own accounts, so those layouts keep their own approval.
            assertEquals(setOf("stc-qattah", "stc-qattah-ar", "stc-top-up"), stc.filter { it.factContract != null }.map { it.id }.toSet())
            stc.filter { it.factContract != null }.forEach { pattern ->
                val link = pattern.factContract!!.accountBindings.single()
                assertEquals(pattern.id, "4321", link.last4)
                val incoming = pattern.label == "TRANSFER_IN"
                assertEquals(pattern.id, if (incoming) PatternAccountRole.DESTINATION else PatternAccountRole.SOURCE, link.role)
            }
            assertPosted(c, "ar-out", "STC Bank", "دفع قطة\nمبلغ:37.50 ر.س\nإلى:TEST PERSON A\nفي:13/10/26 12:30", sourceLast4 = "4321")
            assertPosted(c, "in", "STC Bank", "Adding money to account\nAmount: 300.00 SAR\nVia: *6789\nAt: 2026-10-12 18:06", destinationLast4 = "4321")
        }
    }

    @Test fun aBuiltInSwitchedOnLaterTakesTheLinkButARemovedLinkIsNotAddedBack() = runBlocking {
        fixture { c ->
            val w = c.messagePatternsWorkflow
            w.setEnabled(w.load().generation, "BANK_STC", "stc-qattah-ar", false)
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", "stc-qattah", PatternOperation.TRANSFER_OUT,
                account.id, qattah, null, emptySet()))
            suspend fun arabic() = w.load().banks.single { it.bankId == "BANK_STC" }.currentPatterns.single { it.id == "stc-qattah-ar" }
            assertNull("a switched-off built-in is not linked", arabic().factContract)

            // Like a layout that arrives with an update: on, never linked.
            w.setEnabled(w.load().generation, "BANK_STC", "stc-qattah-ar", true)
            w.linkWalletBuiltIns()
            assertEquals("4321", arabic().factContract!!.accountBindings.single().last4)

            w.restoreRevision(w.load().generation, "BANK_STC", "stc-qattah-ar", 1)
            w.linkWalletBuiltIns()
            assertNull("the user removed this link", arabic().factContract)
        }
    }

    @Test fun ownApprovedCopyOfAStcLayoutKeepsThatBuiltInUnlinkedAndKeepsPosting() = runBlocking {
        fixture { c ->
            val w = c.messagePatternsWorkflow
            val body = "دفع قطة\nمبلغ:37.50 ر.س\nإلى:TEST PERSON A\nفي:13/10/26 12:30"
            val taught = w.saveTeaching(w.teach(w.load().generation, "BANK_STC", "STC Bank", PatternOperation.TRANSFER_OUT, body,
                listOf(binding(body, "amount", "37.50 ر.س"), binding(body, "counterparty", "TEST PERSON A"), binding(body, "occurred_at", "13/10/26 12:30"))))
            val mine = taught.banks.single { it.bankId == "BANK_STC" }.currentPatterns.single { it.id !in BUNDLED_STC }.id
            w.setEnabled(w.load().generation, "BANK_STC", mine, true)
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", mine, PatternOperation.TRANSFER_OUT,
                account.id, body, null, emptySet()))

            val stc = w.load().banks.single { it.bankId == "BANK_STC" }.currentPatterns
            assertNull("the built-in with the same layout stays unlinked", stc.single { it.id == "stc-qattah-ar" }.factContract)
            assertEquals("4321", stc.single { it.id == "stc-qattah" }.factContract!!.accountBindings.single().last4)
            assertTrue(stc.any { it.id == mine && it.enabled })
            assertPosted(c, "own-ar", "STC Bank", body.replace("37.50", "12.00"), sourceLast4 = "4321")
        }
    }

    @Test fun twoOwnedAccountsOrAMessageKeyedLinkLinkNothing() = runBlocking {
        fixture { c ->
            val w = c.messagePatternsWorkflow
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            w.confirmPatternAccount("BANK_STC", "8765")
            w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", "stc-qattah", PatternOperation.TRANSFER_OUT,
                account.id, qattah, null, emptySet()))
            assertEquals(1, w.load().banks.single { it.bankId == "BANK_STC" }.currentPatterns.count { it.factContract != null })
        }
        fixture { c ->
            val w = c.messagePatternsWorkflow
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            // "Acc:321 is my account": the message number tells accounts apart, so no account is assumed for other layouts.
            val incoming = "Internal incoming transfer\nAmount:10SAR\nFrom:TEST PERSON\nAcc:321\nAt:15/10/26 12:30"
            w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", "stc-internal-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, "account_reference", emptySet()))
            assertEquals(1, w.load().banks.single { it.bankId == "BANK_STC" }.currentPatterns.count { it.factContract != null })
        }
    }

    @Test fun noConfirmedLinkLeavesBuiltInsWaitingForApproval() = runBlocking {
        fixture { c ->
            val w = c.messagePatternsWorkflow
            w.confirmPatternAccount("BANK_STC", "4321")
            w.linkWalletBuiltIns()
            assertTrue(w.load().banks.single { it.bankId == "BANK_STC" }.currentPatterns.all { it.factContract == null })
        }
    }

    @Test fun ownBarqPatternKeepsPostingBesideALinkedBuiltInRule() = runBlocking {
        val body = "حوالة واردة داخلية\n100.00 SAR\nحساب المرسل: **5555\n2026-10-02 22:01"
        for (builtInOn in listOf(true, false)) fixture { c ->
            val w = c.messagePatternsWorkflow
            val account = w.confirmPatternAccount("BANK_BARQ", "1482")
            if (!builtInOn) w.setEnabled(w.load().generation, "BANK_BARQ", "barq.transfer-in", false)
            val taught = w.saveTeaching(w.teach(w.load().generation, "BANK_BARQ", "barq app", PatternOperation.TRANSFER_IN, body,
                listOf(binding(body, "amount", "100.00 SAR"), binding(body, "source_account", "**5555"),
                    binding(body, "occurred_at", "2026-10-02 22:01"))))
            val mine = taught.banks.single { it.bankId == "BANK_BARQ" }.currentPatterns.single { it.roleProfile == null }.id
            w.setEnabled(w.load().generation, "BANK_BARQ", mine, true)
            w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_BARQ", mine, PatternOperation.TRANSFER_IN,
                account.id, body, null, setOf("source_account")))

            val barq = w.load().banks.single { it.bankId == "BANK_BARQ" }
            val builtIn = barq.currentPatterns.single { it.id == "barq.transfer-in" }
            assertEquals(builtInOn, builtIn.enabled)
            // A role rule is completed by the user's same-family pattern, so a link never makes them conflict; an off rule stays unlinked.
            assertEquals(if (builtInOn) "1482" else null, builtIn.factContract?.accountBindings?.singleOrNull()?.last4)
            assertTrue("the user's own pattern is never removed", barq.currentPatterns.any { it.id == mine && it.enabled })
            assertEquals("1482", barq.currentPatterns.single { it.id == "barq.withdrawal" }.factContract!!.accountBindings.single().last4)
            // Card movements never take the account link.
            assertNull(barq.currentPatterns.single { it.id == "barq.purchase" }.factContract!!.accountBindings.singleOrNull())
            assertPosted(c, "barq-in", "barq app", body, destinationLast4 = "1482")
            assertPosted(c, "barq-cash", "barq app", "سحب نقدي\nمبلغ 200 SAR\n2026-09-15 12:30", sourceLast4 = "1482")
        }
    }

    private val BUNDLED_STC = setOf("stc-internal-in", "stc-internal-out", "stc-internal-out-ar", "stc-local-in", "stc-qattah",
        "stc-qattah-ar", "stc-sarie-in", "stc-top-up")

    private fun binding(body: String, field: String, value: String) =
        body.indexOf(value).let { PatternBinding(field, it, it + value.length) }

    private suspend fun assertPosted(c: AppContainer, id: String, sender: String, body: String,
        sourceLast4: String? = null, destinationLast4: String? = null) {
        c.processRawSmsUseCase.ingest(RawSms(id, sender, body, Instant.parse("2026-10-15T10:00:00Z"), id, SmsBodyHasher.sha256Hex(body)))
        val event = c.parsedEventRepository.findByRawSmsId(id)!!.event
        assertEquals(body, ParseStatus.SUCCESS, event.parseStatus)
        sourceLast4?.let { assertEquals(it, event.sourceAccountRef?.maskedNumber) }
        destinationLast4?.let { assertEquals(it, event.destinationAccountRef?.maskedNumber) }
    }

    private suspend fun fixture(block: suspend (AppContainer) -> Unit) {
        val context = TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "wallet-${java.util.UUID.randomUUID()}")
        val container = AppContainer(context)
        try { kotlinx.coroutines.withTimeout(20_000) { block(container) } } finally { container.close(); context.getDatabasePath("masroof.db").parentFile?.deleteRecursively() }
    }
}
