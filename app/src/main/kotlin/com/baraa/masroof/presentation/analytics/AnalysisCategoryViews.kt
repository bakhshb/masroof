package com.baraa.masroof.presentation.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.baraa.masroof.R
import com.baraa.masroof.analytics.SpendingCategory
import com.baraa.masroof.application.analytics.AnalysisPresentationBuilder
import com.baraa.masroof.application.analytics.AnalysisSnapshot
import com.baraa.masroof.application.analytics.AnalyticsWorkflow
import com.baraa.masroof.application.dashboard.AnalysisSpendingGroup
import com.baraa.masroof.application.locale.AppDigits
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.ui.components.MasroofStackedBar
import com.baraa.masroof.ui.components.MasroofStackedPart
import com.baraa.masroof.ui.theme.MasroofComponentShapes
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofThemeExtras

@Composable internal fun spendingCategoryName(category: String?): String = stringResource(when (SpendingCategory.fromStored(category)) {
    SpendingCategory.GROCERIES -> R.string.spending_category_groceries
    SpendingCategory.FOOD_DELIVERY -> R.string.spending_category_food_delivery
    SpendingCategory.RESTAURANTS -> R.string.spending_category_restaurants
    SpendingCategory.SHOPPING -> R.string.spending_category_shopping
    SpendingCategory.INSTALLMENTS -> R.string.spending_category_installments
    SpendingCategory.FUEL_CAR -> R.string.spending_category_fuel_car
    SpendingCategory.HEALTH -> R.string.spending_category_health
    SpendingCategory.BILLS_TELECOM -> R.string.spending_category_bills_telecom
    SpendingCategory.SUBSCRIPTIONS -> R.string.spending_category_subscriptions
    SpendingCategory.TRANSPORT_TRAVEL -> R.string.spending_category_transport_travel
    SpendingCategory.CHARITY -> R.string.spending_category_charity
    SpendingCategory.PERSONAL_CARE -> R.string.spending_category_personal_care
    SpendingCategory.OTHER, null -> R.string.spending_category_other
})

@Composable private fun categoryPalette(): List<androidx.compose.ui.graphics.Color> {
    val extended = MasroofThemeExtras.extendedColors
    return listOf(extended.card, extended.liability, extended.account, extended.inflow, MaterialTheme.colorScheme.primary)
}

/** One stacked bar for the largest categories; each row opens that category's transactions. */
@Composable internal fun AnalysisCategoryCard(snapshot: AnalysisSnapshot, onOpenGroup: (String) -> Unit) {
    val parts = remember(snapshot) { AnalysisPresentationBuilder.categories(snapshot) }
    if (parts.isEmpty()) return
    val palette = categoryPalette()
    val rest = MaterialTheme.colorScheme.outline
    fun color(index: Int) = palette.getOrElse(index) { rest }
    MasroofCard(Modifier.testTag("analysis-categories")) {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            MasroofGuidanceLabel(stringResource(R.string.analysis_categories_title), stringResource(R.string.analysis_categories_hint),
                style = MaterialTheme.typography.titleMedium, contentColor = MaterialTheme.colorScheme.onSurface)
            val names = parts.map { spendingCategoryName(it.group.category) }
            val percents = parts.map { stringResource(R.string.analysis_share_percent, it.percent) }
            MasroofStackedBar(parts.mapIndexed { index, part -> MasroofStackedPart(part.share, color(index)) },
                AppDigits.display(parts.indices.joinToString(" · ") { "${names[it]} ${percents[it]}" }))
            Column {
                parts.forEachIndexed { index, part ->
                    Row(Modifier.fillMaxWidth().heightIn(min = MasroofSpacing.minimumTouchTarget)
                        .clickable(role = Role.Button, onClick = { onOpenGroup(part.group.key) }).testTag("analysis-category-${part.group.key}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                        Box(Modifier.size(MasroofSpacing.sectionGap).background(color(index), MasroofComponentShapes.tokenSwatch))
                        Text(names[index], Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(AppDigits.display(percents[index]),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(visualMoney(part.group.amount), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

/** The merchant's category with a change action. The choice applies only on Save; saving blocks dismissal. */
@Composable internal fun MerchantCategoryEditor(group: AnalysisSpendingGroup, workflow: AnalyticsWorkflow) {
    var open by rememberSaveable(group.key) { mutableStateOf(false) }
    var draft by rememberSaveable(group.key) { mutableStateOf(group.category ?: SpendingCategory.OTHER.name) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MasroofCard(Modifier.testTag("merchant-category")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
            Text(stringResource(R.string.analysis_category_of, spendingCategoryName(group.category)), Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall)
            MasroofTextButton(onClick = { draft = group.category ?: SpendingCategory.OTHER.name; failed = false; open = true },
                modifier = Modifier.testTag("merchant-category-change")) { Text(stringResource(R.string.analysis_category_change)) }
        }
        if (failed) Text(stringResource(R.string.analysis_category_failed), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
    if (open) MasroofBottomSheet(stringResource(R.string.analysis_category_change), { if (!busy) open = false }, {
        busy = true
        scope.launchLogged(workflow.appLogService, "LAB_ANALYTICS", "Set merchant category",
            onError = { failed = true }, onFinally = { busy = false }) {
            if (workflow.setMerchantCategory(group.key, draft)) open = false else failed = true
        }
    }, stringResource(R.string.settings_save), subtitle = AppDigits.display(group.name.orEmpty()), busy = busy,
        canApply = draft != group.category) {
        MasroofSheetChoices(SpendingCategory.entries.map { it.name }, setOf(draft), { spendingCategoryName(it) },
            { draft = it }, { it })
    }
}
