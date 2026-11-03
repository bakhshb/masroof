
package com.baraa.masroof.presentation.settings

import com.baraa.masroof.ui.theme.MasroofSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import com.baraa.masroof.domain.model.CardNetwork
import com.baraa.masroof.domain.model.CardRole
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.ui.theme.MasroofIconSizes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.presentation.common.CardOwnershipInlinePrompt
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.formatCardLast4
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBankCardsScreen(
    bank: Bank,
    state: SettingsUiState,
    onBack: () -> Unit,
    onConfirmOwned: (ManagedCardUi) -> Unit,
    onMarkExternal: (ManagedCardUi) -> Unit,
    onRequestStopTracking: (ManagedCardUi) -> Unit,
    onResumeTracking: (ManagedCardUi) -> Unit,
    onDismissStopConfirm: () -> Unit,
    onConfirmStopTracking: () -> Unit,
    onRenameCard: (ManagedCardUi) -> Unit,
    onDismissRenameCard: () -> Unit,
    onSaveCardName: (String) -> Unit,
    onPickCardNetwork: (ManagedCardUi) -> Unit,
    onDismissCardNetwork: () -> Unit,
    onSaveCardSetup: (com.baraa.masroof.domain.model.CardConfiguration) -> Unit = {},
    deleteActions: RegistryDeleteActions,
    onOpenBank: (() -> Unit)? = null,
    onAddManual: (() -> Unit)? = null,
) {
    var referenceTab by rememberSaveable { mutableStateOf("followed") }

    val unregisteredCards = state.unregisteredCards.filter { it.bank == bank }
    val followedCards = state.followedCards.filter { it.bank == bank }
    val stoppedCards = state.stoppedCards.filter { it.bank == bank }
    val bankTree = state.bankTree(bank.id)

    SettingsDeleteDialogHost(state, deleteActions) { (it as? StopTrackingTarget.Card)?.card?.let { card -> registryLabel(card) } }
    state.stopConfirmCardTarget?.let { target ->
        SettingsStopConfirmDialog(
            title = stringResource(R.string.settings_stop_confirm_title),
            body = stringResource(R.string.settings_stop_confirm_body, formatCardLast4(target.last4)),
            updating = state.updating,
            onDismiss = onDismissStopConfirm,
            onConfirm = onConfirmStopTracking,
        )
    }
    SettingsRenameCardDialog(
        target = state.renameCardTarget,
        updating = state.updating,
        onDismiss = onDismissRenameCard,
        onSave = onSaveCardName,
    )
    SettingsCardSetupSheet(
        target = state.cardNetworkTarget,
        suggestion = state.cardTypeSuggestion,
        cards = state.followedCards + state.unregisteredCards + state.stoppedCards,
        accounts = state.followedAccounts,
        busy = state.updating,
        failed = state.error != null,
        onDismiss = onDismissCardNetwork,
        onSave = onSaveCardSetup,
    )
    MasroofSecondaryScaffold(
        title = stringResource(R.string.reference_manage_cards),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
        ) {
            SettingsScreenHeader(
                bank = bank,
                hint = stringResource(R.string.settings_cards_hint),
                onOpenBank = onOpenBank,
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofReference.shortcutGap), verticalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofReference.shortcutGap)) {
                listOf("followed" to R.string.reference_followed, "unconfirmed" to R.string.reference_unconfirmed,
                    "stopped" to R.string.reference_stopped).forEach { (key, label) ->
                    com.baraa.masroof.presentation.common.MasroofChoice(stringResource(label), referenceTab == key, { referenceTab = key })
                }
            }


            if (followedCards.isEmpty() && unregisteredCards.isEmpty() && stoppedCards.isEmpty()) {
                Text(
                    stringResource(R.string.settings_cards_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (referenceTab == "unconfirmed" && unregisteredCards.isNotEmpty()) {
                SettingsGroupTitle(stringResource(R.string.settings_cards_unregistered))
                unregisteredCards.forEach { card ->
                    SettingsRegistryItemCard(
                        icon = MasroofIcons.cardPayment,
                        bank = card.bank,
                        title = registryLabel(card),
                        showBankLabel = false,
                        footer = {
                            CardOwnershipInlinePrompt(
                                enabled = !state.updating,
                                onConfirmOwned = { onConfirmOwned(card) },
                                onMarkExternal = { onMarkExternal(card) },
                            )
                            SettingsDeleteAction({ deleteActions.onRequest(StopTrackingTarget.Card(card)) }, !state.updating,
                                Modifier.padding(top = MasroofSpacing.cardInnerGap))
                        },
                    )
                }
            }

            if (referenceTab == "followed" && followedCards.isNotEmpty()) {

                val cards = bankTree?.let { primaryFirst(it.creditCards) + it.allDebitCards.map { card -> card to false } }
                    ?: primaryFirst(followedCards)
                cards.forEach { (card, nested) ->
                    SettingsRegistryCardRow(
                        card = card,
                        updating = state.updating,
                        onRequestStopTracking = onRequestStopTracking,
                        onRenameCard = onRenameCard,
                        onPickCardNetwork = onPickCardNetwork,
                        onRequestDelete = { deleteActions.onRequest(StopTrackingTarget.Card(it)) },
                        modifier = if (nested) Modifier.padding(start = MasroofSpacing.largeGap) else Modifier,
                    )
                }
            }

            if (referenceTab == "stopped" && stoppedCards.isNotEmpty()) {
                SettingsGroupTitle(stringResource(R.string.settings_cards_stopped))
                stoppedCards.forEach { card ->
                    SettingsRegistryItemCard(
                        icon = MasroofIcons.cardPayment,
                        bank = card.bank,
                        title = registryLabel(card),
                        showBankLabel = false,
                        endAction = {
                            SettingsStoppedItemActions({ onResumeTracking(card) },
                                { deleteActions.onRequest(StopTrackingTarget.Card(card)) }, !state.updating)
                        },
                    )
                }
            }

            state.error?.let {
                Text(
                    stringResource(R.string.settings_update_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            onAddManual?.let { action -> com.baraa.masroof.presentation.common.MasroofOutlinedButton(action,
                enabled = !state.updating, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_add_card_manual))
            } }
        }
    }
}

/**
 * Primary and standalone cards first (by last four), each followed by its supplementary cards.
 * The flag marks a supplementary card shown under its primary.
 */
internal fun primaryFirst(cards: List<ManagedCardUi>): List<Pair<ManagedCardUi, Boolean>> {
    val (supplementary, primary) = cards.partition { it.cardRole == CardRole.SUPPLEMENTARY }
    val placed = mutableSetOf<String>()
    val ordered = primary.sortedBy { it.last4 }.flatMap { parent ->
        listOf(parent to false) + supplementary.filter { it.parentCardLast4 == parent.last4 }.sortedBy { it.last4 }
            .onEach { placed += it.id }.map { it to true }
    }
    return ordered + supplementary.filter { it.id !in placed }.sortedBy { it.last4 }.map { it to false }
}

@Composable
internal fun SettingsRegistryCardRow(
    card: ManagedCardUi,
    updating: Boolean,
    onRequestStopTracking: (ManagedCardUi) -> Unit,
    onRenameCard: (ManagedCardUi) -> Unit,
    onPickCardNetwork: (ManagedCardUi) -> Unit,
    onRequestDelete: (ManagedCardUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = registryLabel(card)
    SettingsRegistryItemCard(
        icon = MasroofIcons.cardPayment,
        bank = card.bank,
        title = title,
        modifier = modifier,
        showBankLabel = false,
        onTitleClick = { onRenameCard(card) },
        headerAction = {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, enabled = !updating, modifier = Modifier.testTag("registry-more-actions")) {
                    Icon(MasroofIcons.more, stringResource(R.string.settings_card_more_actions, title),
                        Modifier.size(MasroofIconSizes.md))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_card_setup)) },
                        onClick = { menu = false; onPickCardNetwork(card) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.reference_stop_tracking)) },
                        onClick = { menu = false; onRequestStopTracking(card) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.registry_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onRequestDelete(card) }, modifier = Modifier.testTag("registry-delete-action"))
                }
            }
        },
        footer = {
            cardSubtitle(card)?.let { subtitle ->
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MasroofSpacing.inlineGap),
                )
            }
        },
    )
}

@Composable
private fun cardSubtitle(card: ManagedCardUi): String? {
    // An unnamed card's title already says Mada or Primary/Supplementary; do not repeat it here.
    val unnamed = card.displayName.isNullOrBlank()
    val titleShowsNetwork = unnamed && card.cardNetwork == CardNetwork.MADA
    val titleShowsRole = unnamed && !titleShowsNetwork && card.cardType != null && card.cardType != CardType.DEBIT &&
        (card.cardRole == CardRole.PRIMARY || card.cardRole == CardRole.SUPPLEMENTARY)
    val parts = buildList {
        card.cardNetwork?.takeIf { !titleShowsNetwork }?.let { add(cardNetworkLabel(it)) }
        card.cardRole?.takeIf { !titleShowsRole }?.let { add(cardRoleLabel(it)) }
        card.linkedAccountMaskedNumber?.let {
            add(stringResource(R.string.settings_linked_account_suffix, it.takeLast(4)))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
