package com.baraa.masroof.application.onboarding

import com.baraa.masroof.application.ingestion.DerivedProcessingStage
import com.baraa.masroof.application.sms.SmsScanFailure
import com.baraa.masroof.application.sms.SmsScanResult
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoricalImportOutcomeTest {
    @Test
    fun someParsedRows_doNotHideFailedRows() {
        assertEquals(HistoricalImportUserOutcome.FAILED, HistoricalImportResult(scanned = 2, parsed = 1, failed = 1).userOutcome())
    }

    @Test
    fun incompleteDerivedPass_survivesScannerGatewayAndUserOutcome() {
        val result = SmsScanResult(
            scanned = 1,
            parsed = 1,
            failure = SmsScanFailure.DerivedIncomplete(DerivedProcessingStage.RECONCILIATION),
        ).toHistoricalImportResult()
        assertEquals(HistoricalImportFailure.ProcessingIncomplete("reconciliation"), result.failure)
        assertEquals(HistoricalImportUserOutcome.FAILED, result.userOutcome())
    }

    @Test
    fun completedScan_retainsItsExistingSuccessOutcome() {
        assertEquals(HistoricalImportUserOutcome.OK, HistoricalImportResult(scanned = 1, parsed = 1).userOutcome())
    }
}
