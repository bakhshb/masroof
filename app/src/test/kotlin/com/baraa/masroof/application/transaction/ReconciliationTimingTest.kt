package com.baraa.masroof.application.transaction

import com.baraa.masroof.domain.matching.TransactionMatcher
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReconciliationTimingTest : ReconciliationTestFixture() {
    /** Every bank, a bank with no pattern file included, reads its wall clock in Riyadh whatever the device zone. */
    @Test
    fun reprocessInAnotherDeviceZone_keepsEveryBanksRiyadhTime() = runBlocking {
        val local = LocalDateTime.of(2026, 8, 26, 22, 30)
        val riyadhInstant = local.atZone(java.time.ZoneId.of("Asia/Riyadh")).toInstant()
        for (bank in listOf(Bank.BANK_ALJAZIRA, Bank("OTHER"))) {
            confirmation.confirmCardOwned(CardReference(bank, "7271"))
            persistEvent(
                smsId = "sms-${bank.id}",
                event = event(
                    id = "pe-${bank.id}",
                    rawSmsId = "sms-${bank.id}",
                    family = MessageFamily.PURCHASE,
                    amount = money("12.00"),
                    bank = bank,
                    card = CardReference(bank, "7271"),
                    channel = PurchaseChannel.ONLINE,
                    merchant = "Shop",
                ),
                details = ParsedEventDetails(occurredAtLocal = local),
            )
        }
        for (deviceZone in listOf("Asia/Tokyo", "America/New_York")) {
            inDeviceZone(deviceZone) { service().reconcileStoredEvents() }
            for (bank in listOf(Bank.BANK_ALJAZIRA, Bank("OTHER"))) {
                val row = ftRepo.findByRawSmsId("sms-${bank.id}")!!
                assertEquals(bank.id, riyadhInstant, row.occurredAt)
                assertEquals(bank.id, "Asia/Riyadh", row.occurredAtZone)
            }
        }
    }

    @Test
    fun fullReconcile_healsStalePairInRiyadhWhenDeviceDefaultDiffers() = runBlocking {
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        try {
            val other = Bank("OTHER")
            confirmation.confirmAccountOwned(AccountReference(other, "3001"))
            val local = java.time.LocalDateTime.parse("2026-08-01T12:00:00")
            val riyadh = java.time.ZoneId.of("Asia/Riyadh")
            val outAt = Instant.parse("2026-08-01T12:00:00Z")
            val inAt = outAt.plus(TransactionMatcher.TRANSFER_MATCH_WINDOW.multipliedBy(3))
            persistEvent(
                smsId = "sms-out-zone-heal",
                at = outAt,
                details = ParsedEventDetails(occurredAtLocal = local),
                event = event(
                    id = "pe-out-zone-heal",
                    rawSmsId = "sms-out-zone-heal",
                    family = MessageFamily.TRANSFER_OUT,
                    amount = money("90.00"),
                    bank = other,
                    source = AccountReference(other, "3001"),
                    destination = AccountReference(other, "3003"),
                    network = BankNetworkType.INTRA_BANK,
                ),
            )
            service().reconcileAffectedRawSmsIds(listOf("sms-out-zone-heal"))
            assertEquals("Asia/Riyadh", ftRepo.listAll().single().occurredAtZone)

            confirmation.confirmAccountOwned(AccountReference(other, "3003"))
            persistEvent(
                smsId = "sms-in-zone-heal",
                at = inAt,
                details = ParsedEventDetails(occurredAtLocal = local),
                event = event(
                    id = "pe-in-zone-heal",
                    rawSmsId = "sms-in-zone-heal",
                    family = MessageFamily.TRANSFER_IN,
                    amount = money("90.00"),
                    bank = other,
                    source = AccountReference(other, "3001"),
                    destination = AccountReference(other, "3003"),
                    network = BankNetworkType.INTRA_BANK,
                ),
            )
            // Full maintenance revisits posted legs outside the new message's device-zone window.
            service().reconcileStoredEventsDetailed()

            val healed = ftRepo.listAll().single()
            assertEquals(FinancialTransactionType.SELF_TRANSFER, healed.type)
            assertEquals(local.atZone(riyadh).toInstant(), healed.occurredAt)
            assertEquals("Asia/Riyadh", healed.occurredAtZone)
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun stalePairHeal_readsRiyadhWhenCounterpartHasNoLocalTime() = runBlocking {
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        try {
            val other = Bank("OTHER")
            confirmation.confirmAccountOwned(AccountReference(other, "3001"))
            val local = java.time.LocalDateTime.parse("2026-08-01T12:00:00")
            val riyadh = java.time.ZoneId.of("Asia/Riyadh")
            val riyadhInstant = local.atZone(riyadh).toInstant()
            val counterpartReceivedAt = riyadhInstant.plusSeconds(5 * 60)
            persistEvent(
                smsId = "sms-out-mixed-zone",
                at = Instant.parse("2026-08-01T12:00:00Z"),
                details = ParsedEventDetails(occurredAtLocal = local),
                event = event(
                    id = "pe-out-mixed-zone",
                    rawSmsId = "sms-out-mixed-zone",
                    family = MessageFamily.TRANSFER_OUT,
                    amount = money("90.00"),
                    bank = other,
                    source = AccountReference(other, "3001"),
                    destination = AccountReference(other, "3003"),
                    network = BankNetworkType.INTRA_BANK,
                ),
            )
            service().reconcileAffectedRawSmsIds(listOf("sms-out-mixed-zone"))
            val posted = ftRepo.listAll().single()
            assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, posted.type)
            assertEquals("Asia/Riyadh", posted.occurredAtZone)
            assertEquals(riyadhInstant, posted.occurredAt)

            confirmation.confirmAccountOwned(AccountReference(other, "3003"))
            persistEvent(
                smsId = "sms-in-mixed-zone",
                at = counterpartReceivedAt,
                details = ParsedEventDetails(),
                event = event(
                    id = "pe-in-mixed-zone",
                    rawSmsId = "sms-in-mixed-zone",
                    family = MessageFamily.TRANSFER_IN,
                    amount = money("90.00"),
                    bank = other,
                    source = AccountReference(other, "3001"),
                    destination = AccountReference(other, "3003"),
                    network = BankNetworkType.INTRA_BANK,
                ),
            )
            service().reconcileAffectedRawSmsIds(listOf("sms-in-mixed-zone"))

            val healed = ftRepo.listAll().single()
            assertEquals(FinancialTransactionType.SELF_TRANSFER, healed.type)
            assertEquals(riyadhInstant, healed.occurredAt)
            assertEquals("Asia/Riyadh", healed.occurredAtZone)
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun postedSingleLegSelfTransfer_holdsMixedClockCounterpart() = runBlocking {
        val other = Bank("OTHER")
        confirmation.confirmAccountOwned(AccountReference(other, "3001"))
        confirmation.confirmAccountOwned(AccountReference(other, "3003"))
        val local = LocalDateTime.parse("2026-08-01T12:00:00")
        val riyadh = java.time.ZoneId.of("Asia/Riyadh")
        val riyadhInstant = local.atZone(riyadh).toInstant()
        persistEvent(
            smsId = "sms-out-posted-leg",
            at = riyadhInstant,
            details = ParsedEventDetails(occurredAtLocal = local),
            event = event(
                id = "pe-out-posted-leg",
                rawSmsId = "sms-out-posted-leg",
                family = MessageFamily.TRANSFER_OUT,
                amount = money("70.00"),
                bank = other,
                source = AccountReference(other, "3001"),
                destination = AccountReference(other, "3003"),
                network = BankNetworkType.INTRA_BANK,
            ),
        )
        service().reconcileStoredEventsDetailed()
        val posted = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, posted.type)
        assertEquals(1, posted.linkedParsedEventIds.size)
        assertEquals("Asia/Riyadh", posted.occurredAtZone)

        // Counterpart has only a receipt time, so the matcher compares effective instants.
        persistEvent(
            smsId = "sms-in-posted-leg",
            at = riyadhInstant.plusSeconds(5 * 60),
            event = event(
                id = "pe-in-posted-leg",
                rawSmsId = "sms-in-posted-leg",
                family = MessageFamily.TRANSFER_IN,
                amount = money("70.00"),
                bank = other,
                source = AccountReference(other, "3001"),
                destination = AccountReference(other, "3003"),
                network = BankNetworkType.INTRA_BANK,
            ),
        )
        val report = service().reconcileStoredEventsDetailed()

        // The counterpart joins the posted leg in this pass, so no pending candidate is left.
        assertEquals(0, report.summary.pendingMatch)
        val healed = ftRepo.listAll().single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, healed.type)
        assertEquals(setOf("pe-out-posted-leg", "pe-in-posted-leg"), healed.linkedParsedEventIds.toSet())
    }

    private inline fun inDeviceZone(zone: String, block: () -> Unit) {
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
        try { block() } finally { java.util.TimeZone.setDefault(previous) }
    }
}
