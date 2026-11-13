package com.baraa.masroof.presentation.onboarding

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.onboarding.OnboardingOwnershipWorkflow
import com.baraa.masroof.application.onboarding.OnboardingPreferencesRepository
import com.baraa.masroof.application.review.ReviewWorkflowService
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.presentation.common.launchLogged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The ownership step and the finish of onboarding, for [OnboardingViewModel]. It shares the onboarding state. */
internal class OnboardingOwnershipActions(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<OnboardingUiState>,
    private val onboardingPrefs: OnboardingPreferencesRepository,
    private val onboardingOwnershipWorkflow: OnboardingOwnershipWorkflow,
    private val refreshReviewQueue: suspend () -> Unit,
    private val reconcileOwnershipChange: suspend (ReviewWorkflowService.OwnershipChange) -> Unit,
    private val isBusy: () -> Boolean,
    private val appLogService: com.baraa.masroof.application.logging.AppLogService?,
) {
    fun setAccountOwnership(candidate: OwnershipCandidateUi, owned: Boolean) {
        if (isBusy() || candidate.bank.id !in uiState.value.selectedBankIds) return
        uiState.update { it.copy(updatingOwnership = true, error = null) }
        scope.launchLogged(appLogService, AppLogCategories.OWNERSHIP, "Onboarding setAccountOwnership", onError = {
            reloadAfterOwnershipFailure()
            uiState.update { it.copy(error = OnboardingError.OWNERSHIP_UPDATE_FAILED) }
        }, onFinally = { uiState.update { it.copy(updatingOwnership = false) } }) {
            val ref = AccountReference(candidate.bank, candidate.suffix)
            if (owned) {
                onboardingOwnershipWorkflow.confirmAccountOwned(ref)
            } else {
                onboardingOwnershipWorkflow.markAccountExternal(ref)
            }
            reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Account(ref))
            loadCandidatesAndCounts()
        }
    }

    fun setCardOwnership(candidate: OwnershipCandidateUi, owned: Boolean) {
        if (isBusy() || candidate.bank.id !in uiState.value.selectedBankIds) return
        uiState.update { it.copy(updatingOwnership = true, error = null) }
        scope.launchLogged(appLogService, AppLogCategories.OWNERSHIP, "Onboarding setCardOwnership", onError = {
            reloadAfterOwnershipFailure()
            uiState.update { it.copy(error = OnboardingError.OWNERSHIP_UPDATE_FAILED) }
        }, onFinally = { uiState.update { it.copy(updatingOwnership = false) } }) {
            val ref = CardReference(candidate.bank, candidate.suffix)
            if (owned) {
                onboardingOwnershipWorkflow.confirmCardOwned(ref)
            } else {
                onboardingOwnershipWorkflow.markCardExternal(ref)
            }
            reconcileOwnershipChange(ReviewWorkflowService.OwnershipChange.Card(ref))
            loadCandidatesAndCounts()
        }
    }

    fun finalizeOnboarding() {
        if (isBusy()) return
        uiState.update { it.copy(finalizing = true, error = null) }
        scope.launchLogged(appLogService, AppLogCategories.OWNERSHIP, "Onboarding finalizeOnboarding", onError = {
            uiState.update { it.copy(finalizing = false, error = OnboardingError.FINALIZATION_FAILED) }
        }, onFinally = { uiState.update { it.copy(finalizing = false) } }) {
            val snapshot = onboardingOwnershipWorkflow.loadSnapshot(uiState.value.selectedBankIds)
            if (!uiState.value.banksConfirmed || snapshot.hasUnknownCandidates || snapshot.cards.any { it.ownership == com.baraa.masroof.domain.model.OwnershipStatus.OWNED && it.cardType == null }) {
                uiState.update { it.copy(error = OnboardingError.OWNERSHIP_UPDATE_FAILED) }
                return@launchLogged
            }
            refreshReviewQueue()
            loadCandidatesAndCounts()
            onboardingPrefs.setOnboardingCompleted(true)
            uiState.update {
                it.copy(
                    onboardingCompleted = true,
                    finalizing = false,
                    step = OnboardingStep.FINALIZE,
                )
            }
        }
    }

    suspend fun loadCandidatesAndCounts() {
        val snapshot = onboardingOwnershipWorkflow.loadSnapshot(uiState.value.selectedBankIds)
        uiState.update {
            it.copy(
                accounts = snapshot.accounts.map(::toOwnershipCandidateUi),
                cards = snapshot.cards.map(::toOwnershipCandidateUi),
                ownedAccountsCount = snapshot.ownedAccountsCount,
                ownedCardsCount = snapshot.ownedCardsCount,
                reviewRequiredCount = snapshot.reviewRequiredCount,
            )
        }
    }

    private suspend fun reloadAfterOwnershipFailure() {
        try { loadCandidatesAndCounts() }
        catch (error: CancellationException) { throw error }
        catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.OWNERSHIP, "Onboarding reloadAfterOwnershipFailure", caught); /* Preserve the durable decision and show the write/retry failure. */ }
    }

    fun refreshOwnership() {
        if (uiState.value.step != OnboardingStep.OWNERSHIP || isBusy()) return
        scope.launchLogged(appLogService, AppLogCategories.OWNERSHIP, "Onboarding refreshOwnership", onError = { uiState.update { it.copy(error = OnboardingError.OWNERSHIP_UPDATE_FAILED) } }) {
            loadCandidatesAndCounts()
        }
    }

    private fun toOwnershipCandidateUi(
        candidate: OnboardingOwnershipWorkflow.OwnershipCandidate,
    ): OwnershipCandidateUi =
        OwnershipCandidateUi(
            kind = when (candidate.kind) {
                OnboardingOwnershipWorkflow.CandidateKind.ACCOUNT ->
                    OwnershipCandidateUi.CandidateKind.ACCOUNT
                OnboardingOwnershipWorkflow.CandidateKind.CARD ->
                    OwnershipCandidateUi.CandidateKind.CARD
            },
            bank = candidate.bank,
            suffix = candidate.suffix,
            ownership = candidate.ownership,
            cardType = candidate.cardType,
            discoveredFromSms = candidate.discoveredFromSms,
        )
}
