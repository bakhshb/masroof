
package com.baraa.masroof.presentation.review

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.baraa.masroof.presentation.common.MasroofChoice
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.baraa.masroof.application.review.ExternalTransferDirection
import com.baraa.masroof.R
import com.baraa.masroof.presentation.common.toUiLabelRes
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.presentation.common.formatCardLast4
import com.baraa.masroof.presentation.common.CardOwnershipInlinePrompt
import com.baraa.masroof.presentation.common.IconLabelRow
import com.baraa.masroof.presentation.common.IconTextButton
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.common.MasroofSectionHeader
import com.baraa.masroof.ui.theme.MasroofIconSizes
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.presentation.common.MasroofAmountRole
import com.baraa.masroof.presentation.common.MasroofAmountText
import com.baraa.masroof.presentation.review.ReviewReasonLabels

@Composable
fun ReviewRoute(viewModel: ReviewViewModel) {
    val state by viewModel.uiState.collectAsState()
    // Every Review visit shares this ViewModel, so each visit saves the message it shows (and whether it is teaching it)
    // and reopens it when it returns. Only the id is saved; the message text is read again from the database.
    var openReviewId by rememberSaveable { mutableStateOf<String?>(null) }
    var teaching by rememberSaveable { mutableStateOf(false) }
    var returning by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        // A new visit keeps what navigation left in the ViewModel: the list, or a message opened with the visit.
        if (returning && viewModel.uiState.value.selectedDetail?.id != openReviewId) {
            viewModel.closeDetail()
            openReviewId?.let(viewModel::openDetail)
        }
        returning = true
        viewModel.refresh()
        var shown: String? = null
        viewModel.uiState.map { it.selectedDetail?.id }.distinctUntilChanged().collect { selected ->
            // Follow every message the ViewModel opens. Forget the visit's message only when it is closed, not while it loads.
            // Teaching belongs to one message, so another message opens on its detail.
            if (selected != null) { if (selected != openReviewId) teaching = false; openReviewId = selected }
            else if (shown == openReviewId) { openReviewId = null; teaching = false }
            shown = selected
        }
    }
    // Recorded before the load ends, so a rotation mid-load keeps the message the user tapped. A hidden older
    // message is closed first, so Back while the new one loads acts on what is on screen.
    fun open(id: String) {
        teaching = false
        openReviewId = id
        if (viewModel.uiState.value.selectedDetail?.id != id) viewModel.closeDetail()
        viewModel.openDetail(id)
    }
    val patterns = viewModel.messagePatternsWorkflow
    val detail = state.selectedDetail?.takeIf { it.id == openReviewId }
    if (teaching && detail != null && patterns != null) {
        com.baraa.masroof.presentation.patterns.MessagePatternsScreen(patterns,
            source = com.baraa.masroof.application.patterns.PatternTeachingSource(detail.bankId, requireNotNull(detail.sender), detail.body, detail.id),
            onOpenReview = ::open, onBack = {
                teaching = false
                viewModel.refreshAfterPatternTeaching()
            })
        return
    }
    if (detail != null) {
        ReviewDetailScreen(
            detail = detail,
            resolving = state.resolving,
            message = state.message,
            error = state.error,
            actionErrorDetail = state.actionErrorDetail,
            onBack = viewModel::closeDetail,
            onResolveType = viewModel::resolveAsFinancialType,
            onResolveExternal = viewModel::resolveAsExternalTransfer,
            onResolvePair = viewModel::resolveSelfTransferPair,
            onDismissNonFinancial = viewModel::resolveAsIgnored,
            onSelectBank = viewModel::selectBank,
            onConfirmOwnershipCardOwned = viewModel::confirmOwnershipCardOwned,
            onMarkOwnershipCardExternal = viewModel::markOwnershipCardExternal,
            onRestoreIgnored = viewModel::restoreIgnoredMessage,
            onTeachPattern = if (patterns != null) ({ teaching = true }) else null,
        )
    } else {
        ReviewListScreen(
            state = state,
            onRefresh = viewModel::refresh,
            onOpen = ::open,
            onDismissAllInformational = viewModel::dismissAllInformational,
            onListModeChange = viewModel::setListMode,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewListScreen(
    state: ReviewUiState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onDismissAllInformational: () -> Unit,
    onListModeChange: (ReviewListMode) -> Unit,
) {
    MasroofSecondaryScaffold(
        title = stringResource(R.string.review_title),
        onBack = null,
        backContentDescription = null,
    ) { contentModifier ->
        Column(modifier = contentModifier.fillMaxSize()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = MasroofSpacing.sectionGap),
                horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap),
                verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap),
            ) {
                MasroofChoice(
                    selected = state.listMode == ReviewListMode.PENDING,
                    onClick = { onListModeChange(ReviewListMode.PENDING) },
                    label = stringResource(R.string.review_tab_pending),
                    enabled = !state.loading || state.items.isNotEmpty(),
                )
                MasroofChoice(
                    selected = state.listMode == ReviewListMode.IGNORED,
                    onClick = { onListModeChange(ReviewListMode.IGNORED) },
                    label = stringResource(R.string.review_tab_ignored),
                    enabled = !state.loading || state.items.isNotEmpty(),
                )
            }
            when {
            state.loading && state.items.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.error == ReviewError.LOAD_FAILED && state.items.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = MasroofIcons.error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.size(MasroofSpacing.sectionHeaderGap))
                        Text(
                            stringResource(R.string.review_load_error),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(MasroofSpacing.sectionGap))
                    IconTextButton(
                        onClick = onRefresh,
                        icon = MasroofIcons.retry,
                        text = stringResource(R.string.dashboard_retry),
                    )
                }
            }
            state.items.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = MasroofIcons.success,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MasroofIconSizes.hero),
                    )
                    Spacer(Modifier.height(MasroofSpacing.sectionGap))
                    Text(
                        stringResource(
                            if (state.listMode == ReviewListMode.IGNORED) {
                                R.string.review_ignored_empty
                            } else {
                                R.string.review_empty
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.listMode == ReviewListMode.PENDING) Text(
                        stringResource(R.string.review_empty_body),
                        modifier = Modifier.fillMaxWidth().padding(top = MasroofSpacing.inlineGap),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap),
                ) {
                    item {
                        MasroofSectionHeader(
                            title = stringResource(
                                if (state.listMode == ReviewListMode.IGNORED) {
                                    R.string.review_ignored_count
                                } else {
                                    R.string.review_count
                                },
                                state.items.size,
                            ),
                            icon = MasroofIcons.reviewQueue,
                            modifier = Modifier.padding(vertical = MasroofSpacing.sectionHeaderGap),
                        )
                    }
                    if (state.listMode == ReviewListMode.PENDING && state.informationalDismissCount > 0) {
                        item {
                            IconTextButton(
                                onClick = onDismissAllInformational,
                                enabled = !state.resolving,
                                icon = MasroofIcons.warning,
                                text = stringResource(
                                    R.string.review_dismiss_all_informational,
                                    state.informationalDismissCount,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    items(state.items, key = { it.id }) { item ->
                        if (item.ignored) ReviewIgnoredRow(item = item, onClick = { onOpen(item.id) })
                        else ReviewListCard(item = item, onClick = { onOpen(item.id) })
                    }
                    item { Spacer(Modifier.height(MasroofSpacing.screenVertical)) }
                }
            }
            }
        }
    }
}

