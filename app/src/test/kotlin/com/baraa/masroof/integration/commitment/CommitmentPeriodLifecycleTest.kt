package com.baraa.masroof.integration.commitment

import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.commitment.CommitmentCreationResult
import com.baraa.masroof.application.dashboard.CommitmentPaymentStatus
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow.CommitmentEditorDraft
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.uniqueStorageContext
import com.baraa.masroof.testsupport.ingest
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Posted evidence -> user commitment/edit -> next-period payment -> durable cold reopen. */
@RunWith(RobolectricTestRunner::class)
class CommitmentPeriodLifecycleTest {
    @Test fun higherPaymentAfterRename_survivesReopenWithoutPayingAnotherPeriod() = runBlocking {
        val context = uniqueStorageContext("commitment-lifecycle")
        var c = AppContainer(context)
        try {
            c.review.onboardingOwnershipWorkflow.confirmCardOwned(CardReference(Bank.BANK_ALJAZIRA, "7271"))
            suspend fun ingest(id: String, day: String, amount: String) {
                val body = "شراء عبر الانترنت\nبطاقة ائتمانية: 7271\nمبلغ: SAR $amount\nلدى: SYNTHETIC_PROVIDER\nفي: $day 12:00"
                c.sms.processRawSmsUseCase.ingest(RawSms(id, "AlJazira", body, Instant.parse("${day}T09:01:00Z"), id, SmsBodyHasher.sha256Hex(body)))
            }
            ingest("commitment-old", "2026-07-28", "100")
            val source = c.storage.financialTransactionRepository.listAll().single()
            assertEquals(CommitmentCreationResult.Success, c.dashboard.dashboardCommitmentsWorkflow.createFromTransaction(source.id))
            val commitment = c.dashboard.settingsCommitmentsWorkflow.listAll().single()
            c.dashboard.settingsCommitmentsWorkflow.update(commitment.id, CommitmentEditorDraft("فاتورة الهاتف", commitment.amount,
                commitment.transactionDate, commitment.recurrence, commitment.dueDate))
            ingest("commitment-new", "2026-08-28", "130")
            val ledger = c.storage.financialTransactionRepository.listAll().sortedBy { it.id }
            val payment = ledger.single { it.id != source.id }
            suspend fun checkPeriods(container: AppContainer) {
                suspend fun row(start: String, end: String) = container.dashboard.dashboardService.loadOverview(
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
                assertEquals(ledger, container.storage.financialTransactionRepository.listAll().sortedBy { it.id })
            }
            checkPeriods(c)
            c.close()
            c = AppContainer(context)
            checkPeriods(c)
        } finally {
            c.close()
            context.getDatabasePath("masroof.db").parentFile?.deleteRecursively()
        }
    }
}
