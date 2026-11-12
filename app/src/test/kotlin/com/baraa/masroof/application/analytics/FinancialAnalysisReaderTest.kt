package com.baraa.masroof.application.analytics

import com.baraa.masroof.testsupport.backfillDecisions
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.application.sms.LiveSmsProcessingHarness
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FinancialAnalysisReaderTest {
    @Test
    fun retryRowOfANonFinancialResolution_doesNotBlockAnalysis() = runBlocking {
        val container = (ApplicationProvider.getApplicationContext<Context>() as MasroofApplication).container
        try {
            val raw = LiveSmsProcessingHarness.liveSms()
            container.storage.rawSmsRepository.insertIfAbsent(raw)
            val now = Instant.parse("2026-08-11T12:00:00Z")
            container.storage.processingRetryRepository.markRequired(raw.id, now, ProcessingRetryMode.LIVE)
            val start = Instant.parse("2026-08-01T00:00:00Z")
            val end = Instant.parse("2026-09-01T00:00:00Z")
            assertTrue(FinancialAnalysisReader.capture(container, start, end, ZoneOffset.UTC).retry)

            val review = container.storage.reviewRepository.upsertRequired(raw.id, ReviewKind.NEEDS_REVIEW, listOf("processing_error"), now)
            container.storage.reviewRepository.markResolved(review.id, ReviewResolutionKind.USER_NON_FINANCIAL, now, null)
            container.storage.database.backfillDecisions()

            assertFalse(FinancialAnalysisReader.capture(container, start, end, ZoneOffset.UTC).retry)
        } finally {
            container.close()
        }
    }
}
