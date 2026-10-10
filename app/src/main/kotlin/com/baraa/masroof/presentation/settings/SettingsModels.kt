package com.baraa.masroof.presentation.settings

import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.application.update.UpdateChannel
import com.baraa.masroof.application.update.UpdateManifest
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardNetwork
import com.baraa.masroof.domain.model.CardRole
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.AccountType
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.application.settings.CommitmentRecordEvent
import com.baraa.masroof.application.settings.CommitmentPauseIntervalSummary
import com.baraa.masroof.domain.model.CommitmentRecurrence
import java.time.LocalDate

enum class CommitmentsListTab {
    ACTIVE,
    DISABLED,
    RECORD,
}

data class CommitmentRecordEntryUi(
    val commitmentId: String,
    val commitmentName: String,
    val event: CommitmentRecordEvent,
    val at: LocalDate,
)

data class ManagedCardUi(
    val id: String,
    val bank: Bank,
    val last4: String,
    val ownership: OwnershipStatus,
    val displayName: String? = null,
    val cardNetwork: CardNetwork? = null,
    val cardType: CardType? = null,
    val cardRole: CardRole? = null,
    val parentCardLast4: String? = null,
    val linkedAccountMaskedNumber: String? = null,
) {
    val displayLabel: String
        get() = displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: when (cardRole) {
                CardRole.PRIMARY -> "Primary ••$last4"
                CardRole.SUPPLEMENTARY -> "Additional ••$last4"
                CardRole.STANDALONE, null -> when (cardType) {
                    CardType.DEBIT -> "Mada ••$last4"
                    CardType.CREDIT -> "Credit ••$last4"
                    null -> "••$last4"
                }
            }
}

data class ManagedAccountUi(
    val id: String,
    val bank: Bank,
    val maskedNumber: String,
    val ownership: OwnershipStatus,
    val displayName: String? = null,
    val accountType: AccountType = AccountType.CURRENT,
) {
    val displayLabel: String
        get() = displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Account ••${maskedNumber.takeLast(4)}"
}

data class SettingsBankTreeUi(
    val bank: Bank,
    val currentAccountNodes: List<SettingsCurrentAccountNodeUi> = emptyList(),
    val savingsAccounts: List<ManagedAccountUi>,
    val walletAccounts: List<ManagedAccountUi>,
    val creditCards: List<ManagedCardUi>,
    val unlinkedDebitCards: List<ManagedCardUi> = emptyList(),
    val loans: List<ManagedLoanUi> = emptyList(),
) {
    /** All owned debit cards for this bank, including those linked to current accounts. */
    val allDebitCards: List<ManagedCardUi>
        get() = (currentAccountNodes.flatMap { it.debitCards } + unlinkedDebitCards)
            .distinctBy { it.id }
}

data class SettingsCurrentAccountNodeUi(
    val account: ManagedAccountUi,
    val debitCards: List<ManagedCardUi>,
)

data class ManagedLoanUi(
    val id: String,
    val bank: Bank,
    val loanType: com.baraa.masroof.domain.model.LoanType,
    val ownership: OwnershipStatus,
    val displayName: String? = null,
)

data class ManagedCommitmentUi(
    val id: String,
    val name: String,
    val amount: com.baraa.masroof.core.money.Money,
    val transactionDate: LocalDate,
    val recurrence: CommitmentRecurrence?,
    val dueDate: LocalDate?,
    val active: Boolean,
    val sourceTransactionId: String,
    val pauseIntervals: List<CommitmentPauseIntervalSummary> = emptyList(),
)

data class SettingsBankSummaryUi(
    val bank: Bank,
    val followedAccountCount: Int,
    val unregisteredAccountCount: Int,
    val stoppedAccountCount: Int,
    val followedCardCount: Int,
    val unregisteredCardCount: Int,
    val stoppedCardCount: Int,
    val loanCount: Int,
) {
    val accountCount: Int
        get() = followedAccountCount + unregisteredAccountCount + stoppedAccountCount

    val cardCount: Int
        get() = followedCardCount + unregisteredCardCount + stoppedCardCount

    val hasContent: Boolean
        get() = accountCount > 0 || cardCount > 0 || loanCount > 0

    val unregisteredCount: Int
        get() = unregisteredAccountCount + unregisteredCardCount
}

