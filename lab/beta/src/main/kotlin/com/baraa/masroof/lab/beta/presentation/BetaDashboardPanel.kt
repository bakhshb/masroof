package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
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
    preferences: BetaDashboardViewPreferencesWorkflow? = null, requestedRoute: String? = null, routeRequest: Int = 0, onPrimaryTabChanged: (String) -> Unit = {}, onBusyChanged: (Boolean) -> Unit = {}, onImport: () -> Unit = {}, onSetup: () -> Unit = onImport, pendingCardCount: Int = 0, onCards: () -> Unit = {}, onPageTitle: (String?) -> Unit = {}, showInternalHeader: Boolean = true, onManageAssets: (com.baraa.masroof.lab.finance.asset.AssetKind)->Unit = {},customizationRequest: Int = 0, transactionExtras: (@Composable (String,(Boolean)->Unit)->Unit)? = null) {
    var controlsBusy by remember { mutableStateOf(false) }
    var homeLayoutOpen by rememberSaveable { mutableStateOf(false) }
    var handledCustomization by rememberSaveable {mutableIntStateOf(0)}
    LaunchedEffect(customizationRequest) {if(customizationRequest!=handledCustomization) {handledCustomization=customizationRequest;homeLayoutOpen=true}}
    val interactive = enabled && !controlsBusy
    var selectedTransaction by rememberSaveable { mutableStateOf<String?>(null) }
    var monthText by rememberSaveable { mutableStateOf(workflow.currentMonth().toString()) }
    var route by rememberSaveable { mutableStateOf(requestedRoute ?: "OVERVIEW") }
    var lastRouteRequest by rememberSaveable { mutableIntStateOf(routeRequest) }
    var selectedBank by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<DashboardProduction?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var activeWorkflow by remember(workflow) { mutableStateOf(workflow) }
    var appliedPeriodDay by rememberSaveable { mutableIntStateOf(workflow.periodStartDay) }
    BetaLaunchedEffect(route) { onPrimaryTabChanged(if (route == "TRANSACTIONS") "TRANSACTIONS" else "HOME") }
    BetaLaunchedEffect(routeRequest) {
        if (lastRouteRequest != routeRequest && requestedRoute != null) {
            route = requestedRoute; selectedId = null; selectedBank = null; selectedTransaction = null
            lastRouteRequest = routeRequest
        }
    }
    val month = YearMonth.parse(monthText)
    var cardTab by rememberSaveable {mutableStateOf("CREDIT")}
    var visibleRows by rememberSaveable(route, monthText) { mutableIntStateOf(20) }
    BetaLaunchedEffect(workflow, monthText, retry, routeRequest) {
        // Clear old money before a different period is exposed, including failed reads.
        loading = true; failed = false; result = null
        try {
            val configuredDay=controls.read().draft.salary?.day ?: workflow.periodStartDay
            activeWorkflow=workflow.withPeriodStartDay(configuredDay)
            if(configuredDay!=appliedPeriodDay && month==workflow.withPeriodStartDay(appliedPeriodDay).currentMonth()) {
                appliedPeriodDay=configuredDay
                val current=activeWorkflow.currentMonth()
                if(current!=month) {monthText=current.toString();return@BetaLaunchedEffect}
            }
            appliedPeriodDay=configuredDay
            result = activeWorkflow.load(month)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    BackHandler(enabled = selectedTransaction != null || selectedId != null || route != "OVERVIEW" || controlsBusy) {
        if (controlsBusy) return@BackHandler
        if (selectedTransaction != null) selectedTransaction = null
        else if (selectedId != null) { selectedId = null; selectedBank = null } else route = "OVERVIEW"
    }
    val pageTitle=when {
        selectedTransaction!=null -> stringResource(R.string.beta_transaction_details)
        selectedId!=null -> stringResource(R.string.beta_dash_details)
        route=="OVERVIEW" -> null
        else -> dashboardRoutes.singleOrNull {it.first==route}?.second?.let {stringResource(it)}
    }
    SideEffect {onPageTitle(pageTitle)}
    fun select(id: AssetId) { selectedBank = id.bank.value; selectedId = id.localId }
    fun matches(id: AssetId) = id.bank.value == selectedBank && id.localId == selectedId
    Box(Modifier.testTag("beta-dashboard")) {
    Column(verticalArrangement = Arrangement.spacedBy(BetaSpacing.section), modifier = Modifier.testTag("dash-route-$route").semantics {selected=true}) {
        val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        val dateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM", locale)
        val displayedPeriod=activeWorkflow.query(month)
        val periodLabel = dateFormat.format(displayedPeriod.from.atZone(activeWorkflow.zone)) + " – " + dateFormat.format(displayedPeriod.until.minusNanos(1).atZone(activeWorkflow.zone))
        if(route!="LOANS" && route!="CONFIGURATION" && selectedTransaction==null && (selectedId==null || result?.snapshot?.accounts?.any {matches(it.id)}==true)) com.baraa.masroof.ui.dashboard.DashboardPeriodHeader(periodLabel, scope = {
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
        if(selectedId==null && selectedTransaction==null && route!="OVERVIEW" && showInternalHeader) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(pageTitle.orEmpty(),style=MaterialTheme.typography.headlineSmall)
                TextButton(enabled=interactive,onClick={route="OVERVIEW"},modifier=Modifier.testTag("dash-overview")) {Text(stringResource(R.string.beta_dash_back))}
            }
        } else if(showInternalHeader && (selectedId!=null || selectedTransaction!=null)) TextButton(enabled=interactive,onClick={if(selectedTransaction!=null) selectedTransaction=null else {selectedId=null;selectedBank=null}},modifier=Modifier.testTag("dash-detail-back")) {Text(stringResource(R.string.beta_dash_back))}

        when {
            loading || (result != null && result?.snapshot?.query != activeWorkflow.query(month)) -> { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("dash-loading")); Text(stringResource(R.string.beta_dash_loading)) }
            failed -> {
                Text(stringResource(R.string.beta_dash_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("dash-error"))
                OutlinedButton(onClick = { retry++ }, enabled = interactive, modifier = Modifier.testTag("dash-retry")) { Text(stringResource(R.string.beta_retry)) }
            }
            else -> result?.let { read ->
                val snapshot = read.snapshot
                if (read.unavailableSections.any {it in setOf(DashboardSection.CURRENT_LEDGER,DashboardSection.VALUATION,DashboardSection.RUNTIME_SOURCE)}) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(if(DashboardSection.CURRENT_LEDGER in read.unavailableSections || DashboardSection.VALUATION in read.unavailableSections) R.string.beta_fix_confirmed_only else R.string.beta_fix_commitments_unknown), Modifier.padding(BetaSpacing.screen).testTag("dash-incomplete"))
                }
                if (selectedTransaction != null) {
                    BetaDashboardTransactionDetail(snapshot, checkNotNull(selectedTransaction))
                    transactionExtras?.invoke(checkNotNull(selectedTransaction)) {closed -> if(closed) selectedTransaction=null;retry++}
                } else if (selectedId != null) {
                    val account = snapshot.accounts.singleOrNull { matches(it.id) }
                    val debit = snapshot.debitCards.singleOrNull { matches(it.cardId) }
                    val facility = snapshot.creditFacilities.singleOrNull { matches(it.facilityId) }
                    val loan = snapshot.loans.singleOrNull { matches(it.id) }
                    val card = (snapshot.creditFacilities.flatMap { it.cards }+snapshot.standaloneCreditCards).singleOrNull { matches(it.cardId) }
                    when {
                        account != null || debit != null || facility != null || card != null || loan != null -> BetaAssetDetail(
                            snapshot, account, debit, facility, card, loan, interactive, ::select,
                            { selectedTransaction = it },
                            DashboardSection.RUNTIME_SOURCE !in read.unavailableSections)
                        else -> Text(stringResource(R.string.beta_dash_missing), modifier = Modifier.testTag("dash-missing"))
                    }
                } else when (route) {
                    "OVERVIEW" -> BetaDashboardHome(read, interactive, preferences, onRoute = { route = it },
                        onTransaction = { selectedTransaction = it }, onImport = onImport, layoutOpen = homeLayoutOpen,
                        onSetup = onSetup, pendingCardCount=pendingCardCount,onCards=onCards, onLayoutClose = { homeLayoutOpen = false }, onConfiguration = { homeLayoutOpen = false; route = "CONFIGURATION" })
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
                            BetaCommitmentsSummary(snapshot,commitments,visibleRows,interactive,::select)
                            if (DashboardSection.COMMITMENTS in read.unavailableSections) DashUnavailable()
                            DashMore(commitments.rows.size > visibleRows) { visibleRows += 20 }
                        }
                    }
                    "ANALYSIS" -> {
                        val analysis = snapshot.analysis
                        if (analysis == null) DashUnavailable() else {
                            DashCard(stringResource(R.string.beta_dash_analysis)) {
                                DashValue(R.string.beta_dash_spending, amount(analysis.metrics[SummaryMetric.SPENDING_NET]))
                                DashValue(R.string.beta_dash_refunds, amount(analysis.metrics[SummaryMetric.REFUNDS]))
                                DashValue(R.string.beta_dash_income, amount(analysis.metrics[SummaryMetric.INCOME]))
                            }
                            Text(stringResource(R.string.beta_dash_merchants), style = MaterialTheme.typography.titleLarge)
                            if (DashboardSection.MERCHANT_TOTALS in read.unavailableSections) DashUnavailable()
                            else if (analysis.merchants.isEmpty()) DashEmpty()
                            analysis.merchants.take(visibleRows).forEach { merchant -> DashCard(merchant.merchant) { Text(amount(merchant.net)) } }
                            DashMore(analysis.merchants.size > visibleRows) { visibleRows += 20 }
                            BetaDashboardAnalysisPanel(snapshot, interactive, preferences) { key -> selectedTransaction = key.transactionId }
                        }
                    }
                }
                if (selectedId == null && selectedTransaction == null && route == "TRANSACTIONS") BetaDashboardTransactions(snapshot, enabled = interactive, onTransaction = { selectedTransaction = it.transactionId }, complete = DashboardSection.RUNTIME_SOURCE !in read.unavailableSections)
                if (selectedId == null && selectedTransaction == null && route == "CONFIGURATION") BetaDashboardConfigurationPanel(controls, snapshot.creditFacilities, month, interactive,
                    onBusyChanged = { controlsBusy = it; onBusyChanged(it) }, onSaved = { retry++ })
                snapshot.revision?.let { Text(stringResource(R.string.beta_dash_as_of, betaDate(it.asOf,snapshot.query.zone)), style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { retry++ }, enabled = interactive, modifier = Modifier.testTag("dash-refresh")) { Text(stringResource(R.string.beta_dash_refresh)) }
            }
        }
    }
    }
}

