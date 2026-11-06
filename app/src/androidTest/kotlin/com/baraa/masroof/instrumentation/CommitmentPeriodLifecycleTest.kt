package com.baraa.masroof.instrumentation

import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.commitment.CommitmentCreationResult
import com.baraa.masroof.application.dashboard.CommitmentPaymentStatus
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow.CommitmentEditorDraft
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.TestRuntime
import com.baraa.masroof.testsupport.ingest
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Posted evidence -> user commitment/edit -> next-period payment -> durable cold reopen. */
class CommitmentPeriodLifecycleTest {
    @Test fun higherPaymentAfterRename_survivesReopenWithoutPayingAnotherPeriod() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        TestRuntime.reset(host)
        var c = TestRuntime.container(host)
        try {
            c.awaitPostStartupBackgroundWorkForTesting()
            c.onboardingOwnershipWorkflow.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
            suspend fun ingest(id: String, day: String, amount: String) {
                val body = "شراء عبر الانترنت\nبطاقة ائتمانية: 7271\nمبلغ: SAR $amount\nلدى: SYNTHETIC_PROVIDER\nفي: $day 12:00"
                c.processRawSmsUseCase.ingest(RawSms(id, "AlJazira", body, Instant.parse("${day}T09:01:00Z"), id, SmsBodyHasher.sha256Hex(body)))
            }
            ingest("commitment-old", "2026-07-28", "100")
            val source = c.financialTransactionRepository.listAll().single()
            assertEquals(CommitmentCreationResult.Success, c.dashboardCommitmentsWorkflow.createFromTransaction(source.id))
            val commitment = c.settingsCommitmentsWorkflow.listAll().single()
            c.settingsCommitmentsWorkflow.update(commitment.id, CommitmentEditorDraft("فاتورة الهاتف", commitment.amount,
                commitment.transactionDate, commitment.recurrence, commitment.dueDate))
            ingest("commitment-new", "2026-08-28", "130")
            val ledger = c.financialTransactionRepository.listAll().sortedBy { it.id }
            val payment = ledger.single { it.id != source.id }
            suspend fun checkPeriods(container: AppContainer) {
                suspend fun row(start: String, end: String) = container.dashboardService.loadOverview(
                    FinancialPeriod(LocalDate.parse(start), LocalDate.parse(end))).commitmentsOverview.rows
                    .single { it.userCommitmentId == commitment.id }
                val old = row("2026-07-27", "2026-08-27")
                val current = row("2026-08-27", "2026-09-27")
                val future = row("2026-09-27", "2026-10-27")
                assertEquals(source.id, old.paymentTransactionId)
                assertEquals(Money.of("100", Currency.SAR), old.amount)
                assertEquals(CommitmentPaymentStatus.PAID, current.status)
                assertEquals(payment.id, current.paymentTransactionId)
                assertEquals(Money.of("130", Currency.SAR), current.amount)
                assertEquals(CommitmentPaymentStatus.UNPAID, future.status)
                assertNull(future.paymentTransactionId)
                assertEquals(Money.of("100", Currency.SAR), future.amount)
                assertEquals(ledger, container.financialTransactionRepository.listAll().sortedBy { it.id })
            }
            checkPeriods(c)
            TestRuntime.close()
            c = TestRuntime.container(host)
            c.awaitPostStartupBackgroundWorkForTesting()
            checkPeriods(c)
        } finally { TestRuntime.close() }
    }
}
