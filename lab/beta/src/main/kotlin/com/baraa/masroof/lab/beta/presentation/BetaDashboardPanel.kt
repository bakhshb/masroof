package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.dashboard.producer.DashboardProduction
import com.baraa.masroof.lab.finance.dashboard.producer.DashboardSection
import com.baraa.masroof.lab.finance.value.Money
import kotlinx.coroutines.CancellationException
import java.time.YearMonth

/** Only displays read models. No financial sums, attribution, conversion or writes. */
@Composable
fun BetaDashboardPanel(workflow: BetaDashboardWorkflow, enabled: Boolean = true,
    controls: BetaDashboardControls = UnavailableBetaDashboardControls,
    ownsScroll: Boolean = false,
    onDestinationChanged: (String)->Unit = {},
    preferences: BetaDashboardViewPreferencesWorkflow? = null, requestedRoute: String? = null, routeRequest: Int = 0, refreshRequest: Int = 0, onPrimaryTabChanged: (String) -> Unit = {}, onBusyChanged: (Boolean) -> Unit = {}, onImport: () -> Unit = {}, onSetup: () -> Unit = onImport, pendingCardCount: Int = 0, onCards: () -> Unit = {}, onPageTitle: (String?) -> Unit = {},onListShare: (BetaListShare?)->Unit = {}, showInternalHeader: Boolean = true, onManageAssets: (com.baraa.masroof.lab.finance.asset.AssetKind)->Unit = {},onNotificationFacts: (String,Int)->Unit = {_,_->}, onOpenCommitment: (String)->Unit = {}, transactionExtras: (@Composable (TransactionFacts,(Boolean)->Unit)->Unit)? = null) {
    var controlsBusy by remember { mutableStateOf(false) }
    var homeLayoutOpen by rememberSaveable { mutableStateOf(false) }
    val pageStates=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var selectedMerchantKey by rememberSaveable {mutableStateOf<String?>(null)}
    var dailyOrigin by rememberSaveable {mutableStateOf("OVERVIEW")}
    var selectedTransaction by rememberSaveable { mutableStateOf<String?>(null) }
    var monthText by rememberSaveable { mutableStateOf(workflow.currentMonth().toString()) }
    var route by rememberSaveable { mutableStateOf(requestedRoute ?: "OVERVIEW") }
    var lastRouteRequest by rememberSaveable { mutableIntStateOf(routeRequest) }
    var selectedBank by rememberSaveable { mutableStateOf<String?>(null) }
    var commitmentDetailExitsToHome by rememberSaveable {mutableStateOf(false)}
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousTransaction by remember {mutableStateOf(selectedTransaction)}
    LaunchedEffect(selectedTransaction) {
        val previous=previousTransaction
        if(previous!=null && previous!=selectedTransaction) pageStates.removeState("transaction:$previous")
        previousTransaction=selectedTransaction
    }
    var retry by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<DashboardProduction?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var activeWorkflow by remember(workflow) { mutableStateOf(workflow) }
    var appliedPeriodDay by rememberSaveable { mutableIntStateOf(workflow.periodStartDay) }
    val interactive = enabled && !controlsBusy && !loading && !failed
    BetaLaunchedEffect(route) { if(route=="ANALYSIS") {route="MERCHANTS";return@BetaLaunchedEffect}; onPrimaryTabChanged(if (route == "TRANSACTIONS") "TRANSACTIONS" else "HOME");onDestinationChanged(route) }
    BetaLaunchedEffect(routeRequest) {
        if (lastRouteRequest != routeRequest && requestedRoute != null) {
            route = requestedRoute; commitmentDetailExitsToHome=false; selectedId = null; selectedBank = null; selectedTransaction = null;selectedMerchantKey=null
            lastRouteRequest = routeRequest
        }
    }
    val month = YearMonth.parse(monthText)
    var cardTab by rememberSaveable {mutableStateOf("CREDIT")}
    var visibleRows by rememberSaveable(route, monthText) { mutableIntStateOf(20) }
    BetaLaunchedEffect(workflow, monthText, retry, routeRequest,refreshRequest) {
        // Clear old money before a different period is exposed, including failed reads.
        loading = true; failed = false
        if(result?.snapshot?.query != activeWorkflow.query(month)) result = null
        try {
            val configuredDay=controls.read().draft.salary?.day ?: workflow.periodStartDay
            activeWorkflow=workflow.withPeriodStartDay(configuredDay)
            if(configuredDay!=appliedPeriodDay && month==workflow.withPeriodStartDay(appliedPeriodDay).currentMonth()) {
                appliedPeriodDay=configuredDay
                val current=activeWorkflow.currentMonth()
                if(current!=month) {monthText=current.toString();return@BetaLaunchedEffect}
            }
            appliedPeriodDay=configuredDay
            if(result?.snapshot?.query != activeWorkflow.query(month)) result = null
            result = activeWorkflow.load(month)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    fun goBack() {
        if(controlsBusy || !enabled) return
        when {
            selectedTransaction!=null -> selectedTransaction=null
            selectedId!=null -> {selectedId=null;selectedBank=null;if(commitmentDetailExitsToHome) {route="OVERVIEW";commitmentDetailExitsToHome=false}}
            selectedMerchantKey!=null && result?.snapshot?.analysis?.merchants?.any {it.merchantKey==selectedMerchantKey}==true -> selectedMerchantKey=null
            route=="DAILY" -> route=dailyOrigin
            else -> {selectedMerchantKey=null;route="OVERVIEW"}
        }
    }
    BackHandler(enabled=selectedTransaction!=null || selectedId!=null || route!="OVERVIEW" || controlsBusy) {goBack()}
    val pageTitle=when {
        selectedTransaction!=null -> stringResource(R.string.beta_detail_transaction_detail_title)
        route=="MERCHANTS" && selectedMerchantKey!=null -> result?.snapshot?.analysis?.merchants?.singleOrNull {it.merchantKey==selectedMerchantKey}?.merchant ?: stringResource(R.string.beta_approved_dashboard_merchants_summary_title)
        selectedId!=null -> stringResource(when {
            result?.snapshot?.accounts?.any {it.id.bank.value==selectedBank && it.id.localId==selectedId}==true -> R.string.beta_approved_reference_account_detail_title
            result?.snapshot?.loans?.any {it.id.bank.value==selectedBank && it.id.localId==selectedId}==true -> R.string.beta_approved_reference_loan_detail_title
            else -> R.string.beta_approved_reference_card_detail_title
        })
        route=="OVERVIEW" -> null
        else -> dashboardRoutes.singleOrNull {it.first==route}?.second?.let {stringResource(it)}
    }
    SideEffect {onPageTitle(pageTitle)}
    fun select(id: AssetId) {
        if(result?.snapshot?.creditFacilities?.any {it.facilityId==id}==true) {
            route="CARDS";cardTab="CREDIT";selectedBank=null;selectedId=null
        } else {selectedBank=id.bank.value;selectedId=id.localId}
    }
    fun matches(id: AssetId) = id.bank.value == selectedBank && id.localId == selectedId
    val originalContent: @Composable () -> Unit = {
        Box((if(ownsScroll && route=="TRANSACTIONS" && selectedTransaction==null) Modifier.fillMaxSize() else Modifier).testTag("beta-dashboard")) {
        Column(verticalArrangement = Arrangement.spacedBy(if(selectedTransaction!=null) com.baraa.masroof.ui.theme.MasroofSpacing.screenVertical else BetaSpacing.section), modifier = (if(ownsScroll && route=="TRANSACTIONS" && selectedTransaction==null) Modifier.fillMaxSize() else Modifier).testTag("dash-route-$route").semantics {selected=true}) {
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            val displayedPeriod=activeWorkflow.query(month)
            val periodLabel = com.baraa.masroof.lab.beta.presentation.approved.approvedPeriodRange(
                displayedPeriod.from.atZone(activeWorkflow.zone).toLocalDate(),displayedPeriod.until.minusNanos(1).atZone(activeWorkflow.zone).toLocalDate(),
                locale,compact=route=="OVERVIEW" && selectedId==null)
            if(route!="TRANSACTIONS" && route!="LOANS" && route!="CONFIGURATION" && selectedTransaction==null && (selectedId==null || result?.snapshot?.accounts?.any {matches(it.id)}==true)) {
                if(route=="OVERVIEW" && selectedId==null) com.baraa.masroof.ui.dashboard.DashboardPeriodHeader(periodLabel, scope = {
                Text(stringResource(R.string.beta_parity_salary_period), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }, previousDescription = stringResource(R.string.beta_dash_previous), nextDescription = stringResource(R.string.beta_dash_next),
                onPrevious = { monthText = month.minusMonths(1).toString() }, onNext = { monthText = month.plusMonths(1).toString() },
                enabled = interactive && !loading, rangeTag = "dash-month", rangeModifier = Modifier.semantics { contentDescription = monthText }, previousTag = "dash-month-previous", nextTag = "dash-month-next",
                customize = {
                    com.baraa.masroof.ui.components.MasroofTextButton(enabled = interactive, onClick = { homeLayoutOpen = true; route = "OVERVIEW"; selectedId = null; selectedBank = null; selectedTransaction = null },
                        modifier = Modifier.heightIn(min = com.baraa.masroof.ui.theme.MasroofSpacing.minimumTouchTarget).testTag("home-configure")) {
                        Icon(com.baraa.masroof.ui.components.MasroofOutlineIcons.SlidersHorizontal, null,
                            Modifier.size(com.baraa.masroof.ui.theme.MasroofReference.icon))
                        Text(stringResource(R.string.beta_home_customize), Modifier.padding(start = com.baraa.masroof.ui.theme.MasroofSpacing.smallGap), style = MaterialTheme.typography.titleSmall)
                    }
                })
                else com.baraa.masroof.lab.beta.presentation.approved.ApprovedPeriodDisplay(periodLabel,
                    Modifier.testTag("dash-month").semantics {contentDescription=monthText})
            }
            if(selectedId==null && selectedTransaction==null && selectedMerchantKey==null && route!="OVERVIEW" && showInternalHeader) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(pageTitle.orEmpty(),style=MaterialTheme.typography.headlineSmall)
                    TextButton(enabled=interactive,onClick=::goBack,modifier=Modifier.testTag("dash-overview")) {Text(stringResource(R.string.beta_dash_back))}
                }
            } else if(showInternalHeader && (selectedId!=null || selectedTransaction!=null || selectedMerchantKey!=null)) TextButton(enabled=interactive,onClick=::goBack,modifier=Modifier.testTag("dash-detail-back")) {Text(stringResource(R.string.beta_dash_back))}

            if(failed) {
                Text(stringResource(R.string.beta_dash_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("dash-error"))
                OutlinedButton(onClick = { retry++ }, enabled = enabled && !loading && !controlsBusy, modifier = Modifier.testTag("dash-retry")) { Text(stringResource(R.string.beta_retry)) }
            }
            when {
                (loading && result==null) || (result != null && result?.snapshot?.query != activeWorkflow.query(month)) -> { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("dash-loading")); Text(stringResource(R.string.beta_dash_loading)) }
                else -> result?.let { read ->
                    val snapshot = read.snapshot
                    SideEffect {onNotificationFacts(periodLabel,snapshot.analysis?.excludedOtherCurrencyCount ?: 0)}
                    if (read.unavailableSections.any {it in setOf(DashboardSection.CURRENT_LEDGER,DashboardSection.VALUATION,DashboardSection.RUNTIME_SOURCE)}) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(if(DashboardSection.CURRENT_LEDGER in read.unavailableSections || DashboardSection.VALUATION in read.unavailableSections) R.string.beta_fix_confirmed_only else R.string.beta_fix_commitments_unknown), Modifier.padding(BetaSpacing.screen).testTag("dash-incomplete"))
                    }
                    if (selectedTransaction != null) pageStates.SaveableStateProvider("transaction:$selectedTransaction") {
                        BetaDashboardTransactionDetail(snapshot, checkNotNull(selectedTransaction),onListShare)
                        snapshot.transactions.singleOrNull {it.key.transactionId==selectedTransaction}?.let {tx ->
                            transactionExtras?.invoke(tx) {closed -> if(closed) selectedTransaction=null;retry++}
                        }
                    } else pageStates.SaveableStateProvider(BetaDailyExpensesWorkflow.pageStateKey(route,selectedBank,selectedId)) {
                    if (selectedId != null) {
                        val account = snapshot.accounts.singleOrNull { matches(it.id) }
                        val debit = snapshot.debitCards.singleOrNull { matches(it.cardId) }
                        val loan = snapshot.loans.singleOrNull { matches(it.id) }
                        val card = (snapshot.creditFacilities.flatMap { it.cards }+snapshot.standaloneCreditCards).singleOrNull { matches(it.cardId) }
                        when {
                            account != null || debit != null || card != null || loan != null -> BetaAssetDetail(
                                snapshot, account, debit, card, loan, interactive,
                                { selectedTransaction = it },
                                DashboardSection.RUNTIME_SOURCE !in read.unavailableSections)
                            else -> Text(stringResource(R.string.beta_dash_missing), modifier = Modifier.testTag("dash-missing"))
                        }
                    } else when (route) {
                        "OVERVIEW" -> BetaDashboardHome(read, interactive, preferences, onRoute = { route = it },
                            onTransaction = { selectedTransaction = it }, onImport = onImport, layoutOpen = homeLayoutOpen,
                            onSetup = onSetup, pendingCardCount=pendingCardCount,onCards=onCards, onLayoutClose = { homeLayoutOpen = false })
                        "FLOW_IN", "FLOW_OUT" -> {
                            val incoming = route == "FLOW_IN"
                            val flow = snapshot.fleetFlow
                            if (flow == null) DashUnavailable() else DashCard(stringResource(if (incoming) R.string.beta_home_inflow else R.string.beta_home_outflow)) {
                                DashValue(R.string.beta_dash_total, amount(if (incoming) flow.totalIn else flow.totalOut))
                                val inbound = setOf(FlowBucket.SALARY, FlowBucket.OTHER_INCOME, FlowBucket.EXTERNAL_IN, FlowBucket.ACCOUNT_REFUNDS, FlowBucket.INTERNAL_IN)
                                flow.buckets.filterKeys { (it in inbound) == incoming }.forEach { (bucket, value) ->
                                    DashValue(flowLabel(bucket), amount(value))
                                }
                            }
                        }
                        "ACCOUNTS" -> {
                            if(snapshot.accounts.isEmpty() && DashboardSection.CONFIRMED_REGISTRY in read.unavailableSections) DashUnavailable() else BetaAccountsSummary(snapshot,visibleRows,interactive,{onManageAssets(com.baraa.masroof.lab.finance.asset.AssetKind.ACCOUNT)},::select)
                            DashMore(snapshot.accounts.size>visibleRows) {visibleRows+=20}
                        }
                        "CARDS" -> {
                            val hasCredit=snapshot.creditFacilities.isNotEmpty() || snapshot.standaloneCreditCards.isNotEmpty()
                            val credit=cardTab=="CREDIT" && hasCredit
                            if(!hasCredit && snapshot.debitCards.isEmpty() && DashboardSection.CONFIRMED_REGISTRY in read.unavailableSections) DashUnavailable() else BetaCardsSummary(snapshot,visibleRows,interactive,credit,{cardTab=if(it) "CREDIT" else "DEBIT"},{onManageAssets(com.baraa.masroof.lab.finance.asset.AssetKind.CREDIT_CARD)},::select)
                            DashMore(snapshot.debitCards.size>visibleRows || snapshot.standaloneCreditCards.size>visibleRows || snapshot.creditFacilities.any {it.cards.size>visibleRows}) {visibleRows+=20}
                        }
                        "LOANS" -> {
                            if(snapshot.loans.isEmpty() && DashboardSection.CONFIRMED_REGISTRY in read.unavailableSections) DashUnavailable() else BetaLoansSummary(snapshot,visibleRows,interactive,{onManageAssets(com.baraa.masroof.lab.finance.asset.AssetKind.LOAN)},::select)
                            DashMore(snapshot.loans.size>visibleRows) {visibleRows+=20}
                        }
                        "COMMITMENTS" -> {
                            val commitments = snapshot.commitments
                            if (commitments == null || (commitments.rows.isEmpty() && DashboardSection.COMMITMENTS in read.unavailableSections)) DashUnavailable() else {
                                BetaCommitmentsSummary(snapshot,commitments,interactive) {row ->
                                    row.userCommitmentId?.let(onOpenCommitment)
                                    val target=row.loanId ?: row.facilityId?.let {facility ->
                                        snapshot.creditFacilities.singleOrNull {it.facilityId==facility}?.cards
                                            ?.singleOrNull {it.role==com.baraa.masroof.lab.finance.asset.CreditCardRole.PRIMARY}?.cardId
                                    }
                                    if(target!=null) {
                                        route=if(row.loanId!=null) "LOANS" else "CARDS"
                                        cardTab="CREDIT";selectedBank=target.bank.value;selectedId=target.localId
                                        commitmentDetailExitsToHome=true
                                    }
                                }
                                if (DashboardSection.COMMITMENTS in read.unavailableSections) DashUnavailable()
                            }
                        }
                        "MERCHANTS" -> BetaMerchantsPage(snapshot,selectedMerchantKey,interactive,
                            DashboardSection.MERCHANT_TOTALS !in read.unavailableSections,{selectedMerchantKey=it},
                            {dailyOrigin="MERCHANTS";route="DAILY"},{selectedTransaction=it})
                        "DAILY" -> BetaDailyExpensesPage(snapshot,interactive,DashboardSection.RUNTIME_SOURCE !in read.unavailableSections,
                            {selectedTransaction=it})
                    }
                    if (selectedId == null && selectedTransaction == null && route == "TRANSACTIONS") BetaDashboardTransactions(snapshot, enabled = interactive, onTransaction = { selectedTransaction = it.transactionId }, complete = DashboardSection.RUNTIME_SOURCE !in read.unavailableSections,bounded=ownsScroll,onShare=onListShare,openGeneration=routeRequest)
                    if (selectedId == null && selectedTransaction == null && route == "CONFIGURATION") BetaDashboardConfigurationPanel(controls, snapshot.creditFacilities, interactive,
                        onBusyChanged = { controlsBusy = it; onBusyChanged(it) }, onSaved = { retry++ })
                    }
                }
            }
        }
        }
    }
    val chartData=remember(preferences) {preferences?.chartData ?: BetaChartDataWorkflow(InMemoryBetaChartDataPreferencesRepository())}
    val content: @Composable ()->Unit = {
        CompositionLocalProvider(com.baraa.masroof.lab.beta.presentation.approved.LocalBetaChartDataWorkflow provides chartData) {originalContent()}
    }
    val readyTag=if(result!=null && !loading && !failed) "dashboard-ready" else "dashboard-loading-state"
    if(ownsScroll) {
        if(route=="OVERVIEW" && selectedId==null && selectedTransaction==null) {
            com.baraa.masroof.lab.beta.presentation.approved.ApprovedLongPullToRefreshBox(
                isRefreshing=loading && result!=null,onRefresh={if(enabled && !controlsBusy) retry++},
                modifier=Modifier.fillMaxSize().testTag(readyTag)) {
                Box(Modifier.fillMaxWidth().padding(horizontal=com.baraa.masroof.ui.theme.MasroofSpacing.screenHorizontal)
                    .padding(top=com.baraa.masroof.ui.theme.MasroofSpacing.screenVertical,bottom=com.baraa.masroof.ui.theme.MasroofSpacing.screenVertical)) {content()}
            }
        } else if(route=="TRANSACTIONS" && selectedTransaction==null) {
            Box(Modifier.fillMaxSize().testTag(readyTag).padding(horizontal=com.baraa.masroof.ui.theme.MasroofSpacing.screenHorizontal).padding(bottom=com.baraa.masroof.ui.theme.MasroofSpacing.screenVertical)) {content()}
        } else {
            val scroll=rememberSaveable(route,selectedBank,selectedId,selectedTransaction,saver=androidx.compose.foundation.ScrollState.Saver) {androidx.compose.foundation.ScrollState(0)}
            Box(Modifier.fillMaxSize().testTag(readyTag).padding(horizontal=com.baraa.masroof.ui.theme.MasroofSpacing.screenHorizontal)
                .padding(bottom=com.baraa.masroof.ui.theme.MasroofSpacing.screenVertical).verticalScroll(scroll)) {content()}
        }
    } else Box(Modifier.testTag(readyTag)) {content()}
}

