package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.presentation.BetaDashboardVisualMapper
import com.baraa.masroof.lab.beta.presentation.MasroofSheetChoices
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.dashboard.*
import com.baraa.masroof.ui.theme.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal val LocalBetaChartDataWorkflow=staticCompositionLocalOf {BetaChartDataWorkflow(InMemoryBetaChartDataPreferencesRepository())}

/** Production SpendingTrendSection/ChartHeader over lab projections. All arithmetic stays in workflows. */
@Composable
internal fun ApprovedSpendingTrend(trend: SpendingTrend?, title: String, explanation: String,
    role: MasroofHeroRole, enabled: Boolean, chartKey: String, showAverage: Boolean,
    modifier: Modifier=Modifier,cardTag: String="asset-trend",chartTag: String="asset-spending-chart",averageTag: String="chart-average",
    unavailableTag: String="asset-chart-unavailable",customizeTag: String="chart-customize",
    controls: (@Composable ()->Unit)?=null,selectedDate: LocalDate?=null,
    onDaySelected: ((LocalDate)->Unit)?=null,scopeLabel: String=title) {
    if(trend==null) {
        MasroofCard(modifier.testTag(cardTag)) {
            ApprovedText(stringResource(R.string.beta_dash_unavailable_help),Modifier.testTag(unavailableTag))
        }
        return
    }
    val workflow=LocalBetaChartDataWorkflow.current
    val preferences by workflow.preferences.collectAsState()
    val categories=workflow.categories(trend,chartKey)
    val selected=remember(trend,chartKey,preferences) {workflow.selected(trend,chartKey)}
    val custom=preferences.selections.containsKey(chartKey) && categories!=trend.categoryTrends.keys
    val categoryNames=categories.map {approvedChartCategoryLabel(it)}.joinToString(stringResource(R.string.beta_approved_chart_type_separator))
    val guidance=if(custom) stringResource(R.string.beta_approved_chart_selected_data,categoryNames)+"\n"+
        stringResource(R.string.beta_approved_chart_refunds_selection_hint) else explanation
    var sheetOpen by rememberSaveable(chartKey) {mutableStateOf(false)}
    if(sheetOpen) ApprovedChartDataSheet(trend,chartKey,scopeLabel,enabled) {sheetOpen=false}
    val visual=BetaDashboardVisualMapper.trend(selected)
    val locale=LocalConfiguration.current.locales[0]
    val date=DateTimeFormatter.ofPattern("d MMM",locale)
    var rememberedDate by rememberSaveable(chartKey,trend.period.from.toString()) {mutableStateOf<String?>(null)}
    MasroofCard(modifier.testTag(cardTag)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            ApprovedGuidanceLabel(title,guidance,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,
                contentColor=MaterialTheme.colorScheme.onSurface)
            if(trend.categoryTrends.isNotEmpty()) IconButton(onClick={sheetOpen=true},enabled=enabled,
                modifier=Modifier.testTag(customizeTag)) {
                Icon(DashboardChartMenuIcon,stringResource(R.string.beta_approved_chart_customize_title),Modifier.size(MasroofReference.icon))
            } else Icon(MasroofOutlineIcons.ChartLine,null,Modifier.size(MasroofReference.icon))
        }
        if(custom) ApprovedText(stringResource(R.string.beta_approved_chart_custom_selection,categories.size),
            Modifier.testTag("chart-custom-summary"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        controls?.let {content -> Column(Modifier.fillMaxWidth().padding(top=MasroofSpacing.sectionGap)) {content()}}
        when {
            selected.points.isEmpty() -> ApprovedText(stringResource(R.string.beta_home_no_elapsed_days),style=MaterialTheme.typography.bodySmall)
            visual==null -> ApprovedText(stringResource(R.string.beta_dash_unavailable_help),Modifier.testTag(unavailableTag))
            else -> {
                val index=visual.dates.indexOf(selectedDate?.toString() ?: rememberedDate).takeIf {it>=0} ?: visual.dates.lastIndex
                fun money(value: BigDecimal)=MasroofMoneyDisplay.format(value,visual.currency,locale.toLanguageTag())
                DashboardTrendHead(date.format(LocalDate.parse(visual.dates[index])),money(visual.values[index]),
                    average=if(showAverage) visual.average?.let {value -> ({
                        MasroofChartAverageLegend(stringResource(R.string.beta_approved_dashboard_chart_average_value,money(value)),Modifier.weight(1f).testTag(averageTag))
                    })} else null)
                MasroofInteractiveLineChart(visual.values,visual.average ?: BigDecimal.ZERO,
                    LineChartLabels(stringResource(R.string.beta_chart_day_axis),stringResource(R.string.beta_chart_previous_point),
                        stringResource(R.string.beta_chart_next_point),stringResource(R.string.beta_chart_average_announcement,
                            visual.average?.let(::money) ?: stringResource(R.string.beta_approved_dashboard_value_unavailable))),
                    modifier=Modifier.testTag(chartTag),selectedPointIndex=index,role=role,
                    onPointSelected={if(enabled) {rememberedDate=visual.dates[it];onDaySelected?.invoke(LocalDate.parse(visual.dates[it]))}},
                    pointLabel={date.format(LocalDate.parse(visual.dates[it]))+" · "+money(visual.values[it])},
                    axisLabel={DateTimeFormatter.ofPattern("d/M",locale).format(LocalDate.parse(visual.dates[it]))},
                    currencyLabel=MasroofMoneyDisplay.currencyLabel(visual.currency,locale.toLanguageTag()),
                    showAverage=showAverage && visual.average!=null)
            }
        }
    }
}

@Composable
private fun ApprovedChartDataSheet(trend: SpendingTrend,key: String,title: String,enabled: Boolean,onDismiss: ()->Unit) {
    val workflow=LocalBetaChartDataWorkflow.current
    var draft by rememberSaveable(key) {mutableStateOf(workflow.categories(trend,key).map {it.name})}
    var failed by remember {mutableStateOf(false)}
    val selected=draft.mapNotNull {runCatching {ChartCategory.valueOf(it)}.getOrNull()}.toSet()
    ApprovedBottomSheet(stringResource(R.string.beta_approved_chart_customize_title),onDismiss,{
        try {workflow.save(key,selected,trend.categoryTrends.keys);onDismiss()}
        catch(_: Exception) {failed=true}
    },stringResource(R.string.beta_approved_chart_save_apply),subtitle=title,canApply=enabled && selected.isNotEmpty(),
        resetLabel=stringResource(R.string.beta_approved_chart_restore_defaults),onReset={draft=trend.categoryTrends.keys.map {it.name}},
        sheetTag="chart-data-sheet",applyTag="chart-data-apply",cancelTag="chart-data-cancel") {
        MasroofSheetChoices(trend.categoryTrends.keys.toList(),selected,{approvedChartCategoryLabel(it)},{
            draft=if(it.name in draft) draft-it.name else draft+it.name
        },{"chart-category-${it.name}"},enabled=enabled)
        ApprovedGuidanceLabel(stringResource(R.string.beta_approved_chart_data_about),stringResource(R.string.beta_approved_chart_refunds_selection_hint))
        if(failed) ApprovedText(stringResource(R.string.beta_chart_save_error),color=MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun approvedChartCategoryLabel(value: ChartCategory)=stringResource(when(value) {
    ChartCategory.CREDIT_PURCHASES -> R.string.beta_approved_chart_category_credit
    ChartCategory.MADA_PURCHASES -> R.string.beta_approved_chart_category_mada
    ChartCategory.ACCOUNT_PURCHASES -> R.string.beta_approved_chart_category_account_purchases
    ChartCategory.OTHER_EXPENSES -> R.string.beta_approved_chart_category_other
    ChartCategory.CASH_WITHDRAWALS -> R.string.beta_approved_chart_category_cash
    ChartCategory.EXTERNAL_TRANSFERS -> R.string.beta_approved_chart_category_external
    ChartCategory.BILLS -> R.string.beta_approved_chart_category_bills
    ChartCategory.LOAN_REPAYMENTS -> R.string.beta_approved_chart_category_loans
    ChartCategory.CREDIT_SETTLEMENTS -> R.string.beta_approved_chart_category_settlements
    ChartCategory.FEES -> R.string.beta_approved_chart_category_fees
    ChartCategory.INTERNAL_TRANSFERS -> R.string.beta_approved_chart_category_internal
    ChartCategory.UNATTRIBUTED_REFUNDS -> R.string.beta_approved_chart_category_refunds
})