data class SettingsUiState(
    val loading: Boolean = true,
    val followedCards: List<ManagedCardUi> = emptyList(),
    val unregisteredCards: List<ManagedCardUi> = emptyList(),
    val stoppedCards: List<ManagedCardUi> = emptyList(),
    val followedAccounts: List<ManagedAccountUi> = emptyList(),
    val unregisteredAccounts: List<ManagedAccountUi> = emptyList(),
    val stoppedAccounts: List<ManagedAccountUi> = emptyList(),
    val loans: List<ManagedLoanUi> = emptyList(),
    val activeCommitments: List<ManagedCommitmentUi> = emptyList(),
    val disabledCommitments: List<ManagedCommitmentUi> = emptyList(),
    val commitmentRecordEntries: List<CommitmentRecordEntryUi> = emptyList(),
    val commitmentsListTab: CommitmentsListTab = CommitmentsListTab.ACTIVE,
    val commitmentsLoaded: Boolean = false,
    val savingCommitment: Boolean = false,
    val bankTrees: List<SettingsBankTreeUi> = emptyList(),
    val bankSummaries: List<SettingsBankSummaryUi> = emptyList(),
    val appVersion: String = "",
    val languageTag: String = "",
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    val updating: Boolean = false,
    val stopConfirmCardTarget: ManagedCardUi? = null,
    val stopConfirmAccountTarget: ManagedAccountUi? = null,
    val stopConfirmLoanTarget: ManagedLoanUi? = null,
    val renameCardTarget: ManagedCardUi? = null,
    val renameAccountTarget: ManagedAccountUi? = null,
    val accountTypeTarget: ManagedAccountUi? = null,
    val cardNetworkTarget: ManagedCardUi? = null,
    val cardRoleTarget: ManagedCardUi? = null,
    val linkDebitTarget: ManagedCardUi? = null,
    val reparsingStored: Boolean = false,
    val importingSms: Boolean = false,
    val smsImportMessage: SmsImportMessage? = null,
    val smsPermissionGranted: Boolean = true,
    val exportingBackup: Boolean = false,
    val importingBackup: Boolean = false,
    val awaitingImportConfirm: Boolean = false,
    val awaitingLegacyImportConfirm: Boolean = false,
    val exportPassphrase: String = "",
    val importPassphrase: String = "",
    val backupMessage: BackupMessage? = null,
    val error: SettingsError? = null,
    val githubTokenConfigured: Boolean = false,
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val isNightlyBuild: Boolean = false,
    val updateState: AppUpdateUiState = AppUpdateUiState.Idle,
    val updateMessage: AppUpdateMessage? = null,
    val exportingLogs: Boolean = false,
    val logMessage: LogMessage? = null,
    val fxRateCorrectionUpdatable: Int = 0,
    val fxRateCorrectionManualFollowUp: Int = 0,
    val fxRateCorrectionRunning: Boolean = false,
    val fxRateCorrectionAwaitingConfirm: Boolean = false,
)

enum class LogMessage {
    EXPORT_SUCCESS,
    EXPORT_FAILED,
    CLEARED,
}

enum class BackupMessage {
    EXPORT_REENTER_PASSPHRASE,
    EXPORT_SUCCESS,
    EXPORT_FAILED,
    IMPORT_FAILED,
    IMPORT_INVALID,
    PASSPHRASE_REQUIRED,
    IMPORT_AUTH_FAILED,
}

enum class SmsImportMessage {
    OK,
    ALREADY_UP_TO_DATE,
    NEEDS_REVIEW,
    PERMISSION_DENIED,
    NO_MESSAGES,
    NO_BANK_SMS,
    NO_TRANSACTIONS,
    FAILED,
}

enum class SettingsError {
    UPDATE_FAILED,
}

enum class AppUpdateMessage {
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DOWNLOAD_SUCCESS,
    TOKEN_SAVED,
    TOKEN_SAVE_FAILED,
    TOKEN_REQUIRED,
    AUTH_FAILED,
    CHECK_FAILED,
    DOWNLOAD_FAILED,
    INSTALL_FAILED,
    INSTALL_PERMISSION_REQUIRED,
}

sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState

    data object Checking : AppUpdateUiState

    data object UpToDate : AppUpdateUiState

    data class Available(
        val manifest: UpdateManifest,
    ) : AppUpdateUiState

    data class Downloading(
        val manifest: UpdateManifest,
        val bytesRead: Long,
        val totalBytes: Long,
    ) : AppUpdateUiState

    data class ReadyToInstall(
        val manifest: UpdateManifest,
    ) : AppUpdateUiState
}
fun SettingsUiState.bankSummary(bankId: String): SettingsBankSummaryUi? =
    bankSummaries.firstOrNull { it.bank.id == bankId }

fun SettingsUiState.bankTree(bankId: String): SettingsBankTreeUi? =
    bankTrees.firstOrNull { it.bank.id == bankId }
