package com.baraa.masroof.instrumentation.processdeath

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.application.interpretation.LearningFeedbackState
import com.baraa.masroof.application.review.ReviewWorkflowResult
import com.baraa.masroof.application.sms.DebugProcessHalt
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.ingest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** scripts/m13-process-death.sh force-stops the process between these two methods. */
@RunWith(AndroidJUnit4::class)
class LearningProcessDeathJourneyTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MasroofApplication
    private fun body(amount: Int)="سداد تمويل خاص\nمن: 3001\nالقسط: SAR $amount\nالمبلغ المتبقي: SAR 300\nلـ: تمويل شخصي\nفي: ${LocalDateTime.now().minusMinutes(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}"
    private fun sms(id: String, amount: Int): RawSms {
        val body=body(amount)
        return RawSms(id,"AlJazira",body,Instant.now(),id,SmsBodyHasher.sha256Hex(body))
    }
    @Test(timeout=120_000) fun armAfterFinancialCorrectionBeforeLearning() = runBlocking {
        assertFalse(Build.FINGERPRINT.equals("robolectric",ignoreCase=true))
        val c=app.container
        c.maintenance.awaitPostStartupBackgroundWorkForTesting()
        c.review.onboardingOwnershipWorkflow.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA,"3001"))
        val service=requireNotNull(c.learning.interpretationService)
        service.synchronizeFeedback()
        c.sms.processRawSmsUseCase.ingest(sms("death-teach",20))
        val review=requireNotNull(c.storage.reviewRepository.findByRawSmsId("death-teach"))
        assertTrue(c.review.reviewWorkflowService.resolveAsFinancialType(review.id,FinancialTransactionType.LOAN_REPAYMENT) is ReviewWorkflowResult.Success)
        service.synchronizeFeedback()
        c.sms.processRawSmsUseCase.ingest(sms("death-corrected",21))
        val tx=requireNotNull(c.storage.financialTransactionRepository.findByRawSmsId("death-corrected"))
        // Explicitly stop feedback before the commit. The next process must rebuild
        // suppression from financial decisions, not a completed advisory update.
        service.close()
        File(app.filesDir,DebugProcessHalt.REQUEST_FILE_NAME).writeText(DebugProcessHalt.RECLASSIFIED)
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            c.review.transactionReclassificationService.reclassify(tx.id,FinancialTransactionType.EXPENSE)
        }
        withTimeout(30_000) {
            while(File(app.filesDir,DebugProcessHalt.MARKER_FILE_NAME).takeIf { it.exists() }?.readText() != DebugProcessHalt.RECLASSIFIED) delay(50)
        }
        assertEquals(FinancialTransactionType.EXPENSE,c.storage.financialTransactionRepository.getById(tx.id)!!.type)
        assertEquals(ReviewResolutionKind.USER_FINANCIAL_TYPE,c.storage.reviewRepository.findByRawSmsId("death-corrected")!!.resolutionKind)
        DebugProcessHalt.scheduleResume(app.filesDir)
    }
    @Test(timeout=120_000) fun resumeFinancialCorrectionAndBlockNextTemplate() = runBlocking {
        val c=app.container
        c.maintenance.awaitPostStartupBackgroundWorkForTesting()
        val service=requireNotNull(c.learning.interpretationService)
        service.synchronizeFeedback()
        assertEquals(FinancialTransactionType.EXPENSE,c.storage.financialTransactionRepository.findByRawSmsId("death-corrected")!!.type)
        assertEquals(LearningFeedbackState.BLOCKED,service.feedbackStatus("death-corrected")!!.state)
        val original=c.storage.financialTransactionRepository.listAll()
        c.sms.processRawSmsUseCase.ingest(sms("death-next",22))
        assertNull(c.storage.financialTransactionRepository.findByRawSmsId("death-next"))
        assertEquals(ReviewStatus.REQUIRED,c.storage.reviewRepository.findByRawSmsId("death-next")!!.status)
        assertEquals(original,c.storage.financialTransactionRepository.listAll())
    }
}
