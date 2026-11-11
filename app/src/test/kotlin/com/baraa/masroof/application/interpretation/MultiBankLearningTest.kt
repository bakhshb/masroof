package com.baraa.masroof.application.interpretation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.TestStorageContext
import com.baraa.masroof.testsupport.ingest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MultiBankLearningTest {
    @Test fun aManualDecisionInAnotherBundledBankTeachesItsPendingAndNextMessages() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        // Scheduled refreshes are run below with this test's container, never by the app's own one.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    object : Worker(appContext, workerParameters) { override fun doWork() = Result.success() }
            }).build())
        val context = TestStorageContext(app, "multi-bank-learning")
        val c = AppContainer(context)
        try {
            val service = requireNotNull(c.learning.interpretationService)
            assertTrue("BANK_SNB" in service.assistedBanks())
            assertTrue("BANK_STC" in service.assistedBanks()) // Every bank remembers decisions.
            val snb = Bank("BANK_SNB")
            c.review.onboardingOwnershipWorkflow.confirmAccountOwned(AccountReference(snb, "3001"))
            val at = LocalDateTime.now().minusMinutes(2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            fun body(amount: Int, remaining: Int) =
                "سداد تمويل خاص\nمن: 3001\nالقسط: SAR $amount\nالمبلغ المتبقي: SAR $remaining\nلـ: تمويل شخصي\nفي: $at"
            fun raw(id: String, text: String) = RawSms(id, "SNB-AlAhli", text, Instant.now(), id, SmsBodyHasher.sha256Hex(text))
            service.synchronizeFeedback()
            c.sms.processRawSmsUseCase.ingest(raw("snb-loan-one", body(20, 300)))
            c.sms.processRawSmsUseCase.ingest(raw("snb-loan-pending", body(21, 299)))
            assertEquals(2, c.storage.reviewRepository.listRequired().size)

            val review = requireNotNull(c.storage.reviewRepository.findByRawSmsId("snb-loan-one"))
            c.review.reviewWorkflowService.resolveAsFinancialType(review.id, FinancialTransactionType.LOAN_REPAYMENT)
            service.synchronizeFeedback()
            assertEquals(LearningFeedbackState.LEARNED, service.feedbackStatus("snb-loan-one")!!.state)
            assertTrue(service.needsRefresh(requireNotNull(c.storage.parsedEventRepository.findByRawSmsId("snb-loan-pending"))))
            ModelRefreshWorkflow(c.storage.reviewRepository, c.storage.parsedEventRepository, c.storage.rawSmsRepository, c.storage.processingRetryRepository,
                c.sms.processStoredSmsUseCase, service::mayRefresh, service::needsRefresh, { c.storage.withDatabaseTransaction(it) },
                { c.sms.historicalDerivedRecovery.recoverPending() }, c.storage.clock).refresh()
            val pending = requireNotNull(c.storage.parsedEventRepository.findByRawSmsId("snb-loan-pending"))
            assertTrue(pending.event.confidence.reasons.toString(), "family_origin:HUMAN_TEMPLATE" in pending.event.confidence.reasons)
            assertEquals(2, c.storage.financialTransactionRepository.listAll().size)
            // Once refreshed with the current model, nothing is scheduled again (no refresh loop).
            service.synchronizeFeedback()
            assertTrue(c.storage.reviewRepository.listRequired().none { review ->
                c.storage.parsedEventRepository.findByRawSmsId(review.rawSmsId)?.let(service::needsRefresh) == true })

            c.sms.processRawSmsUseCase.ingest(raw("snb-loan-next", body(22, 298)))
            val next = requireNotNull(c.storage.parsedEventRepository.findByRawSmsId("snb-loan-next"))
            assertEquals("SUCCESS", next.event.parseStatus.name)
            assertTrue("family_origin:HUMAN_TEMPLATE" in next.event.confidence.reasons)
            assertEquals(3, c.storage.financialTransactionRepository.listAll().size)
            assertTrue(c.storage.reviewRepository.listRequired().isEmpty())

            // The seed bank's UNKNOWN message is read once per model, not on every sync (no refresh loop).
            c.sms.processRawSmsUseCase.ingest(raw("seed-unknown", "عزيزنا العميل رسالة غير مفهومة 123").copy(sender = "AlJazira"))
            val unknown = requireNotNull(c.storage.parsedEventRepository.findByRawSmsId("seed-unknown"))
            assertEquals("UNKNOWN", unknown.event.messageFamily.name)
            assertTrue(c.storage.reviewRepository.findByRawSmsId("seed-unknown") != null)
            var settled = false
            repeat(50) { if (!settled) {
                service.synchronizeFeedback(); settled = !service.needsRefresh(unknown); if (!settled) kotlinx.coroutines.delay(100) } }
            assertTrue(settled)

            // Every bank with role rules re-reads its waiting messages after a rules change, not only AlJazira.
            c.sms.processRawSmsUseCase.ingest(raw("snb-unknown", "عزيزنا العميل رسالة غير مفهومة 456"))
            val snbUnknown = requireNotNull(c.storage.parsedEventRepository.findByRawSmsId("snb-unknown"))
            assertEquals("UNKNOWN", snbUnknown.event.messageFamily.name)
            val olderRules = snbUnknown.copy(event = snbUnknown.event.copy(confidence = snbUnknown.event.confidence.copy(
                reasons = snbUnknown.event.confidence.reasons.map { if (it.startsWith("rules:")) "rules:older" else it })))
            assertTrue(service.needsRefresh(olderRules))

            // A bank the user adds gets the same learning as a bundled one.
            val generation = c.patterns.messagePatternsWorkflow.load().generation
            c.patterns.messagePatternsWorkflow.addBank(generation, "My bank", "MYBANK")
            val added = c.patterns.messagePatternsWorkflow.load().banks.single { it.displayName == "My bank" }.bankId
            assertTrue(added in service.assistedBanks())
        } finally {
            c.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    /** "Not financial" once: the waiting copy and the next copy of the same wording leave review, in a bank without role rules. */
    @Test fun aNotFinancialDecisionClearsTheSameWordingInAnyBank() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    object : Worker(appContext, workerParameters) { override fun doWork() = Result.success() }
            }).build())
        val c = AppContainer(TestStorageContext(app, "remember-not-financial"))
        try {
            val service = requireNotNull(c.learning.interpretationService)
            fun raw(id: String) = "عرض حصري لعملائنا الكرام\nاستمتع بمزايا البطاقة الجديدة".let { text ->
                RawSms(id, "STC Bank", "$text $id", Instant.now(), id, SmsBodyHasher.sha256Hex("$text $id")) }
            service.synchronizeFeedback()
            c.sms.processRawSmsUseCase.ingest(raw("promo-one"))
            c.sms.processRawSmsUseCase.ingest(raw("promo-waiting"))
            assertEquals(2, c.storage.reviewRepository.listRequired().size)

            c.review.reviewWorkflowService.resolveAsNonFinancial(requireNotNull(c.storage.reviewRepository.findByRawSmsId("promo-one")).id)
            service.synchronizeFeedback()
            assertEquals(LearningFeedbackState.LEARNED, service.feedbackStatus("promo-one")!!.state)
            assertTrue(service.needsRefresh(requireNotNull(c.storage.parsedEventRepository.findByRawSmsId("promo-waiting"))))
            ModelRefreshWorkflow(c.storage.reviewRepository, c.storage.parsedEventRepository, c.storage.rawSmsRepository, c.storage.processingRetryRepository,
                c.sms.processStoredSmsUseCase, service::mayRefresh, service::needsRefresh, { c.storage.withDatabaseTransaction(it) },
                { c.sms.historicalDerivedRecovery.recoverPending() }, c.storage.clock).refresh()
            assertEquals("NON_FINANCIAL", c.storage.parsedEventRepository.findByRawSmsId("promo-waiting")!!.event.messageFamily.name)
            assertTrue(c.storage.reviewRepository.listRequired().isEmpty())

            c.sms.processRawSmsUseCase.ingest(raw("promo-next"))
            val next = requireNotNull(c.storage.parsedEventRepository.findByRawSmsId("promo-next"))
            assertEquals("NON_FINANCIAL", next.event.messageFamily.name)
            assertTrue("family_origin:HUMAN_TEMPLATE" in next.event.confidence.reasons)
            assertTrue(c.storage.reviewRepository.listRequired().isEmpty())
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())

            // The user later says this wording is a purchase: both hidden copies go back to review, the
            // automatically settled one included, and they are read once, not on every refresh.
            c.storage.userCorrectionRepository.save(com.baraa.masroof.domain.model.UserCorrection("promo-was-purchase", "promo-one",
                com.baraa.masroof.domain.model.MessageFamily.PURCHASE, null, null, null, Instant.now()))
            service.synchronizeFeedback()
            assertEquals(setOf("promo-waiting", "promo-next"), service.revokedMemories())
            suspend fun refresh() = ModelRefreshWorkflow(c.storage.reviewRepository, c.storage.parsedEventRepository, c.storage.rawSmsRepository,
                c.storage.processingRetryRepository, c.sms.processStoredSmsUseCase, service::mayRefresh, service.refreshFilter(readHistory = false),
                { c.storage.withDatabaseTransaction(it) }, { c.sms.historicalDerivedRecovery.recoverPending() }, c.storage.clock,
                extra = { service.revokedMemories() }).refresh()
            refresh()
            listOf("promo-waiting", "promo-next").forEach { id ->
                assertEquals(id, "UNKNOWN", c.storage.parsedEventRepository.findByRawSmsId(id)!!.event.messageFamily.name)
                assertFalse(id, service.needsRefresh(c.storage.parsedEventRepository.findByRawSmsId(id)!!))
            }
            assertEquals(setOf("promo-waiting", "promo-next"), c.storage.reviewRepository.listRequired().map { it.rawSmsId }.toSet())
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
        } finally {
            c.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
}
