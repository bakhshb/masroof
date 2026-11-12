package com.baraa.masroof.application.patterns

import com.baraa.masroof.testsupport.saveCorrection
import com.baraa.masroof.testsupport.ingest
import com.baraa.masroof.testsupport.uniqueStorageContext
import com.baraa.masroof.testsupport.withAppContainer
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.engine.bank.patterns.*
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.TestStorageContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AlJaziraSharedPatternsTest {
    private val bankId = Bank.BANK_ALJAZIRA.id
    private val body = "Purchase\nAmount:10 SAR\nFrom account:4321\nMerchant:SHOP\nOn:2026-10-15 12:30"
    private fun raw(id: String) = RawSms(id, "Bank-AlJazira-AD", body, Instant.parse("2026-10-15T10:00:00Z"), id, SmsBodyHasher.sha256Hex(body))
    private suspend fun fixture(block: suspend (AppContainer) -> Unit) =
        withAppContainer(uniqueStorageContext("aljazira-pattern"), block = block)

    @Test fun actualIntakeUsesSharedDefinitionsAndDisableRestoreNeverRewritesPostedEvidence() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            w.confirmPatternAccount(bankId, "4321")
            assertTrue(c.sms.processRawSmsUseCase.ingest(raw("posted")) is SmsIngestionResult.Parsed)
            val posted = c.storage.parsedEventRepository.findByRawSmsId("posted")!!
            assertTrue("engine:message-pattern-roles-v1" in posted.event.confidence.reasons)
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
            val purchase = w.load().banks.single { it.bankId == bankId }.currentPatterns.single { it.id == "bank.purchase" }
            val disabled = w.setEnabled(w.load().generation, bankId, purchase.id, false)
            assertTrue(c.sms.processRawSmsUseCase.ingest(raw("pending")) is SmsIngestionResult.ReviewRequired)
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
            val restored = w.restoreRevision(disabled.generation, bankId, purchase.id, 1)
            assertEquals(3, restored.banks.single { it.bankId == bankId }.currentPatterns.single { it.id == purchase.id }.revision)
            assertEquals(1, w.applyPending(bankId, restored.generation).posted)
            assertEquals(2, c.storage.financialTransactionRepository.listAll().size)
            c.sms.processStoredSmsUseCase.process("posted")
            assertEquals(posted, c.storage.parsedEventRepository.findByRawSmsId("posted"))
            assertEquals(2, c.storage.financialTransactionRepository.listAll().size)
        }
    }

    @Test fun disablingAfterParseBlocksTheFinancialWriteWithoutReparsingAcceptedFacts() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            w.confirmPatternAccount(bankId, "4321")
            val raw = raw("between-parse-and-post")
            c.storage.rawSmsRepository.insertIfAbsent(raw)
            assertTrue(c.sms.processStoredSmsUseCase.reparseAndStore(raw, historicalBatch = true) is SmsIngestionResult.Parsed)
            val saved = c.storage.parsedEventRepository.findByRawSmsId(raw.id)
            w.setEnabled(w.load().generation, bankId, "bank.purchase", false)
            assertTrue(c.sms.processStoredSmsUseCase.process(raw.id) is SmsIngestionResult.DerivedIncomplete)
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
            assertEquals(saved, c.storage.parsedEventRepository.findByRawSmsId(raw.id))
            c.sms.processStoredSmsUseCase.recordExhaustedDerivedProcessing(raw.id)
            assertEquals(ReviewStatus.REQUIRED, c.storage.reviewRepository.findByRawSmsId(raw.id)!!.status)
            assertTrue(c.storage.processingRetryRepository.listRetryableRawSmsIds().contains(raw.id))
        }
    }

    @Test fun correctionArrivingBeforeInitialParseRetainsAuthorityAndStillProcesses() = runBlocking {
        fixture { c ->
            c.patterns.messagePatternsWorkflow.confirmPatternAccount(bankId, "4321")
            val raw = raw("early-correction")
            c.storage.rawSmsRepository.insertIfAbsent(raw)
            val correction = UserCorrection("early", raw.id, MessageFamily.PURCHASE,
                com.baraa.masroof.core.money.Money.of("15", com.baraa.masroof.core.money.Currency.SAR), null, null, Instant.parse("2026-10-15T10:00:00Z"))
            c.storage.database.saveCorrection(correction)
            assertTrue(c.sms.processStoredSmsUseCase.process(raw.id) is SmsIngestionResult.Parsed)
            assertEquals(correction, c.storage.userCorrectionRepository.latestForRawSmsId(raw.id))
            assertEquals("10.00", c.storage.parsedEventRepository.findByRawSmsId(raw.id)!!.event.amount!!.amount.toPlainString())
            assertEquals("15.00", c.storage.financialTransactionRepository.listAll().single().amount.amount.toPlainString())
        }
    }

    @Test fun loadingInstalledDefinitionsIsReadOnlyInsideAdmittedFinancialWork() = runBlocking {
        fixture { c ->
            val store = MessagePatternStore(c.storage.appContext)
            val before = store.captureRaw()
            val generation = store.load().generation
            c.storage.databaseAccessGate.withAccess {
                assertEquals(generation, c.patterns.messagePatternsWorkflow.load().generation)
            }
            assertEquals(before, store.captureRaw())
        }
    }

    @Test fun upgradingExistingTeachingAndRepeatedStartupKeepHistoryAndDisabledDefaults() = runBlocking {
        val context = TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "aljazira-upgrade-${java.util.UUID.randomUUID()}")
        val store = MessagePatternStore(context)
        val taught = SavedMessagePattern.fromChoices("existing-teaching", bankId, "AlJazira", "PURCHASE", "Old format:10SAR",
            listOf(FieldChoice("amount", 11, 16)))
        store.saveBank(0, BankMessagePatterns(bankId, listOf("AlJazira"), "My bank", listOf(taught)))
        try {
            withTimeout(20_000) {
                var c = AppContainer(context)
                try {
                    val w = c.patterns.messagePatternsWorkflow
                    val bank = w.load().banks.single { it.bankId == bankId }
                    assertEquals(taught, bank.patterns.single { it.id == taught.id })
                    assertEquals(13, bank.patterns.count { it.roleProfile != null })
                    w.setEnabled(w.load().generation, bankId, "bank.purchase", false)
                } finally { c.close() }
                val before = store.captureRaw()
                c = AppContainer(context)
                try {
                    val bank = c.patterns.messagePatternsWorkflow.load().banks.single { it.bankId == bankId }
                    assertFalse(bank.currentPatterns.single { it.id == "bank.purchase" }.enabled)
                    assertEquals(2, bank.patterns.count { it.id == "bank.purchase" })
                    assertEquals(before, store.captureRaw())
                    assertEquals(store.load(), MessagePatternConfigurationCodec.decode(MessagePatternConfigurationCodec.encode(store.load())))
                } finally { c.close() }
            }
        } finally { context.getDatabasePath("masroof.db").parentFile?.deleteRecursively() }
    }
}
