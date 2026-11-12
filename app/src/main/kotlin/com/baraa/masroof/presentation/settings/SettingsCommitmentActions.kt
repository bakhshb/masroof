package com.baraa.masroof.presentation.settings

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.presentation.common.launchLogged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Commitment edits for [SettingsViewModel]. It shares the Settings state, so screens keep one state object. */
internal class SettingsCommitmentActions(
    private val scope: CoroutineScope,
    private val settingsState: MutableStateFlow<SettingsUiState>,
    private val settingsCommitmentsWorkflow: SettingsCommitmentsWorkflow,
    private val appLogService: AppLogService,
) {
    fun saveCommitment(commitmentId: String, draft: SettingsCommitmentsWorkflow.CommitmentEditorDraft) {
        commitmentWrite("Settings saveCommitment") {
            settingsCommitmentsWorkflow.update(commitmentId, draft)
            applyCommitments(settingsCommitmentsWorkflow.listAll())
        }
    }

    /** One commitment write at a time: each is read-then-write, so overlapping taps would lose an update. */
    private fun commitmentWrite(action: String, block: suspend () -> Unit) {
        if (settingsState.value.savingCommitment) return
        settingsState.update { it.copy(savingCommitment = true) }
        scope.launchLogged(appLogService, AppLogCategories.SETTINGS, action, onError = { settingsState.update { it.copy(error = SettingsError.UPDATE_FAILED) } }, onFinally = { settingsState.update { it.copy(savingCommitment = false) } }) {
            block()
        }
    }

    fun setCommitmentsListTab(tab: CommitmentsListTab) {
        settingsState.update { it.copy(commitmentsListTab = tab) }
    }

    fun toggleCommitmentActive(commitmentId: String) {
        commitmentWrite("Settings toggleCommitmentActive") {
            settingsCommitmentsWorkflow.toggleActive(commitmentId)
            applyCommitments(settingsCommitmentsWorkflow.listAll())
        }
    }

    fun deleteCommitment(commitmentId: String, onDeleted: () -> Unit = {}) {
        commitmentWrite("Settings deleteCommitment") {
            settingsCommitmentsWorkflow.delete(commitmentId)
            applyCommitments(settingsCommitmentsWorkflow.listAll())
            onDeleted()
        }
    }

    fun commitmentById(commitmentId: String): ManagedCommitmentUi? =
        (settingsState.value.activeCommitments + settingsState.value.disabledCommitments)
            .find { it.id == commitmentId }

    private fun applyCommitments(commitments: List<Commitment>) {
        settingsState.update { it.withCommitments(commitments) }
    }
}
