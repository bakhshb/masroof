package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Matches production ownership rows; the data source is one list for the whole import. */
@Composable
internal fun BetaDiscoveryPanel(container: BetaContainer, assets: List<OwnedAsset>,
    onClose: () -> Unit, onBusyChanged: (Boolean) -> Unit = {}, onChanged: () -> Unit = {},
    onContinue: (() -> Unit)? = null, showContinue: Boolean = true, parentBusy: Boolean = false, onManage: (() -> Unit)? = null, onConfigure: (BetaOwnershipCandidate) -> Unit = {}) {
    val scope = rememberBetaUiScope()
    var snapshot by remember { mutableStateOf<BetaOwnershipSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    suspend fun refresh() { snapshot = withContext(BetaIo.dispatcher) { container.ownershipSnapshot() } }
    BetaLaunchedEffect(assets) {
        try { refresh() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    SideEffect { onBusyChanged(busy) }
    DisposableEffect(Unit) { onDispose { onBusyChanged(false) } }
    BackHandler { if (!busy && !parentBusy) onClose() }
    val locked = busy || parentBusy
    fun decide(candidate: BetaOwnershipCandidate, owned: Boolean?, selected: AssetId?) {
        if (locked) return
        busy = true; failed = false
        scope.launch {
            try {
                withContext(BetaIo.dispatcher) { if (owned == null) container.deferOwnership(candidate) else container.decideOwnership(candidate, owned, selected) }
                refresh()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                failed = true
                try { refresh() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep failure visible; never replace unreadable data. */ }
            }
            finally { busy = false; onChanged() }
        }
    }
    Column(Modifier.testTag("ownership-list"), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        if(onContinue!=null) Text(stringResource(R.string.beta_onboarding_progress,5,6),style=MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.beta_ownership_title), style = MaterialTheme.typography.titleLarge,
            modifier = if (onContinue != null) Modifier.testTag("onboarding-ASSETS") else Modifier)
        if (snapshot == null && !failed) CircularProgressIndicator()
        snapshot?.let { current ->
            if (current.unresolvedCount > 0) Text(stringResource(R.string.beta_ownership_must_resolve))
            if (current.configurationCount > 0) Text(stringResource(R.string.beta_ownership_configuration_required))
            if (current.candidates.isEmpty()) Text(stringResource(R.string.beta_ownership_empty))
            BetaOwnershipKind.entries.forEach { kind ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (kind == BetaOwnershipKind.ACCOUNT) MasroofOutlineIcons.ArrowDownLeft else MasroofOutlineIcons.CreditCard,
                        null, Modifier.size(MasroofIconSizes.sm), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(MasroofSpacing.sectionHeaderGap))
                    Text(stringResource(if (kind == BetaOwnershipKind.ACCOUNT) R.string.beta_ownership_accounts else R.string.beta_ownership_cards),
                        style = MaterialTheme.typography.titleMedium)
                }
                current.candidates.filter { it.reference.kind == kind }.forEach { candidate ->
                    key(candidate.reference) {
                        OwnershipCandidateCard(candidate, locked, { owned, selected -> decide(candidate, owned, selected) }, { onConfigure(candidate) })
                    }
                }
            }
            if (onContinue != null && showContinue) OwnershipButton(onContinue, stringResource(R.string.beta_ownership_continue), MasroofOutlineIcons.CircleCheck,
                !locked && current.unresolvedCount == 0, Modifier.fillMaxWidth().testTag("ownership-continue"))
        }
        onManage?.let { manage -> OutlinedButton(manage, enabled = !locked, modifier = Modifier.testTag("onboarding-manage")) {
            Text(stringResource(R.string.beta_setup_manual_action))
        } }
        if (failed) Text(stringResource(R.string.beta_action_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ownership-error"))
        OutlinedButton(onClick = onClose, enabled = !locked, modifier = Modifier.testTag("proposal-close")) { Text(stringResource(R.string.beta_asset_cancel)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OwnershipCandidateCard(candidate: BetaOwnershipCandidate, busy: Boolean,
    onDecide: (Boolean?, AssetId?) -> Unit, onConfigure: () -> Unit) {
    val ref = candidate.reference
    val tag = "ownership-${ref.bank.value}-${ref.kind}-${ref.suffix}"
    var selected by remember(candidate.token) { mutableStateOf(candidate.asset?.id) }
    val statusColor = when (candidate.status) {
        BetaOwnershipStatus.OWNED -> MaterialTheme.colorScheme.primary
        BetaOwnershipStatus.EXTERNAL, BetaOwnershipStatus.REVIEW -> MaterialTheme.colorScheme.onSurfaceVariant
        BetaOwnershipStatus.UNKNOWN -> MaterialTheme.colorScheme.error
    }
    MasroofCard(Modifier.testTag(tag)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ref.kind == BetaOwnershipKind.ACCOUNT) MasroofOutlineIcons.ArrowDownLeft else MasroofOutlineIcons.CreditCard,
                    null, Modifier.size(MasroofSizes.size22), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(MasroofSpacing.sectionHeaderGap))
                Text(stringResource(bankName(ref.bank)))
            }
            Text(stringResource(if (ref.kind == BetaOwnershipKind.ACCOUNT) R.string.beta_ownership_account_suffix else R.string.beta_ownership_card_suffix, ref.suffix))
            if (candidate.conflictingKinds) Text(stringResource(R.string.beta_ownership_kind_conflict), color = MaterialTheme.colorScheme.error)
            if (candidate.conflictingKinds) OutlinedButton(onClick = { onDecide(null, null) }, enabled = !busy,
                modifier = Modifier.testTag("$tag-defer")) { Text(stringResource(R.string.beta_ownership_defer)) }
            if (candidate.choices.size > 1) {
                Text(stringResource(R.string.beta_ownership_collision))
                candidate.choices.forEachIndexed { index, asset ->
                    BetaChoice(selected = selected == asset.id, enabled = !busy, onClick = { selected = asset.id },
                        modifier = Modifier.testTag("$tag-choice-$index"), label = {
                            Text(stringResource(R.string.beta_asset_summary, stringResource(assetKindLabel(asset.kind)), asset.last4.orEmpty(), index + 1))
                        })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                OwnershipButton({ onDecide(true, selected) }, stringResource(if (ref.kind == BetaOwnershipKind.ACCOUNT)
                    R.string.beta_ownership_my_account else R.string.beta_ownership_my_card), MasroofOutlineIcons.CircleCheck,
                    !busy && !candidate.conflictingKinds && (candidate.choices.size <= 1 || selected != null), Modifier.testTag("$tag-owned"))
                OwnershipButton({ onDecide(false, null) }, stringResource(R.string.beta_ownership_not_mine), MasroofOutlineIcons.TriangleAlert,
                    !busy, Modifier.testTag("$tag-external"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(when (candidate.status) {
                    BetaOwnershipStatus.OWNED -> MasroofOutlineIcons.CircleCheck
                    BetaOwnershipStatus.EXTERNAL, BetaOwnershipStatus.REVIEW -> MasroofOutlineIcons.TriangleAlert
                    BetaOwnershipStatus.UNKNOWN -> MasroofOutlineIcons.CircleAlert
                }, null, Modifier.size(MasroofIconSizes.sm), tint = statusColor)
                Spacer(Modifier.width(MasroofSpacing.smallGap))
                Text(stringResource(when (candidate.status) {
                    BetaOwnershipStatus.OWNED -> R.string.beta_ownership_status_owned
                    BetaOwnershipStatus.EXTERNAL -> R.string.beta_ownership_status_external
                    BetaOwnershipStatus.UNKNOWN -> R.string.beta_ownership_status_unknown
                    BetaOwnershipStatus.REVIEW -> R.string.beta_ownership_status_review
                }), color = statusColor)
            }
            if (candidate.status == BetaOwnershipStatus.OWNED && ref.kind == BetaOwnershipKind.CARD && (candidate.asset?.parentId == null || (candidate.asset.kind == AssetKind.CREDIT_CARD && candidate.asset.creditCardRole == null)))
                OutlinedButton(onClick = onConfigure, enabled = !busy, modifier = Modifier.testTag("$tag-configure")) {
                    Text(stringResource(R.string.beta_ownership_configure_card))
                }
        }
    }
}

@Composable
private fun OwnershipButton(onClick: () -> Unit, text: String, icon: ImageVector, enabled: Boolean, modifier: Modifier = Modifier) {
    Button(onClick, modifier, enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
        Icon(icon, null, Modifier.size(MasroofIconSizes.moneyRowLeading))
        Spacer(Modifier.width(MasroofSpacing.sectionHeaderGap))
        Text(text)
    }
}
