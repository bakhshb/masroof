package com.baraa.masroof.application.ingestion

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.ExchangeRateEnrichmentWorkflow
import com.baraa.masroof.application.transaction.ReconciliationReport
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankRoutingResult
import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import kotlinx.coroutines.CancellationException

/**
 * Processing boundary for already-captured [RawSms] evidence:
 * parse → persist ParsedEvent → ownership discovery → reconciliation → review update.
 *
 * Idempotent: re-processing a rawSmsId replaces its ParsedEvent (user corrections are
 * overlays and survive), re-runs idempotent reconciliation, and upserts review rows by
 * rawSmsId. Failures in derived steps never roll back RawSms/ParsedEvent evidence.
 * Ownership, reconciliation, and review-refresh failures are reported as
 * [SmsIngestionResult.DerivedIncomplete] so live work can retry. Exchange-rate
 * enrichment stays best-effort and does not change that outcome.
 *
 * Every recognized-bank RawSms ends in a durable outcome: a ParsedEvent or, when no
 * usable ParsedEvent exists, a direct [IngestionReviewService] review row.
 */
class ProcessStoredSmsUseCase(
    private val rawSmsRepository: RawSmsRepository,
    private val parsedEventRepository: ParsedEventRepository,
    private val bankSmsRegistry: BankSmsRegistry,
    private val ownershipDiscovery: OwnershipDiscoveryService? = null,
    private val reconciliation: TransactionReconciliationService? = null,
    private val reviewQueueUpdater: ReviewQueueUpdater? = null,
    private val ingestionReviewService: IngestionReviewService? = null,
    private val appLogService: AppLogService? = null,
    private val exchangeRateEnrichment: ExchangeRateEnrichmentWorkflow? = null,
) {
    /** Processes evidence captured in this same attempt, reusing the capture's [route]. */
    suspend fun process(
        rawSms: RawSms,
        route: BankRoutingResult,
        logOutcome: Boolean = true,
    ): SmsIngestionResult = processRouted(rawSms, route, logOutcome, deriveImmediately = true)

    /**
     * Parses captured evidence and persists its ParsedEvent (or direct review) without
     * ownership discovery, reconciliation, or review refresh. Batch callers
     * ([com.baraa.masroof.application.sms.HistoricalSmsBatchProcessor]) run those once
     * for the whole batch.
     */
    suspend fun parseAndStore(
        rawSms: RawSms,
        route: BankRoutingResult,
        logOutcome: Boolean = true,
    ): SmsIngestionResult = processRouted(rawSms, route, logOutcome, deriveImmediately = false)

    private suspend fun processRouted(
        rawSms: RawSms,
        route: BankRoutingResult,
        logOutcome: Boolean,
        deriveImmediately: Boolean,
    ): SmsIngestionResult = when (route) {
        is BankRoutingResult.Matched -> parseAndPersist(rawSms, route.adapter, logOutcome, deriveImmediately)
        is BankRoutingResult.Ambiguous -> holdAmbiguousRoute(rawSms, route, logOutcome)
        is BankRoutingResult.NotMatched -> SmsIngestionResult.NotRelevant(reason = route.reason)
    }

    /**
     * Loads stored evidence by id and processes it (e.g. from a background worker), then
     * persists pending exchange-rate enrichment. A missing row is
     * [SmsIngestionResult.Failed] with [REASON_RAW_SMS_NOT_FOUND].
     * Enrichment failure does not change the returned outcome.
     */
    suspend fun process(rawSmsId: String, logOutcome: Boolean = true): SmsIngestionResult {
        val rawSms = rawSmsRepository.getById(rawSmsId)
            ?: return SmsIngestionResult.Failed(rawSmsId = rawSmsId, message = REASON_RAW_SMS_NOT_FOUND)
        val result = processStoredEvidence(rawSms, logOutcome)
        enrichExchangeRates()
        return result
    }

    /**
     * Re-runs parse for an already-stored RawSms and immediately refreshes derived state.
     * Used by interactive/single-message retry paths.
     */
    suspend fun reparseStored(rawSms: RawSms): SmsIngestionResult =
        processStoredEvidence(rawSms, logOutcome = false, deriveImmediately = true)

    /**
     * Re-runs parse for an already-stored RawSms but only persists parse evidence.
     * Bulk maintenance uses this for every row, then runs ownership/reconciliation/review
     * once after the whole backlog has been parsed.
     */
    suspend fun reparseAndStore(rawSms: RawSms): SmsIngestionResult =
        processStoredEvidence(rawSms, logOutcome = false, deriveImmediately = false)

    /**
     * Does not re-apply ingest-time sender detection. Stored SMS is already accepted
     * evidence; detector allowlist changes must not skip backlog refresh.
     *
     * Adapter selection: existing ParsedEvent bank, else the sole registered adapter,
     * else routing only when bank identity cannot be determined.
     */
    private suspend fun processStoredEvidence(
        rawSms: RawSms,
        logOutcome: Boolean,
        deriveImmediately: Boolean = true,
    ): SmsIngestionResult {
        val storedBank = parsedEventRepository.findByRawSmsId(rawSms.id)?.event?.bank
        val adapter = storedBank?.let(bankSmsRegistry::adapterFor)
            ?: bankSmsRegistry.singleAdapterOrNull()
            ?: when (val route = bankSmsRegistry.route(rawSms.sender, rawSms.body)) {
                is BankRoutingResult.Matched -> route.adapter
                is BankRoutingResult.NotMatched ->
                    return SmsIngestionResult.NotRelevant(reason = route.reason)
                is BankRoutingResult.Ambiguous ->
                    return holdAmbiguousRoute(rawSms, route, logOutcome)
            }
        return parseAndPersist(rawSms, adapter, logOutcome, deriveImmediately = deriveImmediately)
    }

    /** Ambiguous bank-like evidence is kept and reviewed, never parsed by a guessed adapter. */
    private suspend fun holdAmbiguousRoute(
        rawSms: RawSms,
        route: BankRoutingResult.Ambiguous,
        logOutcome: Boolean,
    ): SmsIngestionResult {
        if (logOutcome) {
            appLogService?.warn(
                AppLogCategories.INGEST,
                "Ambiguous bank route for SMS from ${AppLogFormatting.maskSender(rawSms.sender)} " +
                    "(${route.banks.joinToString { it.id }}); held for review",
            )
        }
        recordIngestionReview(rawSms.id, IngestionReviewService.REASON_AMBIGUOUS_BANK_ROUTE)
        return SmsIngestionResult.ReviewRequired(
            rawSmsId = rawSms.id,
            event = null,
            details = ParsedEventDetails(),
            reasons = listOf(route.reason),
        )
    }

    private suspend fun parseAndPersist(
        rawSms: RawSms,
        adapter: BankSmsAdapter,
        logOutcome: Boolean,
        deriveImmediately: Boolean,
    ): SmsIngestionResult {
        val parseResult = try {
            adapter.parse(
                SmsParseInput(
                    rawSmsId = rawSms.id,
                    sender = rawSms.sender,
                    body = rawSms.body,
                    receivedAt = rawSms.receivedAt,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return processingFailure(rawSms, e.message ?: e::class.java.simpleName, e, logOutcome)
        }

        val parsedBank = parseResult.eventOrNull()?.bank
        if (parsedBank != null && parsedBank != adapter.bank) {
            return processingFailure(
                rawSms = rawSms,
                message = "parser_bank_mismatch:${adapter.bank.id}->${parsedBank.id}",
                cause = null,
                logOutcome = logOutcome,
            )
        }

        return try {
            mapAndSave(rawSms, parseResult, logOutcome, deriveImmediately)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            processingFailure(rawSms, e.message ?: e::class.java.simpleName, e, logOutcome)
        }
    }

    private suspend fun processingFailure(
        rawSms: RawSms,
        message: String,
        cause: Throwable?,
        logOutcome: Boolean,
    ): SmsIngestionResult.Failed {
        if (logOutcome) {
            logIngestFailure(rawSms, message)
        }
        recordIngestionReview(rawSms.id, IngestionReviewService.REASON_PROCESSING_ERROR)
        return SmsIngestionResult.Failed(rawSmsId = rawSms.id, message = message, cause = cause)
    }

    private suspend fun mapAndSave(
        rawSms: RawSms,
        parseResult: ParseResult,
        logOutcome: Boolean,
        deriveImmediately: Boolean,
    ): SmsIngestionResult {
        suspend fun save(event: ParsedEvent, details: ParsedEventDetails, outcome: String) =
            saveEvent(rawSms, event, details, outcome, logOutcome, deriveImmediately)

        return when (parseResult) {
            is ParseResult.Success -> {
                save(parseResult.event, parseResult.details, "parsed")
                    ?: SmsIngestionResult.Parsed(
                        rawSmsId = rawSms.id,
                        event = parseResult.event,
                        details = parseResult.details,
                    )
            }

            is ParseResult.Partial -> {
                if (parseResult.event != null) {
                    save(parseResult.event, parseResult.details, "parsed_partial")
                        ?: SmsIngestionResult.Parsed(
                            rawSmsId = rawSms.id,
                            event = parseResult.event,
                            details = parseResult.details,
                        )
                } else {
                    if (logOutcome) {
                        appLogService?.warn(
                            AppLogCategories.INGEST,
                            "Invalid parse from ${AppLogFormatting.maskSender(rawSms.sender)}",
                        )
                    }
                    recordIngestionReview(rawSms.id, IngestionReviewService.REASON_INVALID_PARSED_EVENT)
                    SmsIngestionResult.Invalid(
                        rawSmsId = rawSms.id,
                        findings = parseResult.findings,
                    )
                }
            }

            is ParseResult.ReviewRequired -> {
                if (parseResult.event != null) {
                    save(parseResult.event, parseResult.details, "review_required")?.let { return it }
                } else {
                    if (logOutcome) {
                        appLogService?.info(
                            AppLogCategories.INGEST,
                            "Review required from ${AppLogFormatting.maskSender(rawSms.sender)}",
                        )
                    }
                    recordIngestionReview(rawSms.id, IngestionReviewService.REASON_PARSE_REVIEW_REQUIRED)
                }
                SmsIngestionResult.ReviewRequired(
                    rawSmsId = rawSms.id,
                    event = parseResult.event,
                    details = parseResult.details,
                    reasons = parseResult.reasons,
                )
            }

            is ParseResult.NonFinancial -> {
                if (parseResult.event != null) {
                    save(parseResult.event, parseResult.details, "non_financial")?.let { return it }
                } else if (logOutcome) {
                    appLogService?.info(
                        AppLogCategories.INGEST,
                        "Non-financial SMS from ${AppLogFormatting.maskSender(rawSms.sender)}",
                    )
                }
                SmsIngestionResult.NonFinancial(
                    rawSmsId = rawSms.id,
                    event = parseResult.event,
                    details = parseResult.details,
                    reason = parseResult.reason,
                )
            }

            is ParseResult.Unsupported -> {
                if (logOutcome) {
                    appLogService?.info(
                        AppLogCategories.INGEST,
                        "Unsupported message from ${AppLogFormatting.maskSender(rawSms.sender)} (${parseResult.reason})",
                    )
                }
                recordIngestionReview(rawSms.id, IngestionReviewService.REASON_UNSUPPORTED_FORMAT)
                SmsIngestionResult.Unsupported(
                    rawSmsId = rawSms.id,
                    reason = parseResult.reason,
                )
            }

            is ParseResult.Invalid -> {
                if (logOutcome) {
                    appLogService?.warn(
                        AppLogCategories.INGEST,
                        "Invalid parse from ${AppLogFormatting.maskSender(rawSms.sender)}",
                    )
                }
                recordIngestionReview(rawSms.id, IngestionReviewService.REASON_INVALID_PARSED_EVENT)
                SmsIngestionResult.Invalid(
                    rawSmsId = rawSms.id,
                    findings = parseResult.findings,
                )
            }
        }
    }

    private suspend fun saveEvent(
        rawSms: RawSms,
        event: ParsedEvent,
        details: ParsedEventDetails,
        outcome: String,
        logOutcome: Boolean,
        deriveImmediately: Boolean,
    ): SmsIngestionResult.DerivedIncomplete? {
        parsedEventRepository.save(event, details)
        val incomplete = if (deriveImmediately) {
            afterParsedEvent(event, details, logOutcome)
        } else {
            null
        }
        if (logOutcome) {
            logParsedOutcome(rawSms, event.messageFamily, outcome)
        }
        return incomplete
    }

    private fun ParseResult.eventOrNull(): ParsedEvent? =
        when (this) {
            is ParseResult.Success -> event
            is ParseResult.Partial -> event
            is ParseResult.ReviewRequired -> event
            is ParseResult.NonFinancial -> event
            is ParseResult.Unsupported,
            is ParseResult.Invalid,
            -> null
        }

    private suspend fun recordIngestionReview(rawSmsId: String, reason: String) {
        val service = ingestionReviewService ?: return
        try {
            service.requireReview(rawSmsId, reason)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Review persistence must not fail evidence ingestion; RawSms stays for reparse.
        }
    }

    private fun logParsedOutcome(
        rawSms: RawSms,
        family: MessageFamily,
        outcome: String,
    ) {
        appLogService?.info(
            AppLogCategories.INGEST,
            "${outcome.replace('_', ' ')} ${AppLogFormatting.messageFamilyLabel(family)} from ${AppLogFormatting.maskSender(rawSms.sender)}",
        )
    }

    private fun logIngestFailure(rawSms: RawSms, message: String) {
        appLogService?.error(
            AppLogCategories.INGEST,
            "Ingest failed for ${AppLogFormatting.maskSender(rawSms.sender)}: $message",
        )
    }

    private suspend fun afterParsedEvent(
        event: ParsedEvent,
        details: ParsedEventDetails,
        logOutcome: Boolean,
    ): SmsIngestionResult.DerivedIncomplete? {
        discoverOwnership(event, details, logOutcome)?.let { return it }
        return when (val reconciled = reconcileDerived(event, details, logOutcome)) {
            ReconcileDerivedResult.NotConfigured -> null
            is ReconcileDerivedResult.Incomplete -> reconciled.result
            is ReconcileDerivedResult.Ready -> refreshReviewQueue(event, details, reconciled.report, logOutcome)
        }
    }

    private suspend fun discoverOwnership(
        event: ParsedEvent,
        details: ParsedEventDetails,
        logOutcome: Boolean,
    ): SmsIngestionResult.DerivedIncomplete? {
        val discovery = ownershipDiscovery ?: return null
        return try {
            discovery.observe(event, details.loanType)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            derivedIncomplete(event, details, DerivedProcessingStage.OWNERSHIP_DISCOVERY, e, logOutcome)
        }
    }

    private suspend fun reconcileDerived(
        event: ParsedEvent,
        details: ParsedEventDetails,
        logOutcome: Boolean,
    ): ReconcileDerivedResult {
        val svc = reconciliation ?: return ReconcileDerivedResult.NotConfigured
        return try {
            ReconcileDerivedResult.Ready(svc.reconcileAfterParsedEventDetailed(event))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Evidence stays. The live worker retries this stage.
            ReconcileDerivedResult.Incomplete(
                derivedIncomplete(event, details, DerivedProcessingStage.RECONCILIATION, e, logOutcome),
            )
        }
    }

    private suspend fun enrichExchangeRates() {
        val workflow = exchangeRateEnrichment ?: return
        try {
            workflow.enrichPending()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Enrichment is best-effort; pending rows are retried by the next run.
        }
    }

    private suspend fun refreshReviewQueue(
        event: ParsedEvent,
        details: ParsedEventDetails,
        report: ReconciliationReport,
        logOutcome: Boolean,
    ): SmsIngestionResult.DerivedIncomplete? {
        val updater = reviewQueueUpdater ?: return null
        return try {
            updater.applyReport(report)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Posted transactions stay. The live worker retries without rolling them back.
            derivedIncomplete(event, details, DerivedProcessingStage.REVIEW_UPDATE, e, logOutcome)
        }
    }

    private fun derivedIncomplete(
        event: ParsedEvent,
        details: ParsedEventDetails,
        stage: DerivedProcessingStage,
        cause: Throwable,
        logOutcome: Boolean,
    ): SmsIngestionResult.DerivedIncomplete {
        if (logOutcome) {
            appLogService?.error(
                AppLogCategories.INGEST,
                "Derived ${stage.name.lowercase()} incomplete (${cause.javaClass.simpleName}); evidence kept for retry",
            )
        }
        return SmsIngestionResult.DerivedIncomplete(
            rawSmsId = event.rawSmsId,
            event = event,
            details = details,
            stage = stage,
            cause = cause,
        )
    }

    private sealed interface ReconcileDerivedResult {
        data object NotConfigured : ReconcileDerivedResult
        data class Ready(val report: ReconciliationReport) : ReconcileDerivedResult
        data class Incomplete(val result: SmsIngestionResult.DerivedIncomplete) : ReconcileDerivedResult
    }

    /**
     * Records a REQUIRED `processing_error` review after live retries are exhausted.
     * Does not reopen a resolved user review.
     */
    suspend fun recordExhaustedDerivedProcessing(rawSmsId: String) {
        recordIngestionReview(rawSmsId, IngestionReviewService.REASON_PROCESSING_ERROR)
    }

    companion object {
        const val REASON_RAW_SMS_NOT_FOUND = "raw_sms_not_found"
    }
}
