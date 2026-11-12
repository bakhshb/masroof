package com.baraa.masroof.application.interpretation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.TestStorageContext
import com.baraa.masroof.testsupport.ingest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProductionEnginePreservationTest {
    @Test fun modelRefreshPreservesPostedEvidenceAndEvenMerchantOnlyDecisions() = runBlocking {
        val context = TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "engine-preservation")
        val c = AppContainer(context)
        try {
            c.review.onboardingOwnershipWorkflow.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
            fun raw(id: String, body: String) = RawSms(id, "AlJazira", body, Instant.now(), id, SmsBodyHasher.sha256Hex(body))
            c.sms.processRawSmsUseCase.ingest(raw("posted", "شراء عبر الانترنت\nبطاقة ائتمانية: 7271\nمبلغ: SAR 12\nلدى: TEST_SHOP"))
            val service = requireNotNull(c.learning.interpretationService)
            assertNotNull(c.storage.financialTransactionRepository.findByRawSmsId("posted"))
            assertFalse(service.mayRefresh("posted"))
            val ledger = c.storage.financialTransactionRepository.listAll()
            val posted = c.storage.parsedEventRepository.findByRawSmsId("posted")

            c.sms.processRawSmsUseCase.ingest(raw("undecided", "تنبيه جديد غير معروف\nمبلغ: SAR 5"))
            assertTrue(service.mayRefresh("undecided"))
            c.storage.userCorrectionRepository.save(UserCorrection("merchant-only", "undecided", null, null,
                "My merchant", null, Instant.now()))
            assertFalse(service.mayRefresh("undecided"))
            service.synchronizeFeedback()
            assertEquals(ledger, c.storage.financialTransactionRepository.listAll())
            assertEquals(posted, c.storage.parsedEventRepository.findByRawSmsId("posted"))
            assertEquals("masroof.db", context.getDatabasePath("masroof.db").name)
        } finally { c.close() }
    }
}
