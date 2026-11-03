package com.baraa.masroof.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBankScreen(
    bank: Bank,
    summary: SettingsBankSummaryUi,
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenLoans: () -> Unit,
    onOpenPatterns: () -> Unit = {},
    definition: com.baraa.masroof.application.patterns.PatternBankView? = null,
    onImport: () -> Unit = {},
    onStatement: () -> Unit = {},
    onEditBank: () -> Unit = {},
) {
    val accountsSubtitle = formatRegistryCategorySubtitle(
        state = resolveRegistryCategorySubtitle(
            followed = summary.followedAccountCount,
            unregistered = summary.unregisteredAccountCount,
            stopped = summary.stoppedAccountCount,
        ),
        emptyLabel = stringResource(R.string.settings_accounts_empty),
        followedUnregisteredRes = R.plurals.settings_hub_accounts_subtitle,
        followedOnlyRes = R.plurals.settings_hub_accounts_subtitle_followed_only,
        stoppedOnlyRes = R.plurals.settings_hub_accounts_subtitle_stopped_only,
        followedStoppedRes = R.plurals.settings_hub_accounts_subtitle_followed_stopped,
    )
    val cardsSubtitle = formatRegistryCategorySubtitle(
        state = resolveRegistryCategorySubtitle(
            followed = summary.followedCardCount,
            unregistered = summary.unregisteredCardCount,
            stopped = summary.stoppedCardCount,
        ),
        emptyLabel = stringResource(R.string.settings_cards_empty),
        followedUnregisteredRes = R.plurals.settings_hub_cards_subtitle,
        followedOnlyRes = R.plurals.settings_hub_cards_subtitle_followed_only,
        stoppedOnlyRes = R.plurals.settings_hub_cards_subtitle_stopped_only,
        followedStoppedRes = R.plurals.settings_hub_cards_subtitle_followed_stopped,
    )
    val loansSubtitle = if (summary.loanCount > 0) {
        pluralStringResource(R.plurals.settings_bank_summary_loans, summary.loanCount, summary.loanCount)
    } else {
        stringResource(R.string.settings_loans_empty)
    }

    MasroofSecondaryScaffold(
        title = settingsBankLabel(bank),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
        ) {
            definition?.let {
                val (support, accent) = bankSupportLabel(it)
                com.baraa.masroof.presentation.common.MasroofCard {
                    androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        com.baraa.masroof.presentation.common.MasroofText(stringResource(R.string.settings_bank_reading_messages),
                            androidx.compose.ui.Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        com.baraa.masroof.presentation.common.MasroofBadge(support, accent)
                    }
                }
            }
            com.baraa.masroof.presentation.common.MasroofCard {
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_bank_accounts_title), accountsSubtitle, MasroofIcons.moneyMovement, onOpenAccounts)
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_bank_cards_title), cardsSubtitle, MasroofIcons.cardPayment, onOpenCards)
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_bank_loans_title), loansSubtitle, MasroofIcons.savings, onOpenLoans, divider = false)
            }
            com.baraa.masroof.presentation.common.MasroofCard {
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_statement_reconciliation_title), stringResource(R.string.settings_statement_reconciliation_subtitle), MasroofIcons.recentTransactions, onStatement, divider = definition != null)
                if (definition != null) {
                    com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_bank_teaching), stringResource(R.string.pattern_teach), MasroofIcons.sms, onOpenPatterns)
                    com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_bank_details),
                        if (definition.senders.isEmpty()) stringResource(R.string.settings_bank_senders)
                        else pluralStringResource(R.plurals.settings_bank_sender_count, definition.senders.size, definition.senders.size),
                        MasroofIcons.moneyMovement, onEditBank, divider = false)
                }
            }
            if (definition != null) com.baraa.masroof.presentation.common.MasroofOutlinedButton(onImport,
                modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                com.baraa.masroof.presentation.common.MasroofText(stringResource(R.string.pattern_import_bank))
            }
            else com.baraa.masroof.presentation.common.MasroofOutlinedButton(onEditBank,
                modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                com.baraa.masroof.presentation.common.MasroofText(stringResource(R.string.settings_bank_setup_messages))
            }

        }
    }
}

internal sealed interface RegistryCategorySubtitleState {
    data object Empty : RegistryCategorySubtitleState

    data class FollowedUnregistered(
        val followed: Int,
        val unregistered: Int,
        val stopped: Int,
    ) : RegistryCategorySubtitleState

    data class FollowedStopped(
        val followed: Int,
        val stopped: Int,
    ) : RegistryCategorySubtitleState

    data class StoppedOnly(
        val stopped: Int,
    ) : RegistryCategorySubtitleState

    data class FollowedOnly(
        val followed: Int,
    ) : RegistryCategorySubtitleState
}

internal fun resolveRegistryCategorySubtitle(
    followed: Int,
    unregistered: Int,
    stopped: Int,
): RegistryCategorySubtitleState {
    val total = followed + unregistered + stopped
    if (total == 0) return RegistryCategorySubtitleState.Empty
    if (unregistered > 0) {
        return RegistryCategorySubtitleState.FollowedUnregistered(
            followed = followed,
            unregistered = unregistered,
            stopped = stopped,
        )
    }
    if (followed > 0 && stopped > 0) {
        return RegistryCategorySubtitleState.FollowedStopped(
            followed = followed,
            stopped = stopped,
        )
    }
    if (stopped > 0) return RegistryCategorySubtitleState.StoppedOnly(stopped)
    return RegistryCategorySubtitleState.FollowedOnly(followed)
}

@Composable
internal fun formatRegistryCategorySubtitle(
    state: RegistryCategorySubtitleState,
    emptyLabel: String,
    followedUnregisteredRes: Int,
    followedOnlyRes: Int,
    stoppedOnlyRes: Int,
    followedStoppedRes: Int,
): String =
    when (state) {
        RegistryCategorySubtitleState.Empty -> emptyLabel
        is RegistryCategorySubtitleState.FollowedUnregistered -> {
            val base = pluralStringResource(
                followedUnregisteredRes,
                state.followed,
                state.followed,
                state.unregistered,
            )
            if (state.stopped > 0) {
                base + stringResource(R.string.settings_bank_category_stopped_suffix, state.stopped)
            } else {
                base
            }
        }
        is RegistryCategorySubtitleState.FollowedStopped -> pluralStringResource(
            followedStoppedRes,
            state.followed,
            state.followed,
            state.stopped,
        )
        is RegistryCategorySubtitleState.StoppedOnly -> pluralStringResource(
            stoppedOnlyRes,
            state.stopped,
            state.stopped,
        )
        is RegistryCategorySubtitleState.FollowedOnly -> pluralStringResource(
            followedOnlyRes,
            state.followed,
            state.followed,
        )
    }
