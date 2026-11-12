package com.baraa.masroof.instrumentation

import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.testsupport.ingest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId

internal object JourneyFixtures {
    const val DASHBOARD_SMS: String = "sms-dashboard-smoke"
    const val DASHBOARD_MERCHANT: String = "Dashboard Mart"
    const val CORRECTION_SMS: String = "sms-correction-smoke"
    const val CORRECTION_BODY: String = "شراء عبر الانترنت\nمبلغ: SAR 18.00\nلدى: Correction Mart"
    const val CORRECTION_MERCHANT: String = "Correction Mart"
    const val RESTORE_SMS: String = "sms-restore-smoke"
    const val RESTORE_BODY: String = "SMOKE-RESTORE"
    const val RESTORE_MERCHANT: String = "Restore Mart"
}

/**
 * Inserts durable evidence before the activity reads it. Times are "now" so the
 * current salary period includes the rows the dashboard reconstructs.
 *
 * Seeded journeys wait until production startup maintenance reports READY, so a
 * clean Orchestrator database cannot reparse over rows that were just inserted.
 * The startup journey does not wait here and still exercises the real gate.
 */
internal object JourneySeeder {
    fun prepare(methodName: String) {
        runBlocking(Dispatchers.IO) {
            val container = application().container
            container.storage.onboardingPreferencesRepository.setOnboardingStarted(true)
            container.storage.onboardingPreferencesRepository.setOnboardingCompleted(true)
            when {
                methodName.matchesJourney("startup_reachesFinancialUiWhenMaintenanceIsReady") -> Unit
                methodName.matchesJourney("dashboard_showsPersistedTransactionAfterReload") -> {
                    awaitReadyForFixtures(container)
                    container.storage.withDatabaseTransaction { seedDashboardTransaction() }
                }
                methodName.matchesJourney("reviewCorrection_persistsChosenTypeAndReloads") -> {
                    awaitReadyForFixtures(container)
                    container.storage.withDatabaseTransaction { seedCorrectionReview() }
                }
                methodName.matchesJourney("reviewRestore_persistsRestoredPurchaseAndReloads") -> {
                    awaitReadyForFixtures(container)
                    container.storage.withDatabaseTransaction { seedIgnoredPurchase() }
                }
                else -> error("No device seed for $methodName")
            }
        }
    }

    private suspend fun awaitReadyForFixtures(container: AppContainer) {
        val outcome = container.maintenance.awaitStartupMaintenance()
        check(outcome == StartupMaintenanceOutcome.READY) {
            "Device fixtures require startup maintenance READY, was $outcome"
        }
    }

    private suspend fun seedDashboardTransaction() {
        val container = application().container
        val now = Instant.now()
        val rawSmsId = JourneyFixtures.DASHBOARD_SMS
        val eventId = "pe-dashboard-smoke"
        insertEvidence(
            rawSmsId = rawSmsId,
            body = "dashboard smoke evidence",
            merchant = JourneyFixtures.DASHBOARD_MERCHANT,
            eventId = eventId,
            amount = "42.50",
            parseStatus = ParseStatus.SUCCESS,
            at = now,
        )
        val saved = container.storage.financialTransactionRepository.save(
            transaction = FinancialTransaction(
                id = TransactionIdFactory.fromRawSmsIds(listOf(rawSmsId)),
                type = FinancialTransactionType.EXPENSE,
                amount = Money.of("42.50", Currency.SAR),
                occurredAt = now,
                sourceContainerId = null,
                destinationContainerId = null,
                merchant = JourneyFixtures.DASHBOARD_MERCHANT,
                counterparty = null,
                categoryId = null,
                linkedParsedEventIds = listOf(eventId),
                occurredAtZone = ZoneId.systemDefault().id,
            ),
            rawSmsIds = listOf(rawSmsId),
        )
        check(saved is FinancialTransactionSaveResult.Saved) { "Dashboard smoke row was not saved: $saved" }
    }

