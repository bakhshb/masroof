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
            val service = requireNotNull(c.interpretationService)
            assertTrue("BANK_SNB" in service.assistedBanks())
            assertFalse("BANK_STC" in service.assistedBanks())
            val snb = Bank("BANK_SNB")
            c.onboardingOwnershipWorkflow.confirmAccountOwned(AccountReference(snb, "3001"))
            val at = LocalDateTime.now().minusMinutes(2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            fun body(amount: Int, remaining: Int) =
                "سداد تمويل خاص\nمن: 3001\nالقسط: SAR $amount\nالمبلغ المتبقي: SAR $remaining\nلـ: تمويل شخصي\nفي: $at"
            fun raw(id: String, text: String) = RawSms(id, "SNB-AlAhli", text, Instant.now(), id, SmsBodyHasher.sha256Hex(text))
            service.synchronizeFeedback()
            c.processRawSmsUseCase.ingest(raw("snb-loan-one", body(20, 300)))
            c.processRawSmsUseCase.ingest(raw("snb-loan-pending", body(21, 299)))
            assertEquals(2, c.reviewRepository.listRequired().size)

            val review = requireNotNull(c.reviewRepository.findByRawSmsId("snb-loan-one"))
            c.reviewWorkflowService.resolveAsFinancialType(review.id, FinancialTransactionType.LOAN_REPAYMENT)
            service.synchronizeFeedback()
            assertEquals(LearningFeedbackState.LEARNED, service.feedbackStatus("snb-loan-one")!!.state)
            assertTrue(service.needsRefresh(requireNotNull(c.parsedEventRepository.findByRawSmsId("snb-loan-pending"))))
            ModelRefreshWorkflow(c.reviewRepository, c.parsedEventRepository, c.rawSmsRepository, c.processingRetryRepository,
                c.processStoredSmsUseCase, service::mayRefresh, service::needsRefresh, { c.withDatabaseTransaction(it) },
                { c.historicalDerivedRecovery.recoverPending() }, c.clock).refresh()
            val pending = requireNotNull(c.parsedEventRepository.findByRawSmsId("snb-loan-pending"))
            assertTrue(pending.event.confidence.reasons.toString(), "family_origin:HUMAN_TEMPLATE" in pending.event.confidence.reasons)
            assertEquals(2, c.financialTransactionRepository.listAll().size)
            // Once refreshed with the current model, nothing is scheduled again (no refresh loop).
            service.synchronizeFeedback()
            assertTrue(c.reviewRepository.listRequired().none { review ->
                c.parsedEventRepository.findByRawSmsId(review.rawSmsId)?.let(service::needsRefresh) == true })

            c.processRawSmsUseCase.ingest(raw("snb-loan-next", body(22, 298)))
            val next = requireNotNull(c.parsedEventRepository.findByRawSmsId("snb-loan-next"))
            assertEquals("SUCCESS", next.event.parseStatus.name)
            assertTrue("family_origin:HUMAN_TEMPLATE" in next.event.confidence.reasons)
            assertEquals(3, c.financialTransactionRepository.listAll().size)
            assertTrue(c.reviewRepository.listRequired().isEmpty())

            // The seed bank's UNKNOWN message is read once per model, not on every sync (no refresh loop).
            c.processRawSmsUseCase.ingest(raw("seed-unknown", "عزيزنا العميل رسالة غير مفهومة 123").copy(sender = "AlJazira"))
            val unknown = requireNotNull(c.parsedEventRepository.findByRawSmsId("seed-unknown"))
            assertEquals("UNKNOWN", unknown.event.messageFamily.name)
            assertTrue(c.reviewRepository.findByRawSmsId("seed-unknown") != null)
            var settled = false
            repeat(50) { if (!settled) {
                service.synchronizeFeedback(); settled = !service.needsRefresh(unknown); if (!settled) kotlinx.coroutines.delay(100) } }
            assertTrue(settled)
        } finally {
            c.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
}