@Composable
internal fun ReviewListCard(item: ReviewListItemUi, onClick: () -> Unit) {
    MasroofCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(item.titleRes?.let { stringResource(it) } ?: item.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            com.baraa.masroof.presentation.common.MasroofBadge(stringResource(item.kindLabelRes),
                if (item.ignored) com.baraa.masroof.presentation.common.MasroofCardAccent.Inflow
                else com.baraa.masroof.presentation.common.MasroofCardAccent.Liability)
        }
        Spacer(Modifier.height(MasroofSpacing.sectionGap))
        item.amountLabel?.let { com.baraa.masroof.presentation.common.MasroofReferenceMoney(it) }
        Text(item.dateLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val reason = ReviewReasonLabels.labelRes(item.reasonLabel)?.let { stringResource(it) } ?: item.reasonLabel
        com.baraa.masroof.presentation.common.MasroofGuidanceLabel(stringResource(item.kindLabelRes), reason,
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(MasroofSpacing.sectionGap))
        com.baraa.masroof.presentation.common.MasroofButton(onClick, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reference_review_evidence))
        }
    }
}

/** One compact tappable row per ignored message; it opens the same evidence and restore options. */
@Composable
internal fun ReviewIgnoredRow(item: ReviewListItemUi, onClick: () -> Unit) {
    val sender = item.sender ?: item.bankId?.let { com.baraa.masroof.presentation.settings.LocalSettingsBankNames.current[it] }
    MasroofCard(modifier = Modifier.testTag("review-ignored-row").clickable(onClickLabel = stringResource(R.string.reference_review_evidence),
        role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(sender ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(item.dateLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                com.baraa.masroof.presentation.common.SmsBodyText(item.smsBody, Modifier.fillMaxWidth(), maxLines = 1,
                    style = MaterialTheme.typography.bodySmall)
                item.ignoredReasonRes?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl)
                MasroofIcons.periodPrevious else MasroofIcons.periodNext, null, Modifier.size(com.baraa.masroof.ui.theme.MasroofReference.icon),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewDetailScreen(
    detail: ReviewDetailUi,
    resolving: Boolean,
    message: ReviewMessage?,
    error: ReviewError?,
    actionErrorDetail: String?,
    onBack: () -> Unit,
    onResolveType: (FinancialTransactionType) -> Unit,
    onResolveExternal: (String, ExternalTransferDirection) -> Unit,
    onResolvePair: (String) -> Unit,
    onDismissNonFinancial: () -> Unit,
    onSelectBank: (String) -> Unit,
    onConfirmOwnershipCardOwned: () -> Unit,
    onMarkOwnershipCardExternal: () -> Unit,
    onRestoreIgnored: (FinancialTransactionType?) -> Unit,
    onTeachPattern: (() -> Unit)? = null,
) {
    var showTypeSheet by androidx.compose.runtime.remember(detail.id) { androidx.compose.runtime.mutableStateOf(false) }
    var showWhy by androidx.compose.runtime.remember(detail.id) { androidx.compose.runtime.mutableStateOf(false) }
    val unfamiliar = !detail.showRestoreActions && detail.messageFamily in listOf(null, MessageFamily.UNKNOWN) &&
        detail.reasonLabels.any { it in setOf("unknown_message_family", "unsupported_bank_message_format", "parse_review_required", "parse_partial") }
    if (showTypeSheet) {
        val reasonLabel = actionErrorDetail?.let { ReviewReasonLabels.labelRes(it) }
        ReviewTypeSheet(resolving, { if (!resolving) showTypeSheet = false }, onResolveType,
            error = if (error == ReviewError.ACTION_FAILED) stringResource(reasonLabel ?: R.string.review_action_failed) else null)
    }
    MasroofSecondaryScaffold(
        title = stringResource(R.string.review_detail_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.review_back),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap),
        ) {
            if (unfamiliar) {
                Text(stringResource(R.string.teaching_review_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.teaching_review_pending), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                detail.reasonLabels.firstOrNull()?.let { reason ->
                    ReviewReasonLabels.labelRes(reason)?.let { label ->
                        Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            MasroofCard {
                if (!unfamiliar) Text(stringResource(detail.kindLabelRes), style = MaterialTheme.typography.titleMedium)
                detail.amountLabel?.let { MasroofAmountText(it, MasroofAmountRole.Hero) }
                detail.sender?.let { IconLabelRow(MasroofIcons.sender, stringResource(R.string.review_sender, it)) }
                IconLabelRow(MasroofIcons.calendar, stringResource(R.string.review_date, detail.dateLabel))
                com.baraa.masroof.presentation.common.SmsBodyText(detail.body)
            }
            detail.resolvedAtLabel?.let {
                IconLabelRow(
                    icon = MasroofIcons.success,
                    label = stringResource(R.string.review_ignored_at, it),
                )
            }
            if (detail.showRestoreActions) {
                MasroofCard {
                    com.baraa.masroof.presentation.common.MasroofGuidanceLabel(label = stringResource(R.string.review_restore_actions_title), message = stringResource(R.string.review_restore_hint))
                }
                MasroofSectionHeader(
                    title = stringResource(R.string.review_restore_actions_title),
                    icon = MasroofIcons.reviewQueue,
                )
                IconTextButton(
                    onClick = { onRestoreIgnored(null) },
                    enabled = !resolving,
                    icon = MasroofIcons.success,
                    text = stringResource(R.string.review_action_restore),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(REVIEW_RESTORE_AS_IS_TEST_TAG),
                )
                REVIEW_FINANCIAL_TYPE_ACTIONS.forEach { type ->
                    IconTextButton(
                        onClick = { onRestoreIgnored(type) },
                        enabled = !resolving,
                        icon = MasroofIcons.transactionType(type),
                        text = stringResource(R.string.review_action_restore_as, stringResource(type.toUiLabelRes())),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            detail.merchant?.let {
                IconLabelRow(icon = MasroofIcons.merchant, label = stringResource(R.string.review_merchant, it))
            }
            detail.counterparty?.let {
                IconLabelRow(icon = MasroofIcons.counterparty, label = stringResource(R.string.review_counterparty, it))
            }

            if (detail.showOwnershipActions && detail.ownershipCard?.last4 != null) {
                MasroofSectionHeader(
                    title = stringResource(R.string.review_ownership_prompt_title),
                    icon = MasroofIcons.ownership,
                )
                Text(
                    stringResource(
                        R.string.review_ownership_prompt_body,
                        formatCardLast4(detail.ownershipCard.last4),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                CardOwnershipInlinePrompt(
                    enabled = !resolving,
                    onConfirmOwned = onConfirmOwnershipCardOwned,
                    onMarkExternal = onMarkOwnershipCardExternal,
                )
            }

            if (onTeachPattern != null && !detail.sender.isNullOrBlank() && detail.body.isNotBlank()) {
                com.baraa.masroof.presentation.common.MasroofButton(
                    onClick = onTeachPattern, enabled = !resolving,
                    modifier = Modifier.fillMaxWidth().testTag("review-teach-pattern"),
                ) { Text(stringResource(R.string.teaching_review_action)) }
            }
            if (detail.bankChoices.isNotEmpty()) {
                MasroofSectionHeader(
                    title = stringResource(R.string.review_bank_choice_title),
                    icon = MasroofIcons.moneyMovement,
                )
                detail.bankChoices.forEach { choice ->
                    val bankName = choice.labelRes?.let { stringResource(it) } ?: choice.bankId
                    IconTextButton(
                        onClick = { onSelectBank(choice.bankId) },
                        enabled = !resolving,
                        icon = MasroofIcons.moneyMovement,
                        text = stringResource(R.string.review_parse_with_bank, bankName),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (detail.showFinancialTypeActions) {
                com.baraa.masroof.presentation.common.MasroofOutlinedButton(
                    onClick = { showTypeSheet = true }, enabled = !resolving,
                    modifier = Modifier.fillMaxWidth().testTag("review-correct-type"),
                ) { Text(stringResource(R.string.teaching_correct_type)) }
            }
            if (detail.showDismissNonFinancialAction) {
                com.baraa.masroof.presentation.common.MasroofTextButton(
                    onClick = onDismissNonFinancial, enabled = !resolving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.teaching_not_financial)) }
            }
            com.baraa.masroof.presentation.common.MasroofTextButton(
                onClick = { showWhy = !showWhy }, modifier = Modifier.testTag("review-why"),
            ) { Text(stringResource(R.string.teaching_review_why)) }
            if (showWhy) {
                detail.reasonLabels.forEach { reason ->
                    val label = ReviewReasonLabels.labelRes(reason)
                    Text(label?.let { stringResource(it) } ?: reason)
                }
                com.baraa.masroof.presentation.interpretation.InterpretationProvenanceCard(detail.provenance)
            }
            val externalDirection = when (detail.messageFamily) {
                MessageFamily.TRANSFER_IN -> ExternalTransferDirection.INCOMING
                MessageFamily.TRANSFER_OUT -> ExternalTransferDirection.OUTGOING
                else -> null
            }
            if (detail.showExternalTransferAction && externalDirection != null) {
                val externalIcon = when {
                    detail.transferBetweenOwnAccounts -> MasroofIcons.selfTransfer
                    detail.messageFamily == MessageFamily.TRANSFER_IN -> MasroofIcons.externalIn
                    detail.messageFamily == MessageFamily.TRANSFER_OUT -> MasroofIcons.externalOut
                    else -> MasroofIcons.selfTransfer
                }
                IconTextButton(
                    onClick = { onResolveExternal(detail.id, externalDirection) },
                    enabled = !resolving,
                    icon = externalIcon,
                    text = stringResource(
                        when {
                            detail.transferBetweenOwnAccounts -> R.string.txn_type_self_transfer
                            detail.messageFamily == MessageFamily.TRANSFER_IN -> R.string.review_action_external_transfer_in
                            detail.messageFamily == MessageFamily.TRANSFER_OUT -> R.string.review_action_external_transfer_out
                            else -> R.string.review_action_external_transfer
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (detail.showIncomingIncomeAction) {
                IconTextButton(
                    onClick = { onResolveType(FinancialTransactionType.INCOME) },
                    enabled = !resolving,
                    icon = MasroofIcons.income,
                    text = stringResource(R.string.review_action_incoming_income),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (detail.pairCandidates.isNotEmpty()) {
                MasroofSectionHeader(
                    title = stringResource(R.string.review_pair_candidates),
                    icon = MasroofIcons.pairMatch,
                )
                detail.pairCandidates.forEach { candidate ->
                    IconTextButton(
                        onClick = { onResolvePair(candidate.id) },
                        enabled = !resolving,
                        icon = MasroofIcons.pairMatch,
                        text = stringResource(R.string.review_pair_with, candidate.title),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            when (message) {
                ReviewMessage.RESOLVED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MasroofIcons.success,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(MasroofSpacing.compactCardLabelTop))
                    Text(
                        stringResource(R.string.review_resolved),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                ReviewMessage.STILL_NEEDS_REVIEW -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MasroofIcons.warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(MasroofSpacing.compactCardLabelTop))
                    Text(
                        stringResource(R.string.review_still_pending),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ReviewMessage.RESTORED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MasroofIcons.success,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(MasroofSpacing.compactCardLabelTop))
                    Text(
                        stringResource(R.string.review_restored),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                null -> Unit
            }
            if (error == ReviewError.ACTION_FAILED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MasroofIcons.error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(MasroofSpacing.compactCardLabelTop))
                    Text(stringResource(R.string.review_action_failed), color = MaterialTheme.colorScheme.error)
                }
                actionErrorDetail?.let { reason ->
                    val detailRes = restoreFailureLabelRes(reason) ?: ReviewReasonLabels.labelRes(reason)
                    Text(
                        detailRes?.let { stringResource(it) } ?: reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (resolving) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

private fun restoreFailureLabelRes(reason: String): Int? =
    when (reason) {
        "not_ignored", "review_not_found" -> R.string.settings_restore_not_ignored
        "reconcile_failed" -> R.string.settings_restore_reconcile_failed
        "review_clear_failed", "rollback_delete_failed", "rollback_review_failed" ->
            R.string.settings_restore_failed
        else -> null
    }