    private suspend fun seedCorrectionReview() {
        val container = application().container
        // Use durable evidence and the real engine. A rule refresh must read the same
        // amount/merchant and event identity that the manual-resolution UI reads.
        container.sms.processRawSmsUseCase.ingest(RawSms(
            id = JourneyFixtures.CORRECTION_SMS,
            sender = "AlJazira",
            body = JourneyFixtures.CORRECTION_BODY,
            receivedAt = Instant.now(),
            deviceMessageId = JourneyFixtures.CORRECTION_SMS,
            bodyHash = SmsBodyHasher.sha256Hex(JourneyFixtures.CORRECTION_BODY),
        ))
        val parsed = checkNotNull(container.storage.parsedEventRepository.findByRawSmsId(JourneyFixtures.CORRECTION_SMS))
        check(parsed.event.amount == Money.of("18.00", Currency.SAR))
        check(parsed.event.merchant == JourneyFixtures.CORRECTION_MERCHANT)
        check(container.storage.reviewRepository.listRequired().any { it.rawSmsId == JourneyFixtures.CORRECTION_SMS })
        check(container.storage.financialTransactionRepository.findByRawSmsId(JourneyFixtures.CORRECTION_SMS) == null)
    }

    private suspend fun seedIgnoredPurchase() {
        insertEvidence(
            rawSmsId = JourneyFixtures.RESTORE_SMS,
            body = JourneyFixtures.RESTORE_BODY,
            merchant = JourneyFixtures.RESTORE_MERCHANT,
            eventId = "pe-restore-smoke",
            amount = "9.00",
            parseStatus = ParseStatus.SUCCESS,
            at = Instant.now(),
        )
        val container = application().container
        val review = container.storage.reviewRepository.upsertRequired(
            rawSmsId = JourneyFixtures.RESTORE_SMS,
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("purchase_without_resolved_owned_instrument"),
            now = Instant.now(),
        )
        val now = Instant.now()
        // As the app ignores it: the review resolution and its decision together.
        val decision = com.baraa.masroof.domain.model.UserDecision(
            JourneyFixtures.RESTORE_SMS, com.baraa.masroof.domain.model.UserDecisionKind.NON_FINANCIAL, now)
        val ignored = container.storage.userDecisionRepository.recordWith(decision, before = { null }) {
            container.storage.reviewRepository.markResolved(
                id = review.id,
                resolutionKind = ReviewResolutionKind.USER_NON_FINANCIAL,
                resolvedAt = now,
                resolvedTransactionId = null,
            )
        }
        check(ignored != null) { "Ignored smoke review was not stored" }
    }

    private suspend fun insertEvidence(
        rawSmsId: String,
        body: String,
        merchant: String,
        eventId: String,
        amount: String,
        parseStatus: ParseStatus,
        at: Instant,
    ) {
        val container = application().container
        container.storage.rawSmsRepository.insertIfAbsent(
            RawSms(
                id = rawSmsId,
                sender = "AlJazira",
                body = body,
                receivedAt = at,
                deviceMessageId = rawSmsId,
                bodyHash = SmsBodyHasher.sha256Hex(body),
            ),
        )
        container.storage.parsedEventRepository.save(
            ParsedEvent(
                id = eventId,
                rawSmsId = rawSmsId,
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.PURCHASE,
                direction = MoneyDirection.OUTGOING,
                amount = Money.of(amount, Currency.SAR),
                purchaseChannel = null,
                sourceAccountRef = null,
                destinationAccountRef = null,
                cardRef = null,
                merchant = merchant,
                counterparty = null,
                occurredAt = at,
                bankNetworkType = null,
                confidence = Confidence(1.0),
                parseStatus = parseStatus,
            ),
        )
    }

    private fun String.matchesJourney(expected: String): Boolean =
        this == expected || startsWith("$expected[") || startsWith("$expected(")

    private fun application(): MasroofApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MasroofApplication
}