private val dashboardRoutes = listOf("OVERVIEW" to R.string.beta_dash_summary, "ACCOUNTS" to R.string.beta_dash_accounts,
    "CARDS" to R.string.beta_dash_cards, "LOANS" to R.string.beta_dash_loans, "COMMITMENTS" to R.string.beta_dash_commitments,
    "ANALYSIS" to R.string.beta_dash_analysis, "TRANSACTIONS" to R.string.beta_transaction_title, "CONFIGURATION" to R.string.beta_controls_title)

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
@Composable private fun amount(value: SignedAmount?): String = value?.let { betaMoney(it.amount.toPlainString(),it.currency.name) } ?: stringResource(R.string.beta_dash_unavailable)
@Composable private fun amount(value: Money?): String = value?.let { betaMoney(it.amount.toPlainString(),it.currency.name) } ?: stringResource(R.string.beta_dash_unavailable)
@Composable private fun DashEmpty() { Text(stringResource(R.string.beta_dash_empty), modifier = Modifier.testTag("dash-empty")) }
@Composable private fun DashUnavailable() { Text(stringResource(R.string.beta_dash_unavailable_help), modifier = Modifier.testTag("dash-unavailable")) }
@Composable private fun DashMore(visible: Boolean, onClick: () -> Unit) {
    if (visible) OutlinedButton(onClick = onClick, modifier = Modifier.testTag("dash-more")) { Text(stringResource(R.string.beta_dash_more)) }
}
@Composable private fun DashPeriod(period: DisplayPeriod?) {
    if (period == null) Text(stringResource(R.string.beta_dash_period_unavailable), style = MaterialTheme.typography.bodySmall)
    else Text(stringResource(R.string.beta_dash_period, period.from.atZone(period.zone).toLocalDateTime().toString(),
        period.until.atZone(period.zone).toLocalDateTime().toString()), style = MaterialTheme.typography.bodySmall)
}
@Composable private fun DashLink(id: AssetId, label: String, enabled: Boolean, onClick: (AssetId) -> Unit) {
    OutlinedButton(enabled = enabled, onClick = { onClick(id) }, modifier = Modifier.testTag("dash-asset-${id.bank.value}-${id.localId}")) { Text(label) }
}
@Composable private fun DashFlow(flow: AccountFlow?) {
    DashValue(R.string.beta_dash_inflow, amount(flow?.totalIn)); DashValue(R.string.beta_dash_outflow, amount(flow?.totalOut)); DashValue(R.string.beta_dash_remaining, amount(flow?.remaining))
}
@Composable private fun DashLoan(loan: LoanSummary, zone: java.time.ZoneId, content: @Composable () -> Unit = {}) {
    DashCard(loan.label.takeIf(String::isNotBlank) ?: stringResource(R.string.beta_asset_loan)) {
        DashValue(R.string.beta_dash_loan_remaining, amount(loan.remaining))
        loan.remainingAsOf?.let { Text(stringResource(R.string.beta_dash_as_of, it.atZone(zone).toLocalDate().toString()), style = MaterialTheme.typography.bodySmall) }
        DashValue(R.string.beta_dash_installment, amount(loan.latestInstallment))
        loan.installmentAsOf?.let { Text(stringResource(R.string.beta_dash_as_of, it.atZone(zone).toLocalDate().toString()), style = MaterialTheme.typography.bodySmall) }
        DashValue(R.string.beta_dash_salary_payment, amount(loan.salaryPayment)); DashPeriod(loan.salaryPeriod); content()
    }
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
