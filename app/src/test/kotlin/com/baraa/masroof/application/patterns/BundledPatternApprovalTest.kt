package com.baraa.masroof.application.patterns

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.engine.bank.patterns.PatternAccountRole
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.TestStorageContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Built-in rules can be linked to an owned account or card; their family and readings stay fixed. Fictional values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BundledPatternApprovalTest {
    private val incoming = "حوالة واردة داخلية\n100.00 SAR\nحساب المرسل: **5555\n2026-10-02 22:01"
    private val purchase = "شراء\nمبلغ 25 SAR\nلدى TEST\n2026-09-15 12:30"
    private val cardConfig = CardConfiguration(CardNetwork.VISA, CardType.CREDIT, CardRole.STANDALONE)

    @Test fun linkedIncomingTransferPostsPendingAndNewMessagesToTheChosenAccount() = runBlocking {
        fixture { c ->
            val w = c.messagePatternsWorkflow
            c.processRawSmsUseCase.ingest(sms("pending", incoming))
            assertTrue(c.financialTransactionRepository.listAll().isEmpty())
            assertTrue(w.catalog().banks.single { it.bankId == BARQ }.currentPatterns.single { it.id == "barq.transfer-in" }.canApprove)
            val account = w.confirmPatternAccount(BARQ, "1234")
            w.saveFinancial(w.previewFinancial(w.catalog().generation, BARQ, "barq.transfer-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, null, emptySet()))
            val view = w.catalog().banks.single { it.bankId == BARQ }.currentPatterns.single { it.id == "barq.transfer-in" }
            assertEquals(PatternApprovalStatus.BUNDLED, view.status)
            assertEquals("1234", view.approval?.accountLast4)
            assertFalse(view.canEdit)
            assertEquals(1, w.applyPending(BARQ, w.catalog().generation).posted)
            c.processRawSmsUseCase.ingest(sms("live", incoming.replace("100.00", "40.00")))
            val legs = c.parsedEventRepository.findByRawSmsId("live")!!.event
            assertEquals(ParseStatus.SUCCESS, legs.parseStatus)
            assertEquals(AccountReference(Bank(BARQ), "1234"), legs.destinationAccountRef)
            assertEquals(2, c.financialTransactionRepository.listAll().size)
        }
    }

    @Test fun builtInApprovalKeepsItsFamilyAndRejectsReferenceAndFundingAccountChoices() = runBlocking {
        fixture { c ->
            val w = c.messagePatternsWorkflow
            val account = w.confirmPatternAccount(BARQ, "1234")
            fun rejected(block: suspend () -> Unit) = assertTrue(runBlocking { runCatching { block() } }.isFailure)
            rejected { w.previewFinancial(w.catalog().generation, BARQ, "barq.transfer-in", PatternOperation.TRANSFER_OUT,
                account.id, incoming, null, emptySet()) }
            rejected { w.previewFinancial(w.catalog().generation, BARQ, "barq.transfer-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, "source_account", emptySet()) }
            // A purchase is funded by its card; a funding account would read a credit purchase as an account debit.
            rejected { w.previewFinancial(w.catalog().generation, BARQ, "barq.purchase", PatternOperation.PURCHASE,
                account.id, purchase, null, emptySet()) }
            // Links never cross families: an incoming transfer has no card.
            val card = requireNotNull(c.cardRegistryRepository.get(c.settingsRegistryWorkflow.addCard(BARQ, "4321", null, cardConfig)))
            rejected { w.previewFinancial(w.catalog().generation, BARQ, "barq.transfer-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, null, emptySet(), cardId = card.id) }
            assertFalse(w.catalog().banks.single { it.bankId == BARQ }.currentPatterns.single { it.id == "barq.notice" }.canApprove)
        }
    }

    @Test fun purchaseWithoutACardTakesTheLinkedCardAndANamedCardStaysItsOwn() = runBlocking {
        fixture { c ->
            val w = c.messagePatternsWorkflow
            val card = requireNotNull(c.cardRegistryRepository.get(c.settingsRegistryWorkflow.addCard(BARQ, "4321", null, cardConfig)))
            val other = requireNotNull(c.cardRegistryRepository.get(c.settingsRegistryWorkflow.addCard(BARQ, "1111", null, cardConfig)))
            val named = "شراء\nمبلغ 25 SAR\nبطاقة مدى: **4321\nلدى TEST\n2026-09-15 12:30"
            assertTrue(runCatching { w.previewFinancial(w.catalog().generation, BARQ, "barq.purchase", PatternOperation.PURCHASE,
                null, named, null, emptySet(), cardId = other.id) }.isFailure)
            // An example that names the chosen card still saves it as the link for messages that name none.
            w.saveFinancial(w.previewFinancial(w.catalog().generation, BARQ, "barq.purchase", PatternOperation.PURCHASE,
                null, named, null, emptySet(), cardId = card.id))
            val links = w.load().banks.single { it.bankId == BARQ }.currentPatterns.single { it.id == "barq.purchase" }.factContract!!.accountBindings
            assertEquals(listOf(PatternAccountRole.CARD to "4321"), links.map { it.role to it.last4 })
            assertEquals("4321", w.catalog().banks.single { it.bankId == BARQ }.currentPatterns.single { it.id == "barq.purchase" }.approval?.cardLast4)
            c.processRawSmsUseCase.ingest(sms("purchase", purchase))
            assertEquals(CardReference(Bank(BARQ), "4321"), c.parsedEventRepository.findByRawSmsId("purchase")!!.event.cardRef)
            assertEquals(1, c.financialTransactionRepository.listAll().size)
            // A message naming another owned card keeps that card; the link never moves it.
            c.processRawSmsUseCase.ingest(sms("other-card", named.replace("4321", "1111")))
            assertEquals(CardReference(Bank(BARQ), "1111"), c.parsedEventRepository.findByRawSmsId("other-card")!!.event.cardRef)
            assertEquals(2, c.financialTransactionRepository.listAll().size)
        }
    }

    @Test fun losingOwnershipOfALinkedAccountOrCardStopsPosting() = runBlocking {
        fixture { c ->
            val w = c.messagePatternsWorkflow
            val account = w.confirmPatternAccount(BARQ, "1234")
            w.saveFinancial(w.previewFinancial(w.catalog().generation, BARQ, "barq.transfer-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, null, emptySet()))
            val ref = c.settingsRegistryWorkflow.addCard(BARQ, "4321", null, cardConfig)
            val card = requireNotNull(c.cardRegistryRepository.get(ref))
            w.saveFinancial(w.previewFinancial(w.catalog().generation, BARQ, "barq.purchase", PatternOperation.PURCHASE,
                null, purchase, null, emptySet(), cardId = card.id))
            c.accountRegistryRepository.setOwnership(AccountReference(Bank(BARQ), "1234"), OwnershipStatus.EXTERNAL)
            c.cardRegistryRepository.setOwnership(ref, OwnershipStatus.EXTERNAL)
            c.processRawSmsUseCase.ingest(sms("after-account", incoming))
            c.processRawSmsUseCase.ingest(sms("after-card", purchase))
            assertTrue(c.financialTransactionRepository.listAll().isEmpty())
        }
    }

    @Test fun linkingLaterKeepsMessagesThatNameTheirOwnAccountPostingAndPairing() = runBlocking {
        fixture { c ->
            val w = c.messagePatternsWorkflow
            val named = "حوالة واردة داخلية\n100.00 SAR\nحساب المرسل: **5555\nلحساب: **1111\n2026-10-02 22:01"
            w.confirmPatternAccount(BARQ, "1111")
            c.processRawSmsUseCase.ingest(sms("before-link", named))
            val posted = requireNotNull(c.parsedEventRepository.findByRawSmsId("before-link"))
            assertEquals(ParseStatus.SUCCESS, posted.event.parseStatus)
            val account = w.confirmPatternAccount(BARQ, "1234")
            w.saveFinancial(w.previewFinancial(w.catalog().generation, BARQ, "barq.transfer-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, null, emptySet()))
            val policy = com.baraa.masroof.application.interpretation.PatternInterpretationWritePolicy(
                com.baraa.masroof.data.patterns.MessagePatternStore(context), c.accountRegistryRepository, c.cardRegistryRepository)
            assertTrue("a leg posted before the link still pairs", policy.isCurrent(posted, posted = true))
            // The approval summary shows where an example really posts, not only the chosen link.
            val preview = w.previewFinancial(w.catalog().generation, BARQ, "barq.transfer-in", PatternOperation.TRANSFER_IN,
                account.id, named, null, emptySet())
            assertEquals("1111", preview.accountLast4)
            assertEquals("1234", w.previewFinancial(w.catalog().generation, BARQ, "barq.transfer-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, null, emptySet()).accountLast4)
            c.processRawSmsUseCase.ingest(sms("after-link", named.replace("100.00", "70.00")))
            assertEquals(AccountReference(Bank(BARQ), "1111"),
                c.parsedEventRepository.findByRawSmsId("after-link")!!.event.destinationAccountRef)
        }
    }

    private lateinit var context: Context
    /** Leaving the patterns screen cancels its scope; an apply that started must still post what it reparsed. */
    @Test fun applyPendingFinishesWhenTheCallerIsCancelled() = runBlocking {
        fixture { c ->
            val w = c.messagePatternsWorkflow
            c.processRawSmsUseCase.ingest(sms("pending", incoming))
            val account = w.confirmPatternAccount(BARQ, "1234")
            w.saveFinancial(w.previewFinancial(w.catalog().generation, BARQ, "barq.transfer-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, null, emptySet()))
            val generation = w.catalog().generation

            val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { w.applyPending(BARQ, generation) }
            job.cancel()
            job.join()

            assertEquals(1, c.financialTransactionRepository.listAll().size)
        }
    }

    private suspend fun fixture(block: suspend (AppContainer) -> Unit) {
        context = TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "bundled-${java.util.UUID.randomUUID()}")
        val container = AppContainer(context)
        try { kotlinx.coroutines.withTimeout(20_000) { block(container) } } finally { container.close(); context.getDatabasePath("masroof.db").parentFile?.deleteRecursively() }
    }
    private fun sms(id: String, body: String) = RawSms(id, "barq app", body, Instant.parse("2026-10-15T10:00:00Z"), id, SmsBodyHasher.sha256Hex(body))

    private companion object { const val BARQ = "BANK_BARQ" }
}
