package com.baraa.masroof.application.ingestion

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.review.IngestionReviewService
import com.baraa.masroof.application.review.ReviewQueueUpdater
import com.baraa.masroof.application.transaction.ReconciliationReport
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.bank.BankRoutingResult
import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.ownership.OwnershipDiscoveryService
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration

/**
 * RawSms → persist → parse → store pipeline used by historical scan and live intake.
 * Idempotent: duplicates do not re-parse.
 *
 * Optional [ownershipDiscovery], [reconciliation], and [reviewQueueUpdater] run
 * after a ParsedEvent is saved. Failures in those derived steps do not roll back
 * RawSms/ParsedEvent (or already-persisted FinancialTransactions).
 *
 * Every persisted recognized-bank RawSms ends in a durable outcome: a ParsedEvent
 * (processed / non-financial / review via reconciliation) or, when no usable
 * ParsedEvent exists, a direct [IngestionReviewService] review row.
 */
class ProcessRawSmsUseCase(
    private val rawSmsRepository: RawSmsRepository,
    private val parsedEventRepository: ParsedEventRepository,
    private val bankSmsRegistry: BankSmsRegistry,
    private val ownershipDiscovery: OwnershipDiscoveryService? = null,
    private val reconciliation: TransactionReconciliationService? = null,
    private val reviewQueueUpdater: ReviewQueueUpdater? = null,
    private val ingestionReviewService: IngestionReviewService? = null,
    private val appLogService: AppLogService? = null,
) {
    private val insertMutex = Mutex()

    suspend fun ingest(rawSms: RawSms, logOutcome: Boolean = true): SmsIngestionResult {
        val adapter = when (val route = bankSmsRegistry.route(rawSms.sender, rawSms.body)) {
            is BankRoutingResult.NotMatched -> {
                if (logOutcome) {
                    appLogService?.info(
                        AppLogCategories.INGEST,
                        "Ignored non-bank SMS from ${AppLogFormatting.maskSender(rawSms.sender)} (${route.reason})",
                    )
                }
                return SmsIngestionResult.NotRelevant(reason = route.reason)
            }
            is BankRoutingResult.Matched -> route.adapter
        }
        val insertOutcome = try {
            insertMutex.withLock {
                if (hasCrossSourceNearDuplicate(rawSms)) {
                    return@withLock RawSmsInsertResult.AlreadyExists
                }
                rawSmsRepository.insertIfAbsent(rawSms)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e::class.java.simpleName
            if (logOutcome) {
                logIngestFailure(rawSms, message)
            }
            return SmsIngestionResult.Failed(
                rawSmsId = null,
                message = message,
                cause = e,
            )
        }

        return when (insertOutcome) {
            RawSmsInsertResult.AlreadyExists -> {
                if (logOutcome) {
                    appLogService?.info(
                        AppLogCategories.INGEST,
                        "Duplicate SMS from ${AppLogFormatting.maskSender(rawSms.sender)}",
                    )
                }
                SmsIngestionResult.Duplicate
            }
            RawSmsInsertResult.Inserted -> {
                if (logOutcome) {
                    appLogService?.info(
                        AppLogCategories.INGEST,
                        "Inserted SMS from ${AppLogFormatting.maskSender(rawSms.sender)}",
                    )
                }
                parseAndPersist(rawSms, adapter, logOutcome)
            }
        }
    }

    /**
     * Re-runs parse for an already-stored RawSms and replaces its ParsedEvent.
     * Used after parser improvements to refresh the review queue without duplicating evidence.
     *
     * Does not re-apply ingest-time sender detection. Stored SMS is already accepted
     * evidence; detector allowlist changes must not skip backlog refresh.
     *
     * Adapter selection: existing ParsedEvent bank, else the sole registered adapter,
     * else ingest routing only when bank identity cannot be determined.
     */
    suspend fun reparseStored(rawSms: RawSms): SmsIngestionResult {
        val storedBank = parsedEventRepository.findByRawSmsId(rawSms.id)?.event?.bank
        val adapter = storedBank?.let(bankSmsRegistry::adapterFor)
            ?: bankSmsRegistry.singleAdapterOrNull()
            ?: when (val route = bankSmsRegistry.route(rawSms.sender, rawSms.body)) {
                is BankRoutingResult.Matched -> route.adapter
                is BankRoutingResult.NotMatched ->
                    return SmsIngestionResult.NotRelevant(reason = route.reason)
            }
        return parseAndPersist(rawSms, adapter, logOutcome = false)
    }

    private suspend fun hasCrossSourceNearDuplicate(rawSms: RawSms): Boolean {
        val receivedAt = rawSms.receivedAt
        val from = receivedAt.minus(CROSS_SOURCE_RECEIVED_AT_TOLERANCE)
        val to = receivedAt.plus(CROSS_SOURCE_RECEIVED_AT_TOLERANCE)
        val lookingForLiveRow = rawSms.deviceMessageId != null
        return rawSmsRepository.findCrossSourceNearDuplicate(
            sender = rawSms.sender,
            bodyHash = rawSms.bodyHash,
            fromInclusive = from,
            toInclusive = to,
            lookingForLiveRow = lookingForLiveRow,
        ) != null
    }

    private suspend fun parseAndPersist(
        rawSms: RawSms,
        adapter: BankSmsAdapter,
        logOutcome: Boolean,
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
            val message = e.message ?: e::class.java.simpleName
            if (logOutcome) {
                logIngestFailure(rawSms, message)
            }
            recordIngestionReview(rawSms.id, IngestionReviewService.REASON_PROCESSING_ERROR)
            return SmsIngestionResult.Failed(
                rawSmsId = rawSms.id,
                message = message,
                cause = e,
            )
        }

        return try {
            mapAndSave(rawSms, parseResult, logOutcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e::class.java.simpleName
            if (logOutcome) {
                logIngestFailure(rawSms, message)
            }
            recordIngestionReview(rawSms.id, IngestionReviewService.REASON_PROCESSING_ERROR)
            SmsIngestionResult.Failed(
                rawSmsId = rawSms.id,
                message = message,
                cause = e,
            )
        }
    }

    private suspend fun mapAndSave(
        rawSms: RawSms,
        parseResult: ParseResult,
        logOutcome: Boolean,
    ): SmsIngestionResult =
        when (parseResult) {
            is ParseResult.Success -> {
                parsedEventRepository.save(parseResult.event, parseResult.details)
                afterParsedEvent(parseResult.event, parseResult.details.loanType)
                if (logOutcome) {
                    logParsedOutcome(rawSms, parseResult.event.messageFamily, "parsed")
                }
                SmsIngestionResult.Parsed(
                    rawSmsId = rawSms.id,
                    event = parseResult.event,
                    details = parseResult.details,
                )
            }

            is ParseResult.Partial -> {
                if (parseResult.event != null) {
                    parsedEventRepository.save(parseResult.event, parseResult.details)
                    afterParsedEvent(parseResult.event, parseResult.details.loanType)
                    if (logOutcome) {
                        logParsedOutcome(rawSms, parseResult.event.messageFamily, "parsed_partial")
                    }
                    SmsIngestionResult.Parsed(
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
                    parsedEventRepository.save(parseResult.event, parseResult.details)
                    afterParsedEvent(parseResult.event, parseResult.details.loanType)
                    if (logOutcome) {
                        logParsedOutcome(rawSms, parseResult.event.messageFamily, "review_required")
                    }
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
                    parsedEventRepository.save(parseResult.event, parseResult.details)
                    afterParsedEvent(parseResult.event, parseResult.details.loanType)
                    if (logOutcome) {
                        logParsedOutcome(rawSms, parseResult.event.messageFamily, "non_financial")
                    }
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

    private suspend fun afterParsedEvent(event: ParsedEvent, loanType: LoanType?) {
        discoverOwnership(event, loanType)
        val report = reconcileDerived(event) ?: return
        refreshReviewQueue(report)
    }

    private suspend fun discoverOwnership(
        event: ParsedEvent,
        loanType: LoanType?,
    ) {
        val discovery = ownershipDiscovery ?: return
        try {
            discovery.observe(event, loanType)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Discovery is best-effort; RawSms/ParsedEvent evidence stays.
        }
    }

    private suspend fun reconcileDerived(event: ParsedEvent): ReconciliationReport? {
        val svc = reconciliation ?: return null
        return try {
            svc.reconcileAfterParsedEventDetailed(event)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // P8 derived processing must not destroy RawSms/ParsedEvent evidence.
            null
        }
    }

    private suspend fun refreshReviewQueue(report: ReconciliationReport) {
        val updater = reviewQueueUpdater ?: return
        try {
            updater.applyReport(report)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // P9 review persistence must not fail successful evidence ingestion.
        }
    }

    companion object {
        /**
         * Maximum |live receipt − historical DATE| for opposite-source reconciliation.
         * Same-source rows are never merged by this window alone.
         */
        val CROSS_SOURCE_RECEIVED_AT_TOLERANCE: Duration = Duration.ofSeconds(5)
    }
}
