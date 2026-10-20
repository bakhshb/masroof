package com.baraa.masroof.lab.beta.presentation

import android.os.Bundle
import com.baraa.masroof.lab.beta.application.notification.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.BetaIo
import com.baraa.masroof.lab.beta.application.BetaOnboardingStep
import com.baraa.masroof.lab.finance.asset.AssetKind
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import com.baraa.masroof.lab.finance.asset.OwnedAsset
import com.baraa.masroof.lab.finance.value.BankId
import com.baraa.masroof.lab.beta.application.BetaBankChoice
import com.baraa.masroof.lab.beta.BetaContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen Beta shell hosted by the lab module.
 *
 * It does not read the production composition root.
 */
class BetaActivity : ComponentActivity() {
    private var betaContainer: BetaContainer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // Destruction queues behind this creation even if the lifecycle cancels its result.
            val container = try { withContext(BetaIo.dispatcher) {
                BetaContainer(applicationContext).also { betaContainer = it }
            } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                recordStartupFailure("OPEN_STORAGE",error)
                setContent { com.baraa.masroof.ui.theme.MasroofTheme {
                    BetaShell(markerCount = 0, restoreMissing = false, showMaintenanceActions = false, showReturn = false,
                        editor = { BetaStartupFailure(onRetry = { recreate() }, onReturn = { finish() }) },
                        onReturn = { finish() }, onReset = {}, onBackup = {}, onRestore = {})
                } }
                return@launch
            }
            setContent {
            var betaTheme by remember { mutableStateOf("SYSTEM") }
            var betaLanguage by remember { mutableStateOf("SYSTEM") }
            val dark = when (betaTheme) { "DARK" -> true; "LIGHT" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            BetaLocaleProvider(betaLanguage) { com.baraa.masroof.ui.theme.MasroofTheme(darkTheme = dark) {
                val shellContainer = remember { container }
                DisposableEffect(shellContainer) {
                    com.baraa.masroof.lab.beta.application.sms.BetaLiveSms.resume(applicationContext)
                    onDispose { }
                }
                val scope = rememberBetaUiScope()
                val dashboardWorkflow = remember(shellContainer) {
                    com.baraa.masroof.lab.beta.application.BetaDashboardWorkflow({ query, periods ->
                        withContext(BetaIo.dispatcher) { shellContainer.dashboard(query, periods) }
                    })
                }
                val dashboardControls = remember(shellContainer) { com.baraa.masroof.lab.beta.application.betaDashboardControls(shellContainer) }
                val dashboardPreferences = remember(shellContainer) { com.baraa.masroof.lab.beta.application.BetaDashboardViewPreferencesWorkflow(applicationContext) }
                var markerCount by remember { mutableIntStateOf(0) }
                var restoreMissing by remember { mutableStateOf(false) }
                var banks by remember { mutableStateOf<List<BetaBankChoice>>(emptyList()) }
                var assets by remember { mutableStateOf<List<OwnedAsset>>(emptyList()) }
                var loaded by remember { mutableStateOf(false) }
                var automationFailed by remember { mutableStateOf(false) }
                var startState by remember { mutableStateOf<com.baraa.masroof.lab.beta.application.BetaStartState?>(null) }
                var onboarding by remember { mutableStateOf(BetaOnboardingStep.COMPLETE) }
                var hasMessages by remember { mutableStateOf(false) }
                var pendingReviewCount by remember { mutableIntStateOf(0) }
                var registryTracking by remember {mutableStateOf<List<com.baraa.masroof.lab.beta.application.BetaTrackedAsset>>(emptyList())}
                var registryReferences by remember {mutableStateOf<List<com.baraa.masroof.lab.beta.application.BetaOwnershipCandidate>>(emptyList())}
                var registryInitialTab by rememberSaveable {mutableStateOf("followed")}
                var registryTitle by remember {mutableStateOf<String?>(null)}
                var ownershipPending by remember { mutableIntStateOf(Int.MAX_VALUE) }
                var cardTasks by remember { mutableStateOf<List<com.baraa.masroof.lab.beta.application.BetaCardSetupTask>>(emptyList()) }
                var assetMetadata by remember { mutableStateOf<Map<com.baraa.masroof.lab.finance.asset.AssetId,com.baraa.masroof.lab.beta.application.BetaAssetMetadata>>(emptyMap()) }
                var historyBusy by remember { mutableStateOf(false) }
                var discoveryOpen by rememberSaveable { mutableStateOf(false) }
                var proposalId by rememberSaveable { mutableStateOf<String?>(null) }
                var proposalSuffix by rememberSaveable { mutableStateOf<String?>(null) }
                var proposalEvidence by rememberSaveable { mutableStateOf<String?>(null) }
                var proposalRevision by rememberSaveable { mutableStateOf<String?>(null) }
                var proposalKind by rememberSaveable { mutableStateOf<String?>(null) }
                var approvalOpen by rememberSaveable { mutableStateOf(false) }
                var approvalEvidenceId by rememberSaveable { mutableStateOf<String?>(null) }
                var reviewOpen by rememberSaveable { mutableStateOf(false) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                var developerToolsOpen by rememberSaveable { mutableStateOf(false) }
                var settingsCategory by rememberSaveable { mutableStateOf("HUB") }
                var ownershipEditSuffix by rememberSaveable { mutableStateOf("") }
                var initialAssetKind by rememberSaveable { mutableStateOf(AssetKind.ACCOUNT.name) }
                var dashboardPageTitle by remember {mutableStateOf<String?>(null)}
                var reviewDetailOpen by remember {mutableStateOf(false)}
                var dashboardDestination by rememberSaveable {mutableStateOf("OVERVIEW")}
                var dashboardTab by rememberSaveable { mutableStateOf("HOME") }
                var dashboardRequest by rememberSaveable { mutableIntStateOf(0) }
                var developerWorkspaceRequest by rememberSaveable {mutableIntStateOf(0)}
                var notificationsOpen by rememberSaveable {mutableStateOf(false)}
                var notificationState by remember {mutableStateOf<BetaNotificationCenterResult?>(null)}
                var notificationLoading by remember {mutableStateOf(false)}
                var notificationFailed by remember {mutableStateOf(false)}
                var notificationBusy by remember {mutableStateOf(false)}
                var notificationReload by remember {mutableIntStateOf(0)}
                var notificationPeriod by rememberSaveable {mutableStateOf("")}
                var notificationExcluded by rememberSaveable {mutableIntStateOf(0)}
                val notificationPermission=androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {notificationReload++}
                var historyOpen by rememberSaveable { mutableStateOf(false) }
                var editOwnershipConfirmed by rememberSaveable {mutableStateOf(false)}
                var editBank by rememberSaveable { mutableStateOf<String?>(null) }
                var editId by rememberSaveable { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(true) }
                var failed by remember { mutableStateOf(false) }
                suspend fun execute(action: () -> Boolean?): Boolean {
                    failed = false
                    automationFailed = false
                    var automaticProcessing = false
                    try {
                        val snapshot = withContext(BetaIo.dispatcher) {
                            val restored = action()
                            dashboardPreferences.refresh()
                            automaticProcessing = true
                            shellContainer.processAutomaticEvidence()
                            automaticProcessing = false
                            val inbox = shellContainer.evidenceInboxCounts()
                            BetaScreenSnapshot(shellContainer.markerCount(), shellContainer.bankChoices(), shellContainer.assets(), restored, shellContainer.settings().theme, shellContainer.startState(), shellContainer.onboardingStep(), inbox.total > 0, inbox.needsReview, if(shellContainer.onboardingStep()==BetaOnboardingStep.ASSETS) shellContainer.ownershipSnapshot().unresolvedCount else 0, shellContainer.settings().language, shellContainer.cardSetupTasks(),shellContainer.assetMetadata(),shellContainer.assetTrackingSnapshot(),if(settingsOpen && settingsCategory in setOf("ACCOUNTS","CARDS","LOANS")) shellContainer.ownershipSnapshot().candidates else emptyList())
                        }
                        markerCount = snapshot.markers
                        banks = snapshot.banks
                        assets = snapshot.assets
                        betaTheme = snapshot.theme
                        betaLanguage = snapshot.language
                        startState = snapshot.startState
                        onboarding = snapshot.onboarding
                        hasMessages = snapshot.hasMessages
                        pendingReviewCount = snapshot.pendingReviews
                        ownershipPending = snapshot.ownershipPending
                        cardTasks=snapshot.cardTasks
                        assetMetadata=snapshot.assetMetadata
                        registryTracking=snapshot.tracking;registryReferences=snapshot.references
                        if (snapshot.startState != com.baraa.masroof.lab.beta.application.BetaStartState.READY) {
                            developerToolsOpen = false; settingsOpen = false; historyOpen = false; reviewOpen = false
                            approvalOpen = false; discoveryOpen = false; editBank = null; editId = null
                        }
                        loaded = true
                        restoreMissing = snapshot.restored == false
                        return true
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        recordStartupFailure(if(automaticProcessing) "AUTOMATIC_PROCESSING" else "LOAD_STATE",error)
                        banks = emptyList()
                        automationFailed = automaticProcessing
                        failed = true
                        return false
                    } finally { busy = false }
                }
                fun perform(action: () -> Boolean?) {
                    if (busy) return
                    busy = true
                    scope.launch { execute(action) }
                }
                var maintenanceTools by rememberSaveable { mutableStateOf(false) }
                fun openRoute(route: String) {
                    if (busy || historyBusy) return
                    if(route=="SETTINGS") registryInitialTab="followed"
                    notificationsOpen=route=="NOTIFICATIONS"
                    historyOpen = route == "HISTORY"; reviewOpen = route == "REVIEW"; approvalOpen = route == "APPROVAL"
                    discoveryOpen = route == "DISCOVERY"; settingsOpen = route == "SETTINGS"; developerToolsOpen = route in setOf("DEVELOPER","DATA"); maintenanceTools=route=="DEVELOPER"
                    editBank = null; editId = null; proposalId = null; approvalEvidenceId = null
                    if (route == "HOME" || route == "TRANSACTIONS" || route == "CONFIGURATION") { dashboardTab = route; dashboardRequest++ }
                }
                fun resumeSetup() {
                    if (busy || historyBusy) return
                    openRoute("HOME")
                    settingsCategory = "HUB"
                    perform { shellContainer.resumeOnboarding(); null }
                }
                fun closeTask() {
                    notificationsOpen=false
                    historyOpen = false; discoveryOpen = false; approvalOpen = false; reviewOpen = false
                    perform { null }
                }
                fun openAsset(bank: BankId, kind: AssetKind) {
                    if (busy || historyBusy) return
                    editOwnershipConfirmed=false; editBank = bank.value; editId = null; proposalId = null; initialAssetKind = kind.name; ownershipEditSuffix = ""; failed = false
                }
                BetaLaunchedEffect(loaded,busy,startState,pendingReviewCount,assets,cardTasks,notificationReload,notificationPeriod,notificationExcluded,notificationsOpen) {
                    if(!loaded || busy || startState!=com.baraa.masroof.lab.beta.application.BetaStartState.READY) return@BetaLaunchedEffect
                    notificationLoading=true;notificationFailed=false
                    try {notificationState=withContext(BetaIo.dispatcher) {shellContainer.notifications(notificationExcluded,notificationPeriod)}}
                    catch(c: kotlinx.coroutines.CancellationException) {throw c}
                    catch(_: Exception) {notificationFailed=true}
                    finally {notificationLoading=false}
                }
                fun openNotification(item: BetaNotificationItem) {
                    if(busy || historyBusy || notificationBusy) return
                    notificationBusy=true;notificationFailed=false
                    scope.launch {
                        try {
                            withContext(BetaIo.dispatcher) {shellContainer.readNotification(item.id)}
                            notificationReload++
                            when(item.action) {
                                BetaNotificationAction.REQUEST_SMS_PERMISSION -> notificationPermission.launch(android.Manifest.permission.READ_SMS)
                                BetaNotificationAction.OPEN_APP_SETTINGS -> startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:$packageName")))
                                BetaNotificationAction.OPEN_REVIEW -> openRoute("REVIEW")
                                BetaNotificationAction.OPEN_SETTINGS_CARDS, BetaNotificationAction.OPEN_SETTINGS_ACCOUNTS -> {
                                    openRoute("SETTINGS");settingsCategory=if(item.action==BetaNotificationAction.OPEN_SETTINGS_CARDS) "CARDS" else "ACCOUNTS"
                                    registryInitialTab="unconfirmed";perform {null}
                                }
                                BetaNotificationAction.OPEN_SETTINGS_ABOUT -> {openRoute("SETTINGS");settingsCategory="ABOUT"}
                                BetaNotificationAction.DISMISS_RESCAN, BetaNotificationAction.MARK_READ_ONLY -> Unit
                            }
                        } catch(c: kotlinx.coroutines.CancellationException) {throw c}
                        catch(_: Exception) {notificationFailed=true}
                        finally {notificationBusy=false}
                    }
                }
                BetaLaunchedEffect(Unit) { execute { null } }
                BetaLaunchedEffect(shellContainer) {
                    com.baraa.masroof.lab.beta.application.sms.BetaLiveSms.changes.collect {
                        while(busy || historyBusy) kotlinx.coroutines.delay(50)
                        perform {null}; dashboardRequest++
                    }
                }
                androidx.compose.runtime.DisposableEffect(Unit) {
                    val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
                        if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME && loaded && !busy && !historyBusy) { perform {null}; dashboardRequest++ }
                    }
                    this@BetaActivity.lifecycle.addObserver(observer)
                    onDispose {this@BetaActivity.lifecycle.removeObserver(observer)}
                }
                if (!loaded || automationFailed || startState != com.baraa.masroof.lab.beta.application.BetaStartState.READY) {
                    BetaShell(
                        markerCount = 0, restoreMissing = false, showMaintenanceActions = false, showReturn = !failed,
                        returnEnabled = !busy || !loaded, onReturn = { finish() }, onReset = {}, onBackup = {}, onRestore = {},
                        editor = {
                            if (!loaded || automationFailed) {
                                if (failed) BetaStartupFailure(onRetry = { perform { null } }, onReturn = { finish() },processing=automationFailed)
                                else com.baraa.masroof.lab.beta.presentation.approved.ApprovedText(androidx.compose.ui.res.stringResource(com.baraa.masroof.lab.beta.R.string.beta_start_loading))
                            } else BetaStartPanel(checkNotNull(startState), busy, failed,
                                onContinue = { perform { if (startState == com.baraa.masroof.lab.beta.application.BetaStartState.NEW) shellContainer.beginOnboarding() else shellContainer.acknowledgeStart(); null } },
                                onFresh = { perform { shellContainer.startFresh(); null } })
                        },
                    )
                } else BetaShell(
                    showMaintenanceActions = false,
                    showReturn = false,
                    notificationCount=notificationState?.unreadCount ?: 0,onOpenNotifications={if(!busy && !historyBusy && !notificationBusy) notificationsOpen=true},developerWorkspaceRequest=developerWorkspaceRequest,
                    secondaryTitle=when {
                        notificationsOpen -> androidx.compose.ui.res.stringResource(R.string.beta_approved_notification_center_title)
                        settingsOpen -> registryTitle ?: androidx.compose.ui.res.stringResource(when(settingsCategory) {"APP"->R.string.beta_approved_reference_preferences_title;"ACCOUNTS"->R.string.beta_home_accounts;"CARDS"->R.string.beta_home_cards;"LOANS"->R.string.beta_home_loans;"COMMITMENTS"->R.string.beta_dash_commitments;"ABOUT"->R.string.beta_complete_about;else->R.string.beta_nav_settings})
                        reviewOpen -> androidx.compose.ui.res.stringResource(if(reviewDetailOpen) R.string.beta_complete_review_details else R.string.beta_nav_review)
                        historyOpen -> androidx.compose.ui.res.stringResource(R.string.beta_history_title)
                        developerToolsOpen -> androidx.compose.ui.res.stringResource(R.string.beta_parity_data_settings)
                        onboarding!=BetaOnboardingStep.COMPLETE -> androidx.compose.ui.res.stringResource(R.string.beta_setup_continue)
                        else -> dashboardPageTitle
                    },
                    onNavigateBack={onBackPressedDispatcher.onBackPressed()},
                    setupFooter = if(onboarding==BetaOnboardingStep.ASSETS && editBank==null && !settingsOpen && !developerToolsOpen) ({
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth().padding(com.baraa.masroof.ui.theme.MasroofSpacing.screenHorizontal),verticalArrangement=androidx.compose.foundation.layout.Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofSpacing.smallGap)) {
                            if(ownershipPending in 1 until Int.MAX_VALUE) com.baraa.masroof.lab.beta.presentation.approved.ApprovedText(androidx.compose.ui.res.stringResource(com.baraa.masroof.lab.beta.R.string.beta_parity_ownership_pending,ownershipPending),style=androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            androidx.compose.material3.Button(enabled=!busy && !historyBusy && ownershipPending==0,onClick={
                                discoveryOpen=false; perform {shellContainer.advanceOnboarding(BetaOnboardingStep.ASSETS);null}
                            },modifier=androidx.compose.ui.Modifier.fillMaxWidth().testTag("ownership-continue")) {
                                com.baraa.masroof.lab.beta.presentation.approved.ApprovedText(androidx.compose.ui.res.stringResource(com.baraa.masroof.lab.beta.R.string.beta_parity_finalize))
                            }
                        }
                    }) else null,
                    selectedHomeTab = when { notificationsOpen -> "NONE"; settingsOpen -> "SETTINGS"; reviewOpen -> "REVIEW"; dashboardDestination in setOf("ACCOUNTS","CARDS","LOANS","COMMITMENTS","MERCHANTS","DAILY","ANALYSIS","CONFIGURATION") -> "NONE"; else -> dashboardTab },
                    onOpenHome = { openRoute("HOME") },
                    onOpenTransactions = { openRoute("TRANSACTIONS") },
                    dashboardOwnsScroll=true,editorOwnsScroll=reviewOpen && loaded,
                    dashboard = if (onboarding != BetaOnboardingStep.COMPLETE) null else ({ BetaDashboardPanel(dashboardWorkflow,ownsScroll=true,onDestinationChanged={dashboardDestination=it},transactionExtras={id,changed -> BetaPostedTransactionActions(shellContainer,id,{historyBusy=it},changed)}, showInternalHeader=false,onNotificationFacts={period,count -> notificationPeriod=period;notificationExcluded=count}, onPageTitle={dashboardPageTitle=it}, enabled = !busy && !historyBusy, controls = dashboardControls, preferences = dashboardPreferences, pendingCardCount=cardTasks.size,onCards={openRoute("SETTINGS");settingsCategory="CARDS";perform {null}},
                        requestedRoute = when(dashboardTab) {"TRANSACTIONS" -> "TRANSACTIONS";"CONFIGURATION" -> "CONFIGURATION";else -> "OVERVIEW"}, routeRequest = dashboardRequest, onPrimaryTabChanged = { dashboardTab = it }, onBusyChanged = { historyBusy = it }, onImport = { openRoute("HISTORY") }, onSetup = ::resumeSetup,onManageAssets={kind -> openRoute("SETTINGS");settingsCategory=when(kind) {AssetKind.ACCOUNT->"ACCOUNTS";AssetKind.LOAN->"LOANS";else->"CARDS"};perform {null}}) }),
                    onOpenDiscovery = { openRoute("DISCOVERY") },
                    onOpenHistory = { openRoute("HISTORY") },
                    onOpenApproval = { openRoute("APPROVAL") },
                    onOpenReview = { approvalEvidenceId=null;openRoute("REVIEW") },
                    onOpenSettings = { settingsCategory="HUB";openRoute("SETTINGS") },
                    onOpenDeveloperTools = { openRoute("DEVELOPER") },
                    onOpenFinancialSettings = { openRoute("CONFIGURATION") },
                    markerCount = markerCount,
                    assets = assets,
                    returnEnabled = !historyBusy && (editBank == null || !busy),
                    onAddAsset = { bank -> openAsset(bank, AssetKind.ACCOUNT) },
                    onEditAsset = { asset -> if (!busy) { editBank = asset.id.bank.value; editId = asset.id.localId; proposalId = null; failed = false } },
                    editorKey=when {
                        notificationsOpen -> "NOTIFICATIONS"
                        editBank!=null -> null
                        settingsOpen -> "SETTINGS"
                        reviewOpen -> "REVIEW"
                        developerToolsOpen -> "DATA"
                        discoveryOpen || onboarding==BetaOnboardingStep.ASSETS -> "OWNERSHIP"
                        historyOpen || onboarding==BetaOnboardingStep.IMPORT -> "IMPORT"
                        else -> null
                    },
                    editor = if(notificationsOpen) ({
                        androidx.activity.compose.BackHandler {if(!busy && !historyBusy && !notificationBusy) notificationsOpen=false}
                        com.baraa.masroof.lab.beta.presentation.approved.ApprovedNotificationCenterContent(
                            notificationState?.items.orEmpty(),notificationState?.unreadCount ?: 0,notificationLoading,notificationFailed,
                            onRetry={notificationReload++},onOpen=::openNotification)
                    }) else if (settingsOpen && editBank == null && loaded) ({ BetaSettingsHubPanel(settingsCategory, { settingsCategory = it;registryInitialTab="followed";if(it in setOf("ACCOUNTS","CARDS","LOANS")) perform {null} }, banks, assets, dashboardControls, busy || historyBusy,
                        failed=failed, onRename={asset,name,revision,completed -> perform {
                            shellContainer.saveManualAsset(com.baraa.masroof.lab.beta.application.ManualAssetDraft(
                                asset.id,asset.kind,asset.last4.orEmpty(),asset.parentId,asset.creditCardRole,true,true,asset,
                                metadata=(assetMetadata[asset.id] ?: com.baraa.masroof.lab.beta.application.BetaAssetMetadata()).copy(
                                    displayName=name.trim().takeIf {it.isNotEmpty()},revision=revision)))
                            completed();null
                        }},
                        theme=betaTheme,language=betaLanguage,cardTasks=cardTasks,metadata=assetMetadata,
                        tracking=registryTracking,references=registryReferences,onRegistryTitle={registryTitle=it},initialRegistryTab=registryInitialTab,
                        onTracking={target,owned,completed -> perform {shellContainer.setAssetTracked(target,owned);completed();null}},
                        onOwnership={candidate,owned,selected -> perform {shellContainer.decideOwnership(candidate,owned,selected);null}},
                        onAccountType={asset,type,revision,completed -> perform {
                            shellContainer.saveManualAsset(com.baraa.masroof.lab.beta.application.ManualAssetDraft(asset.id,asset.kind,asset.last4.orEmpty(),asset.parentId,asset.creditCardRole,true,true,asset,
                                metadata=(assetMetadata[asset.id] ?: com.baraa.masroof.lab.beta.application.BetaAssetMetadata()).copy(accountType=type,revision=revision)))
                            completed();null
                        }},
                        onConfigureTask={task -> editOwnershipConfirmed=true;editBank=task.bank.value;editId=task.assetId?.localId;ownershipEditSuffix=task.suffix;initialAssetKind=AssetKind.DEBIT_CARD.name;proposalId=null;failed=false},
                        onTheme={value -> perform {shellContainer.setTheme(value);null}},
                        onLanguage={value -> perform {shellContainer.setLanguage(value);null}},
                        onData={openRoute("DATA")},
                        onEdit = { asset -> editBank = asset.id.bank.value; editId = asset.id.localId; proposalId = null; failed = false },
                        onBusyChanged = { historyBusy = it }, onClose = { settingsOpen = false },
                        onSetup = ::resumeSetup, onReset = { perform { shellContainer.reset(); null } },onHistory={openRoute("HISTORY")},onDeveloper={openRoute("HOME");developerWorkspaceRequest++}) })
                    else if (developerToolsOpen && loaded) ({ BetaSettingsPanel(shellContainer, showMaintenance=maintenanceTools, onClose = { developerToolsOpen = false }, onBusyChanged = { historyBusy = it }, onChanged = { perform { null } }) }) else if (approvalOpen && loaded) ({ BetaFinancialApprovalPanel(shellContainer, initialEvidenceId = approvalEvidenceId, onClose = ::closeTask, onBusyChanged = { historyBusy = it }) }) else if (reviewOpen && loaded) ({ BetaReviewInboxPanel(shellContainer, onClose = ::closeTask, onBusyChanged = { historyBusy = it }, initialEvidenceId=approvalEvidenceId, onDetailChanged={reviewDetailOpen=it}, onChanged={perform {null};dashboardRequest++}) }) else if ((discoveryOpen || onboarding == BetaOnboardingStep.ASSETS) && editBank == null && loaded) ({ BetaDiscoveryPanel(shellContainer, assets,
                        onClose = ::closeTask, onBusyChanged = { historyBusy = it }, onChanged = { perform { null } }, showContinue = onboarding!=BetaOnboardingStep.ASSETS, parentBusy = busy,
                        onContinue = if (onboarding == BetaOnboardingStep.ASSETS) ({
                            discoveryOpen = false
                            perform { shellContainer.advanceOnboarding(BetaOnboardingStep.ASSETS); null }
                        }) else null,
                        onManage = { openRoute("SETTINGS") },
                        onConfigure = { candidate ->
                            editOwnershipConfirmed=candidate.status==com.baraa.masroof.lab.beta.application.BetaOwnershipStatus.OWNED
                            editBank = candidate.reference.bank.value; editId = candidate.asset?.id?.localId; proposalId = null
                            initialAssetKind = (candidate.asset?.kind ?: AssetKind.DEBIT_CARD).name
                            ownershipEditSuffix = candidate.reference.suffix; failed = false
                        }) }) else if ((historyOpen || onboarding==BetaOnboardingStep.IMPORT) && loaded) ({ BetaHistoryPanel(shellContainer, onClose = {if(onboarding==BetaOnboardingStep.IMPORT) finish() else closeTask()}, onBusyChanged = { historyBusy = it }, onFinancialReview = { id -> openRoute("REVIEW"); approvalEvidenceId = id }, onConfirmAccounts = { openRoute("DISCOVERY") }, onContinueSetup = if (onboarding == BetaOnboardingStep.IMPORT) ({
                            historyOpen = false
                            perform { shellContainer.advanceOnboarding(BetaOnboardingStep.IMPORT); null }
                        }) else null) }) else if (editBank != null && loaded) ({
                        key(editBank, editId) {
                            val existing = editId?.let { id -> assets.singleOrNull { it.id.bank.value == editBank && it.id.localId == id } }
                            if (editId != null && existing == null) {
                                com.baraa.masroof.lab.beta.presentation.approved.ApprovedText(androidx.compose.ui.res.stringResource(com.baraa.masroof.lab.beta.R.string.beta_asset_missing))
                                androidx.compose.material3.OutlinedButton(onClick = { editBank = null; editId = null }) {
                                    com.baraa.masroof.lab.beta.presentation.approved.ApprovedText(androidx.compose.ui.res.stringResource(com.baraa.masroof.lab.beta.R.string.beta_asset_cancel))
                                }
                            } else BetaAssetEditor(BankId(checkNotNull(editBank)), existing, assets, busy, failed,
                                initialKind = AssetKind.valueOf(initialAssetKind), initialSuffix = ownershipEditSuffix,
                                allowedKinds=AssetKind.valueOf(initialAssetKind).let {if(it in setOf(AssetKind.DEBIT_CARD,AssetKind.CREDIT_CARD)) setOf(AssetKind.DEBIT_CARD,AssetKind.CREDIT_CARD) else setOf(it)},
                                ownershipConfirmed=editOwnershipConfirmed, registryMetadata=assetMetadata, metadata=existing?.let {assetMetadata[it.id]} ?: com.baraa.masroof.lab.beta.application.BetaAssetMetadata(),
                                onCancel = { editBank = null; editId = null; proposalId = null; failed = false },
                                proposal = proposalId?.let { com.baraa.masroof.lab.beta.application.BetaAssetProposal(
                                    com.baraa.masroof.lab.finance.asset.AssetId(com.baraa.masroof.lab.finance.value.Environment.BETA, BankId(checkNotNull(editBank)), it),
                                    com.baraa.masroof.lab.finance.asset.AssetKind.valueOf(checkNotNull(proposalKind)), checkNotNull(proposalSuffix), checkNotNull(proposalEvidence), checkNotNull(proposalRevision)) },
                                onSave = { draft ->
                                    if (!busy) {
                                        busy = true
                                        scope.launch {
                                            if (execute {
                                                val proposal = proposalId?.let { com.baraa.masroof.lab.beta.application.BetaAssetProposal(draft.id,
                                                    com.baraa.masroof.lab.finance.asset.AssetKind.valueOf(checkNotNull(proposalKind)), checkNotNull(proposalSuffix), checkNotNull(proposalEvidence), checkNotNull(proposalRevision)) }
                                                if (proposal != null) shellContainer.confirmProposal(proposal, draft) else shellContainer.saveManualAsset(draft)
                                                null }) {
                                                editBank = null
                                                editId = null
                                            }
                                        }
                                    }
                                })
                        }
                    }) else if (onboarding != BetaOnboardingStep.COMPLETE) ({
                        BetaOnboardingPanel(onboarding, banks, busy || historyBusy, failed, hasMessages, assets.size, pendingReviewCount,
                            onChooseBank = { bank -> perform { shellContainer.setBankEnabled(bank, true); shellContainer.advanceOnboarding(BetaOnboardingStep.BANK); null } },
                            onNext = { perform { shellContainer.advanceOnboarding(onboarding); null } },
                            onHistory = { openRoute("HISTORY") }, onDiscovery = { openRoute("DISCOVERY") },
                            onManage = { openRoute("SETTINGS") }, onApproval = { openRoute("REVIEW") })
                    }) else null,
                    banks = banks,
                    busy = busy || historyBusy || notificationBusy,
                    failed = failed,
                    onRetry = { perform { null } },
                    onSetBankEnabled = { bank, enabled ->
                        perform { shellContainer.setBankEnabled(bank, enabled); null }
                    },
                    restoreMissing = restoreMissing,
                    onReturn = { if (!historyBusy && (editBank == null || !busy)) finish() },
                    onReset = { perform { shellContainer.reset(); null } },
                    onBackup = { perform { shellContainer.backup(); null } },
                    onRestore = { perform { shellContainer.restore() } },
                )
            }
            }
            }
        }
    }

    private fun recordStartupFailure(stage: String,error: Exception) {
        // Do not log exception messages/causes: they may contain SQL values or captured SMS.
        val frames=error.stackTrace.filter {it.className.startsWith("com.baraa.masroof.lab.")}.take(4)
            .joinToString(";") {"${it.className}.${it.methodName}:${it.lineNumber}"}
        android.util.Log.e("MasroofBetaStartup","stage=$stage type=${error.javaClass.simpleName} frames=$frames retry=AVAILABLE")
    }

    override fun onDestroy() {
        // Never wait for Room/comparison on the main thread. New Activity creation uses
        // the same FIFO queue, so its database work cannot overtake this close.
        BetaIo.execute { betaContainer?.close(); betaContainer = null }
        super.onDestroy()
    }
}

private data class BetaScreenSnapshot(
    val markers: Int,
    val banks: List<BetaBankChoice>,
    val assets: List<OwnedAsset>,
    val restored: Boolean?,
    val theme: String,
    val startState: com.baraa.masroof.lab.beta.application.BetaStartState,
    val onboarding: BetaOnboardingStep,
    val hasMessages: Boolean,
    val pendingReviews: Int,
    val ownershipPending: Int,
    val language: String,
    val cardTasks: List<com.baraa.masroof.lab.beta.application.BetaCardSetupTask>,
    val assetMetadata: Map<com.baraa.masroof.lab.finance.asset.AssetId,com.baraa.masroof.lab.beta.application.BetaAssetMetadata>,
    val tracking: List<com.baraa.masroof.lab.beta.application.BetaTrackedAsset>,
    val references: List<com.baraa.masroof.lab.beta.application.BetaOwnershipCandidate>,
)