private val dashboardRoutes = listOf("OVERVIEW" to R.string.beta_dash_summary, "ACCOUNTS" to R.string.beta_dash_accounts,
    "CARDS" to R.string.beta_dash_cards, "LOANS" to R.string.beta_dash_loans, "COMMITMENTS" to R.string.beta_dash_commitments,
    "MERCHANTS" to R.string.beta_approved_dashboard_merchants_summary_title,"DAILY" to R.string.beta_approved_reference_daily_title, "TRANSACTIONS" to R.string.beta_list_transaction_list_title, "CONFIGURATION" to R.string.beta_controls_title)

@Composable private fun DashCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    com.baraa.masroof.ui.components.MasroofCard {
        Column(verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@Composable private fun DashValue(label: Int, value: String, modifier: Modifier = Modifier) {
    // Stacked text also fits split-screen and large font without clipped currency values.
    Column(modifier) { Text(stringResource(label), style = MaterialTheme.typography.labelLarge); Text(value, style = MaterialTheme.typography.titleLarge) }
}
@Composable private fun amount(value: SignedAmount?): String = value?.let { betaMoney(it.amount.toPlainString(),it.currency.name) } ?: stringResource(R.string.beta_approved_dashboard_value_unavailable)
@Composable private fun amount(value: Money?): String = value?.let { betaMoney(it.amount.toPlainString(),it.currency.name) } ?: stringResource(R.string.beta_approved_dashboard_value_unavailable)
@Composable private fun DashEmpty() { Text(stringResource(R.string.beta_dash_empty), modifier = Modifier.testTag("dash-empty")) }
@Composable private fun DashUnavailable() { Text(stringResource(R.string.beta_dash_unavailable_help), modifier = Modifier.testTag("dash-unavailable")) }
@Composable private fun DashMore(visible: Boolean, onClick: () -> Unit) {
    if (visible) OutlinedButton(onClick = onClick, modifier = Modifier.testTag("dash-more")) { Text(stringResource(R.string.beta_dash_more)) }
}

private fun flowLabel(bucket: FlowBucket): Int = when (bucket) {
    FlowBucket.SALARY, FlowBucket.OTHER_INCOME -> R.string.beta_review_income
    FlowBucket.EXTERNAL_IN -> R.string.beta_review_transfer_in
    FlowBucket.ACCOUNT_REFUNDS -> R.string.beta_review_refund
    FlowBucket.INTERNAL_IN, FlowBucket.INTERNAL_OUT -> R.string.beta_review_self_transfer
    FlowBucket.EXTERNAL_OUT -> R.string.beta_review_transfer_out
    FlowBucket.CREDIT_SETTLEMENT -> R.string.beta_review_card_payment
    FlowBucket.CASH_WITHDRAWAL -> R.string.beta_review_cash
    FlowBucket.BILLS -> R.string.beta_review_bill
    FlowBucket.POS_PURCHASES -> R.string.beta_review_expense
    FlowBucket.FEES -> R.string.beta_review_fee
    FlowBucket.LOAN -> R.string.beta_review_loan
}
