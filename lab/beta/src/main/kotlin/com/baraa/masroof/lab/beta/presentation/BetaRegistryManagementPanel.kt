package com.baraa.masroof.lab.beta.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.presentation.approved.*
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.BankId
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*

/** Source SettingsBankAccounts/Cards/Loans screen content; decisions use full lab registry identities. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BetaRegistryManagementPanel(category: String,banks: List<BetaBankChoice>,tracking: List<BetaTrackedAsset>,
    references: List<BetaOwnershipCandidate>,metadata: Map<AssetId,BetaAssetMetadata>,tasks: List<BetaCardSetupTask>,busy: Boolean,initialTab: String,
    onCategory: (String)->Unit,onTitle: (String?)->Unit,onRename: (OwnedAsset)->Unit,onEdit: (OwnedAsset)->Unit,
    onPickAccountType: (OwnedAsset)->Unit,onTracking: (BetaTrackedAsset,Boolean,()->Unit)->Unit,
    onOwnership: (BetaOwnershipCandidate,Boolean,AssetId?)->Unit,onConfigure: (BetaCardSetupTask)->Unit) {
    var selectedBank by rememberSaveable {mutableStateOf<String?>(null)}
    var bankPage by rememberSaveable {mutableStateOf(false)}
    var tab by rememberSaveable(category,initialTab) {mutableStateOf(initialTab)}
    var stopTarget by remember {mutableStateOf<BetaTrackedAsset?>(null)}
    val kinds=when(category) {"ACCOUNTS"->setOf(AssetKind.ACCOUNT);"LOANS"->setOf(AssetKind.LOAN);else->setOf(AssetKind.CREDIT_CARD,AssetKind.DEBIT_CARD)}
    val refKind=if(category=="ACCOUNTS") BetaOwnershipKind.ACCOUNT else BetaOwnershipKind.CARD
    val candidates=if(category=="LOANS") emptyList() else references.filter {it.reference.kind==refKind}
    val eligible=banks.filter {bank -> tracking.any {it.asset.id.bank==bank.bankId && it.asset.kind in kinds} || candidates.any {it.reference.bank==bank.bankId} || (category=="CARDS" && tasks.any {it.bank==bank.bankId})}
    val bank=banks.singleOrNull {it.bankId.value==selectedBank} ?: eligible.singleOrNull() ?: banks.singleOrNull()
    val title=stringResource(if(bankPage) R.string.beta_registry_reference_bank_registered else when(category) {
        "ACCOUNTS"->R.string.beta_registry_reference_manage_accounts;"LOANS"->R.string.beta_registry_reference_manage_loans;else->R.string.beta_registry_reference_manage_cards})
    SideEffect {onTitle(title)}
    DisposableEffect(Unit) {onDispose {onTitle(null)}}
    BackHandler(enabled=bankPage || (selectedBank!=null && eligible.size!=1 && banks.size!=1)) {
        if(!busy) {if(bankPage) bankPage=false else selectedBank=null}
    }
    if(bank==null) {
        banks.forEach {choice -> BetaManagementRow(stringResource(bankName(choice.bankId)),"",MasroofOutlineIcons.Landmark,!busy,
            {selectedBank=choice.bankId.value},Modifier.testTag("registry-bank-${choice.bankId.value}"))}
        return
    }
    val rows=tracking.filter {it.asset.id.bank==bank.bankId && it.asset.kind in kinds}
    val stoppedReferences=candidates.filter {it.reference.bank==bank.bankId && it.status==BetaOwnershipStatus.EXTERNAL && it.choices.isEmpty()}
    val unknown=candidates.filter {it.reference.bank==bank.bankId && it.status in setOf(BetaOwnershipStatus.UNKNOWN,BetaOwnershipStatus.REVIEW)}
    if(bankPage) {
        MasroofCard {
            Text(stringResource(bankName(bank.bankId)),style=MaterialTheme.typography.titleMedium)
            listOf("ACCOUNTS" to R.string.beta_registry_settings_bank_accounts_title,"CARDS" to R.string.beta_registry_settings_bank_cards_title,"LOANS" to R.string.beta_registry_settings_bank_loans_title).forEachIndexed {index,(next,label) ->
                BetaManagementRow(stringResource(label),registryCategorySubtitle(next,bank.bankId,tracking,references),if(next=="CARDS") MasroofOutlineIcons.CreditCard else MasroofOutlineIcons.Landmark,!busy,
                    {bankPage=false;selectedBank=bank.bankId.value;onCategory(next)},divider=index!=2)
            }
        }
        return
    }
    ApprovedRegistryBankHeader(stringResource(bankName(bank.bankId)),stringResource(when(category) {
        "ACCOUNTS"->R.string.beta_registry_settings_accounts_hint;"LOANS"->R.string.beta_registry_settings_loans_hint;else->R.string.beta_registry_settings_cards_hint}),!busy) {selectedBank=bank.bankId.value;bankPage=true}
    Row(horizontalArrangement=Arrangement.spacedBy(MasroofReference.shortcutGap)) {
        listOf("followed" to R.string.beta_registry_reference_followed,"unconfirmed" to R.string.beta_registry_reference_unconfirmed,"stopped" to R.string.beta_registry_reference_stopped).forEach {(key,label) ->
            ApprovedChoice(stringResource(label),tab==key,{tab=key},Modifier.testTag("registry-tab-$key"),enabled=!busy)
        }
    }
    if(!busy && rows.isEmpty() && unknown.isEmpty() && tasks.none {category=="CARDS" && it.bank==bank.bankId})
        Text(stringResource(when(category) {"ACCOUNTS"->R.string.beta_registry_settings_accounts_empty;"LOANS"->R.string.beta_registry_settings_loans_empty;else->R.string.beta_registry_settings_cards_empty}),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    val visible=rows.filter {when(tab) {"stopped"->it.status==BetaOwnershipStatus.EXTERNAL;"unconfirmed"->it.status==BetaOwnershipStatus.UNKNOWN;else->it.status==BetaOwnershipStatus.OWNED}}
    if(tab!="followed" && (visible.isNotEmpty() || (tab=="unconfirmed" && unknown.isNotEmpty()) || (tab=="stopped" && stoppedReferences.isNotEmpty()))) ApprovedSectionTitle(stringResource(when {
        tab=="stopped" && category=="ACCOUNTS"->R.string.beta_registry_settings_accounts_stopped
        tab=="stopped" && category=="LOANS"->R.string.beta_registry_settings_loans_stopped
        tab=="stopped"->R.string.beta_registry_settings_cards_stopped
        category=="ACCOUNTS"->R.string.beta_registry_settings_accounts_unregistered
        category=="LOANS"->R.string.beta_registry_settings_loans_unregistered
        else->R.string.beta_registry_settings_cards_unregistered}))
    if(tab=="unconfirmed") {
        unknown.forEach {candidate ->
            ApprovedRegistryItemCard(if(category=="CARDS") MasroofOutlineIcons.CreditCard else MasroofOutlineIcons.Landmark,
                stringResource(bankName(bank.bankId)),"•• "+candidate.reference.suffix,showBankLabel=false,
                footer={
                    var selected by remember(candidate.token) {mutableStateOf(candidate.asset?.id)}
                    if(candidate.choices.size>1) candidate.choices.forEach {choice ->
                        BetaChoice(selected==choice.id,{selected=choice.id},enabled=!busy) {Text(approvedRegistryDisplayLabel(metadata[choice.id]?.displayName,stringResource(assetKindLabel(choice.kind)),choice.last4.orEmpty()))}
                    }
                    if(candidate.conflictingKinds) Text(stringResource(R.string.beta_ownership_kind_conflict),color=MaterialTheme.colorScheme.error)
                    ApprovedRegistryOwnershipPrompt(category=="CARDS",!busy && !candidate.conflictingKinds && (candidate.choices.size<=1 || selected!=null),!busy,
                        {onOwnership(candidate,true,selected)},{onOwnership(candidate,false,null)})
                })
        }
        if(category=="CARDS") tasks.filter {it.bank==bank.bankId && it.assetId==null && unknown.none {c->c.reference.suffix==it.suffix}}.forEach {task ->
            BetaManagementRow("•• "+task.suffix,stringResource(R.string.beta_parity_card_missing),MasroofOutlineIcons.CreditCard,!busy,{onConfigure(task)},Modifier.testTag("configure-card-${task.suffix}"))
        }
    }
    if(tab=="stopped") stoppedReferences.forEach {candidate ->
        ApprovedRegistryItemCard(if(category=="CARDS") MasroofOutlineIcons.CreditCard else MasroofOutlineIcons.Landmark,
            stringResource(bankName(bank.bankId)),"•• "+candidate.reference.suffix,showBankLabel=false,
            endAction={ApprovedResumeTracking(!busy,Modifier.testTag("registry-resume-reference-${candidate.reference.kind}-${candidate.reference.suffix}")) {onOwnership(candidate,true,null)}})
    }
    visible.sortedWith(compareBy<BetaTrackedAsset> {if(it.asset.kind==AssetKind.DEBIT_CARD) 1 else 0}.thenBy {it.asset.id.localId}).forEach {item ->
        val asset=item.asset
        val label=registryLabel(asset,metadata[asset.id])
        ApprovedRegistryItemCard(if(category=="CARDS") MasroofOutlineIcons.CreditCard else MasroofOutlineIcons.Landmark,
            stringResource(bankName(bank.bankId)),label,Modifier.testTag("manage-asset-${asset.id.bank.value}-${asset.id.localId}"),
            showBankLabel=category=="LOANS",accent=if(category=="LOANS") MasroofCardAccent.Liability else if(category=="CARDS") MasroofCardAccent.Credit else MasroofCardAccent.Account,
            onTitleClick=if(!busy && tab=="followed" && category=="CARDS") ({onRename(asset)}) else null,
            endAction=if(tab=="stopped") ({ApprovedResumeTracking(!busy,Modifier.testTag("registry-resume-${asset.id.bank.value}-${asset.id.localId}")) {onTracking(item,true,{})}})
                else if(category=="LOANS" && tab=="followed") ({ApprovedManagementAction(stringResource(R.string.beta_registry_reference_stop_tracking),{stopTarget=item},Modifier.testTag("registry-stop-${asset.id.bank.value}-${asset.id.localId}"),enabled=!busy,warning=true)}) else null,
            footer=if(tab=="followed" && category!="LOANS") ({
                if(category=="ACCOUNTS") {
                    Text(registryAccountType(metadata[asset.id]?.accountType),Modifier.padding(top=MasroofSpacing.sectionGap),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    tracking.filter {it.asset.kind==AssetKind.DEBIT_CARD && it.asset.parentId==asset.id && it.status==BetaOwnershipStatus.OWNED}.forEach {card ->
                        Text(stringResource(R.string.beta_registry_dashboard_account_linked_mada_label,registryLabel(card.asset,metadata[card.asset.id])),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                } else registryCardSubtitle(asset,metadata,tracking)?.let {Text(it,Modifier.padding(top=MasroofSpacing.inlineGap),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                FlowRow(Modifier.fillMaxWidth().padding(top=MasroofSpacing.sectionGap),horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.smallGap),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.smallGap)) {
                    if(category=="ACCOUNTS") ApprovedManagementAction(stringResource(R.string.beta_approved_settings_action_rename),{onRename(asset)},enabled=!busy)
                    ApprovedManagementAction(stringResource(if(category=="CARDS") R.string.beta_approved_settings_card_setup else R.string.beta_registry_settings_account_type_title),
                        {if(category=="CARDS") onEdit(asset) else onPickAccountType(asset)},Modifier.testTag("registry-configure-${asset.id.bank.value}-${asset.id.localId}"),enabled=!busy)
                    ApprovedManagementAction(stringResource(R.string.beta_registry_reference_stop_tracking),{stopTarget=item},Modifier.testTag("registry-stop-${asset.id.bank.value}-${asset.id.localId}"),enabled=!busy,warning=true)
                }
            }) else if(tab=="unconfirmed") ({
                ApprovedRegistryOwnershipPrompt(category=="CARDS",!busy,!busy,
                    {onTracking(item,true,{})},{onTracking(item,false,{})},
                    titleRes=if(category=="LOANS") R.string.beta_registry_settings_loans_unregistered else null)
            }) else null)
    }
    stopTarget?.let {target ->
        AlertDialog(onDismissRequest={if(!busy) stopTarget=null},icon={Icon(MasroofOutlineIcons.TriangleAlert,null,tint=MaterialTheme.colorScheme.error)},
            title={Text(stringResource(when(category) {"ACCOUNTS"->R.string.beta_registry_settings_stop_account_confirm_title;"LOANS"->R.string.beta_registry_settings_stop_loan_confirm_title;else->R.string.beta_registry_settings_stop_confirm_title}))},
            text={Text(stringResource(when(category) {"ACCOUNTS"->R.string.beta_registry_settings_stop_account_confirm_body;"LOANS"->R.string.beta_registry_settings_stop_loan_confirm_body;else->R.string.beta_registry_settings_stop_confirm_body},if(category=="LOANS") registryLabel(target.asset,metadata[target.asset.id]) else target.asset.last4.orEmpty()))},
            confirmButton={TextButton(enabled=!busy,onClick={onTracking(target,false,{stopTarget=null})},modifier=Modifier.testTag("registry-stop-confirm")) {Text(stringResource(R.string.beta_registry_settings_stop_confirm_action))}},
            dismissButton={TextButton(enabled=!busy,onClick={stopTarget=null},modifier=Modifier.testTag("registry-stop-cancel")) {Text(stringResource(R.string.beta_registry_settings_cancel))}})
    }
}

@Composable
internal fun ApprovedRegistryBankHeader(bankLabel: String,hint: String,enabled: Boolean,onOpen: ()->Unit) {
    MasroofCard(Modifier.clickable(enabled=enabled,onClick=onOpen).testTag("registry-bank-header")) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            Surface(shape=MasroofShapes.small,color=MasroofThemeExtras.extendedColors.accountSoft) {
                Box(Modifier.size(MasroofReference.iconBox),contentAlignment=Alignment.Center) {Icon(MasroofOutlineIcons.Landmark,null,Modifier.size(MasroofReference.icon),tint=MasroofThemeExtras.extendedColors.account)}
            }
            ApprovedGuidanceLabel(bankLabel,hint,style=MaterialTheme.typography.labelLarge,contentColor=MaterialTheme.colorScheme.onSurface)
        }
    }
}
@Composable private fun ApprovedResumeTracking(enabled: Boolean,modifier: Modifier,onClick: ()->Unit) {
    OutlinedButton(onClick,modifier,enabled=enabled,colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.primary)) {
        Icon(MasroofOutlineIcons.CircleCheck,null,Modifier.size(MasroofSpacing.screenHorizontal));Spacer(Modifier.width(MasroofSpacing.inlineGap))
        Text(stringResource(R.string.beta_registry_settings_action_resume_short),style=MaterialTheme.typography.labelLarge)
    }
}
@Composable internal fun registryAccountType(type: String?): String=stringResource(when(type) {
    "SAVINGS"->R.string.beta_registry_settings_account_type_savings;"WALLET"->R.string.beta_registry_settings_account_type_wallet
    "CURRENT",null->R.string.beta_registry_settings_account_type_current;else->R.string.beta_registry_account_type_other})
@Composable private fun registryLabel(asset: OwnedAsset,metadata: BetaAssetMetadata?): String =
    if(asset.kind==AssetKind.LOAN) metadata?.displayName ?: stringResource(when(metadata?.loanType) {"AUTO"->R.string.beta_registry_settings_loan_type_auto;"MORTGAGE"->R.string.beta_registry_settings_loan_type_mortgage;else->R.string.beta_registry_settings_loan_type_personal})
    else approvedRegistryDisplayLabel(metadata?.displayName,stringResource(assetKindLabel(asset.kind)),asset.last4.orEmpty())
@Composable private fun registryCardSubtitle(asset: OwnedAsset,metadata: Map<AssetId,BetaAssetMetadata>,tracking: List<BetaTrackedAsset>): String? {
    val parts=buildList {
        metadata[asset.id]?.network?.let {add(stringResource(when(it) {"VISA"->R.string.beta_registry_card_network_visa;"MASTERCARD"->R.string.beta_registry_card_network_mastercard;"AMEX"->R.string.beta_registry_card_network_amex;"MADA"->R.string.beta_registry_card_network_mada;else->R.string.beta_registry_card_network_unknown}))}
        asset.creditCardRole?.let {add(stringResource(when(it) {CreditCardRole.PRIMARY->R.string.beta_registry_settings_card_role_primary;CreditCardRole.SUPPLEMENTARY->R.string.beta_registry_settings_card_role_additional;CreditCardRole.STANDALONE->R.string.beta_registry_settings_card_role_standalone}))}
        if(asset.kind==AssetKind.DEBIT_CARD) tracking.singleOrNull {it.asset.id==asset.parentId}?.asset?.last4?.let {add(stringResource(R.string.beta_registry_settings_linked_account_suffix,it))}
    }
    return parts.takeIf {it.isNotEmpty()}?.joinToString(" · ")
}

/** Source CardOwnershipInlinePrompt / AccountOwnershipInlinePrompt, without review-only headings. */
@Composable private fun ApprovedRegistryOwnershipPrompt(card: Boolean,mineEnabled: Boolean,enabled: Boolean,onMine: ()->Unit,onExternal: ()->Unit,titleRes: Int?=null) {
    val gap=if(card) MasroofSpacing.listItemGap else MasroofSpacing.cardInnerGap
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(gap)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(MasroofOutlineIcons.TriangleAlert,null,Modifier.size(if(card) MasroofIconSizes.sm else MasroofSpacing.screenHorizontal),tint=MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(if(card) MasroofSpacing.compactCardLabelTop else MasroofSpacing.smallGap))
            Text(stringResource(titleRes ?: if(card) R.string.beta_registry_ownership_prompt_needs_confirm else R.string.beta_registry_settings_accounts_unregistered),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(gap)) {
            ApprovedIconTextButton(onMine,stringResource(if(card) R.string.beta_registry_onboarding_is_mine_card else R.string.beta_registry_onboarding_is_mine_account),enabled=mineEnabled,icon=MasroofOutlineIcons.CircleCheck)
            ApprovedIconTextButton(onExternal,stringResource(R.string.beta_registry_ownership_action_not_mine),enabled=enabled,icon=MasroofOutlineIcons.TriangleAlert)
        }
    }
}

