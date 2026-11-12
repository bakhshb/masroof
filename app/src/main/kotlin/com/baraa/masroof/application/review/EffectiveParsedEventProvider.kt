package com.baraa.masroof.application.review

import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.repository.UserDecisionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository

/**
 * Application projection: current [ParsedEvent] + the user's `CORRECTION` decisions.
 *
 * Corrections are applied in [com.baraa.masroof.domain.model.EffectiveUserDecisions.corrections] order (oldest
 * first); each non-null corrected field overlays the previous projection (later wins per field). List reads load
 * those decisions once per bind-sized chunk.
 * Does not mutate RawSms or stored ParsedEvent rows.
 */
class EffectiveParsedEventProvider(
    private val parsedEventRepository: ParsedEventRepository,
    private val userDecisionRepository: UserDecisionRepository,
) {
    suspend fun findEffectiveByRawSmsId(rawSmsId: String): ParsedEventRecord? {
        val stored = parsedEventRepository.findByRawSmsId(rawSmsId) ?: return null
        return applyCorrections(stored, userDecisionRepository.effectiveFor(rawSmsId).corrections)
    }

    suspend fun listEffectiveByRawSmsIds(rawSmsIds: Collection<String>): List<ParsedEventRecord> =
        withCorrections(parsedEventRepository.listByRawSmsIds(rawSmsIds))

    /** [ParsedEventRepository.listTransferCandidates] with corrections applied. */
    suspend fun listTransferCandidatesEffective(
        receivedAt: ClosedRange<java.time.Instant>,
        occurredAt: ClosedRange<java.time.Instant>,
        occurredAtLocal: ClosedRange<java.time.LocalDateTime>,
    ): List<ParsedEventRecord> =
        withCorrections(parsedEventRepository.listTransferCandidates(receivedAt, occurredAt, occurredAtLocal))

    /** Every raw SMS whose parse names [loan] (any family; corrections never change the loan). */
    suspend fun listRawSmsIdsReferencingLoan(loan: com.baraa.masroof.domain.model.LoanReference): List<String> =
        parsedEventRepository.listRawSmsIdsReferencingLoan(loan)

    suspend fun listAllEffective(): List<ParsedEventRecord> =
        withCorrections(parsedEventRepository.listAll())

    private suspend fun withCorrections(records: List<ParsedEventRecord>): List<ParsedEventRecord> {
        if (records.isEmpty()) return emptyList()
        val decisions = userDecisionRepository.effectiveFor(records.map { it.event.rawSmsId })
        return records.map { record ->
            applyCorrections(record, decisions[record.event.rawSmsId]?.corrections.orEmpty())
        }
    }

    /** [corrections] are `CORRECTION` decisions, oldest first. */
    fun applyCorrections(
        record: ParsedEventRecord,
        corrections: List<UserDecision>,
    ): ParsedEventRecord {
        if (corrections.isEmpty()) return record
        var event = record.event
        for (correction in corrections) {
            event = overlay(event, correction)
        }
        val automationConfirmed = corrections.any { correction ->
            correction.messageFamily != null || correction.amount != null
        }
        return ParsedEventRecord(
            event = event,
            details = record.details,
            userCorrected = true,
            automationConfirmed = automationConfirmed,
        )
    }

    private fun overlay(event: ParsedEvent, correction: UserDecision): ParsedEvent =
        event.copy(
            messageFamily = correction.messageFamily ?: event.messageFamily,
            amount = correction.amount ?: event.amount,
            merchant = correction.merchant ?: event.merchant,
            counterparty = correction.counterparty ?: event.counterparty,
        )
}
