package com.baraa.masroof.application.review

import com.baraa.masroof.application.ingestion.ProcessStoredSmsUseCase
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.ExplicitBankSelection
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.time.InstantClock

/**
 * Records one bank for one RawSms and reparses that row with the named adapter.
 *
 * Detector rules are not changed. Other RawSms keep their own route.
 */
class ExplicitBankSelectionWorkflow(
    private val reviewRepository: ReviewRepository,
    private val rawSmsRepository: RawSmsRepository,
    private val parsedEventRepository: ParsedEventRepository,
    private val bankSmsRegistry: BankSmsRegistry,
    private val processStored: ProcessStoredSmsUseCase,
    private val clock: InstantClock,
) {
    fun selectableBanks(): List<Bank> = bankSmsRegistry.banks()

    suspend fun selectAndReparse(rawSmsId: String, bankId: String) {
        if (bankId.isBlank() || '\u001e' in bankId) return
        val bank = Bank.fromId(bankId)
        if (bankSmsRegistry.adapterFor(bank) == null) return
        val existing = reviewRepository.findByRawSmsId(rawSmsId) ?: return
        if (existing.status != ReviewStatus.REQUIRED) return
        val routeReason = existing.reasons.firstOrNull { it in ExplicitBankSelection.ROUTE_REASONS_OFFERING_CHOICE }
        reviewRepository.upsertRequired(
            rawSmsId = rawSmsId,
            kind = existing.kind,
            reasons = ExplicitBankSelection.withSelection(existing.reasons, bank),
            now = clock.now(),
        )
        val rawSms = rawSmsRepository.getById(rawSmsId) ?: return
        processStored.reparseStored(rawSms)
        val parsed = parsedEventRepository.findByRawSmsId(rawSmsId) != null
        if (parsed) {
            clearRouteReason(rawSmsId)
        } else if (routeReason != null) {
            restoreRouteReason(rawSmsId, routeReason)
        }
    }

    private suspend fun clearRouteReason(rawSmsId: String) {
        val current = reviewRepository.findByRawSmsId(rawSmsId) ?: return
        if (current.status != ReviewStatus.REQUIRED) return
        val cleaned = current.reasons.filterNot { it in ExplicitBankSelection.ROUTE_REASONS_OFFERING_CHOICE }
        if (cleaned == current.reasons) return
        reviewRepository.upsertRequired(
            rawSmsId = rawSmsId,
            kind = current.kind,
            reasons = cleaned,
            now = clock.now(),
        )
    }

    private suspend fun restoreRouteReason(rawSmsId: String, routeReason: String) {
        val current = reviewRepository.findByRawSmsId(rawSmsId) ?: return
        if (current.status != ReviewStatus.REQUIRED) return
        if (routeReason in current.reasons) return
        reviewRepository.upsertRequired(
            rawSmsId = rawSmsId,
            kind = current.kind,
            reasons = current.reasons + routeReason,
            now = clock.now(),
        )
    }
}