/** Source bank category counts; no financial values are calculated by the UI. */
@Composable private fun registryCategorySubtitle(category: String,bank: BankId,tracking: List<BetaTrackedAsset>,references: List<BetaOwnershipCandidate>): String {
    val kinds=when(category) {"ACCOUNTS"->setOf(AssetKind.ACCOUNT);"CARDS"->setOf(AssetKind.CREDIT_CARD,AssetKind.DEBIT_CARD);else->setOf(AssetKind.LOAN)}
    val rows=tracking.filter {it.asset.id.bank==bank && it.asset.kind in kinds}
    val followed=rows.count {it.status==BetaOwnershipStatus.OWNED}
    val stopped=rows.count {it.status==BetaOwnershipStatus.EXTERNAL}
    val empty=stringResource(when(category) {"ACCOUNTS"->R.string.beta_registry_settings_accounts_empty;"CARDS"->R.string.beta_registry_settings_cards_empty;else->R.string.beta_registry_settings_loans_empty})
    if(category=="LOANS") return if(rows.isEmpty()) empty else stringResource(R.string.beta_registry_settings_bank_summary_loans,rows.size)
    val kind=if(category=="ACCOUNTS") BetaOwnershipKind.ACCOUNT else BetaOwnershipKind.CARD
    val unconfirmed=references.count {it.reference.bank==bank && it.reference.kind==kind && it.status in setOf(BetaOwnershipStatus.UNKNOWN,BetaOwnershipStatus.REVIEW)}
    val cards=category=="CARDS"
    return when {
        followed+stopped+unconfirmed==0->empty
        unconfirmed>0->stringResource(if(cards) R.string.beta_registry_settings_hub_cards_subtitle else R.string.beta_registry_settings_hub_accounts_subtitle,followed,unconfirmed)+
            if(stopped>0) stringResource(R.string.beta_registry_settings_bank_category_stopped_suffix,stopped) else ""
        followed>0 && stopped>0->stringResource(if(cards) R.string.beta_registry_settings_hub_cards_subtitle_followed_stopped else R.string.beta_registry_settings_hub_accounts_subtitle_followed_stopped,followed,stopped)
        stopped>0->stringResource(if(cards) R.string.beta_registry_settings_hub_cards_subtitle_stopped_only else R.string.beta_registry_settings_hub_accounts_subtitle_stopped_only,stopped)
        else->stringResource(if(cards) R.string.beta_registry_settings_hub_cards_subtitle_followed_only else R.string.beta_registry_settings_hub_accounts_subtitle_followed_only,followed)
    }
}
