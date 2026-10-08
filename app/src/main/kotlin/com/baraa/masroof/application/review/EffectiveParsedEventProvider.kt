package com.baraa.masroof.application.review

import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.repository.UserCorrectionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository

/**
 * Application projection: current [ParsedEvent] + user corrections.
 *
 * Corrections are applied in ascending `(createdAt, id)` order; each non-null
 * corrected field overlays the previous projection (later wins per field).
 * List reads load those corrections once per bind-sized chunk. Single-record
 * reads stay on [UserCorrectionRepository.listForRawSmsId].
 * Does not mutate RawSms or stored ParsedEvent rows.
 */
class EffectiveParsedEventProvider(
    private val parsedEventRepository: ParsedEventRepository,
    private val userCorrectionRepository: UserCorrectionRepository,
) {
    suspend fun findEffectiveByRawSmsId(rawSmsId: String): ParsedEventRecord? {
        val stored = parsedEventRepository.findByRawSmsId(rawSmsId) ?: return null
        val corrections = userCorrectionRepository.listForRawSmsId(rawSmsId)
        return applyCorrections(stored, corrections)
    }

    suspend fun getEffectiveById(id: String): ParsedEventRecord? {
        val stored = parsedEventRepository.getById(id) ?: return null
        val corrections = userCorrectionRepository.listForRawSmsId(stored.event.rawSmsId)
        return applyCorrections(stored, corrections)
    }

    suspend fun listUnlinkedTransfersEffective(): List<ParsedEventRecord> =
        withCorrections(parsedEventRepository.listUnlinkedTransfers())

    suspend fun listEffectiveByRawSmsIds(rawSmsIds: Collection<String>): List<ParsedEventRecord> =
        withCorrections(parsedEventRepository.listByRawSmsIds(rawSmsIds))

    suspend fun listUnlinkedTransfersEffectiveReceivedBetween(
        startInclusive: java.time.Instant,
        endExclusive: java.time.Instant,
    ): List<ParsedEventRecord> =
        withCorrections(
            parsedEventRepository.listUnlinkedTransfersReceivedBetween(startInclusive, endExclusive),
        )

    suspend fun listUnlinkedTransfersEffectiveOccurredLocalBetween(
        startInclusive: java.time.LocalDateTime,
        endExclusive: java.time.LocalDateTime,
    ): List<ParsedEventRecord> =
        withCorrections(
            parsedEventRepository.listUnlinkedTransfersOccurredLocalBetween(startInclusive, endExclusive),
        )

    suspend fun listAllEffective(): List<ParsedEventRecord> =
        withCorrections(parsedEventRepository.listAll())

    suspend fun listEffectiveReceivedBetween(
        startInclusive: java.time.Instant,
        endExclusive: java.time.Instant,
    ): List<ParsedEventRecord> =
        withCorrections(parsedEventRepository.listReceivedBetween(startInclusive, endExclusive))

    private suspend fun withCorrections(records: List<ParsedEventRecord>): List<ParsedEventRecord> {
        if (records.isEmpty()) return emptyList()
        val correctionsByRawSmsId = userCorrectionRepository
            .listForRawSmsIds(records.map { it.event.rawSmsId })
            .groupBy { it.targetRawSmsId }
        return records.map { record ->
            applyCorrections(record, correctionsByRawSmsId[record.event.rawSmsId].orEmpty())
        }
    }

    fun applyCorrections(
        record: ParsedEventRecord,
        corrections: List<UserCorrection>,
    ): ParsedEventRecord {
        if (corrections.isEmpty()) return record
        val ordered = corrections.sortedWith(
            compareBy<UserCorrection> { it.createdAt }.thenBy { it.id },
        )
        var event = record.event
        for (correction in ordered) {
            event = overlay(event, correction)
        }
        val automationConfirmed = ordered.any { correction ->
            correction.correctedType != null || correction.correctedAmount != null
        }
        return ParsedEventRecord(
            event = event,
            details = record.details,
            userCorrected = true,
            automationConfirmed = automationConfirmed,
        )
    }

    private fun overlay(event: ParsedEvent, correction: UserCorrection): ParsedEvent =
        event.copy(
            messageFamily = correction.correctedType ?: event.messageFamily,
            amount = correction.correctedAmount ?: event.amount,
            merchant = correction.correctedMerchant ?: event.merchant,
            counterparty = correction.correctedCounterparty ?: event.counterparty,
        )
}
