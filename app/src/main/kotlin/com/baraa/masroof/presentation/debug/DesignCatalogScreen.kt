package com.baraa.masroof.presentation.debug

import com.baraa.masroof.ui.theme.MasroofComponentShapes
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.baraa.masroof.R
import com.baraa.masroof.presentation.common.IconLabelRow
import com.baraa.masroof.presentation.common.MasroofAmountRole
import com.baraa.masroof.presentation.common.MasroofAmountText
import com.baraa.masroof.presentation.common.MasroofBadge
import com.baraa.masroof.presentation.common.MasroofBarChart
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofCardAccent
import com.baraa.masroof.presentation.common.MasroofHorizontalBar
import com.baraa.masroof.presentation.common.MasroofHorizontalBarStyle
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofDonutChart
import com.baraa.masroof.presentation.common.MasroofInteractiveLineChart
import com.baraa.masroof.presentation.common.commitmentDonutPaidFraction
import com.baraa.masroof.presentation.common.MasroofMoneyRow
import com.baraa.masroof.presentation.common.MasroofMoneyRowStyle
import com.baraa.masroof.presentation.common.MasroofRankedBarRow
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.common.MasroofSectionHeader
import com.baraa.masroof.presentation.settings.SettingsSpacing
import com.baraa.masroof.ui.theme.MasroofIconSizes
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofThemeExtras

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignCatalogScreen(
    onBack: () -> Unit,
) {
    MasroofSecondaryScaffold(
        title = stringResource(R.string.settings_design_catalog_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
        ) {
            Text(
                stringResource(R.string.settings_design_catalog_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(com.baraa.masroof.ui.theme.MasroofDesign.version, style = MaterialTheme.typography.bodySmall)
            com.baraa.masroof.presentation.common.MasroofGuidanceLabel(stringResource(R.string.approved_daily_average),
                stringResource(R.string.approved_average_basis, 15))
            com.baraa.masroof.presentation.common.MasroofHeroCard("مصروف", "1,250", role = com.baraa.masroof.ui.theme.MasroofHeroRole.Account)
            TypographySection()
            ColorSection()
            SpacingSection()
            IconSizeSection()
            ComponentSection()
            AnalyticsCatalogSection()
        }
    }
}

@Composable
private fun CatalogSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
        MasroofSectionHeader(title = title)
        content()
    }
}

@Composable
private fun TypographySection() {
    CatalogSection(title = stringResource(R.string.settings_design_catalog_typography)) {
        MasroofCard {
            Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                Text("displayMedium", style = MaterialTheme.typography.displayMedium)
                Text("headlineSmall", style = MaterialTheme.typography.headlineSmall)
                Text("titleMedium", style = MaterialTheme.typography.titleMedium)
                Text("bodyLarge", style = MaterialTheme.typography.bodyLarge)
                Text("bodyMedium", style = MaterialTheme.typography.bodyMedium)
                Text("labelMedium", style = MaterialTheme.typography.labelMedium)
                Text("labelSmall", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ColorSection() {
    val extended = MasroofThemeExtras.extendedColors
    CatalogSection(title = stringResource(R.string.settings_design_catalog_colors)) {
        MasroofCard {
            Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
                ColorSwatch("primary", MaterialTheme.colorScheme.primary)
                ColorSwatch("primaryContainer", MaterialTheme.colorScheme.primaryContainer)
                ColorSwatch("surface", MaterialTheme.colorScheme.surface)
                ColorSwatch("surfaceContainer", MaterialTheme.colorScheme.surfaceContainer)
                ColorSwatch("surfaceContainerHigh", MaterialTheme.colorScheme.surfaceContainerHigh)
                ColorSwatch("account", extended.account)
                ColorSwatch("card", extended.card)
                ColorSwatch("liability", extended.liability)
                ColorSwatch("inflow", extended.inflow)
                ColorSwatch("outflow", extended.outflow)
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    label: String,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap),
    ) {
        Box(
            modifier = Modifier
                .size(MasroofIconSizes.logo)
                .background(color, MasroofComponentShapes.iconContainer)
                .border(MasroofSpacing.borderWidth, MaterialTheme.colorScheme.outline, MasroofComponentShapes.iconContainer),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SpacingSection() {
    CatalogSection(title = stringResource(R.string.settings_design_catalog_spacing)) {
        MasroofCard {
            Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
                SpacingSample("screenHorizontal", MasroofSpacing.screenHorizontal)
                SpacingSample("sectionGap", MasroofSpacing.sectionGap)
                SpacingSample("sectionHeaderGap", MasroofSpacing.sectionHeaderGap)
                SpacingSample("carouselGap", MasroofSpacing.carouselGap)
                SpacingSample("entityIconSize", MasroofSpacing.entityIconSize)
            }
        }
    }
}

@Composable
private fun SpacingSample(
    label: String,
    size: Dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap),
    ) {
        Box(
            modifier = Modifier
                .width(size)
                .height(MasroofSpacing.sectionHeaderGap)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    MasroofComponentShapes.tokenSwatch,
                ),
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text("${size.value.toInt()}dp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IconSizeSection() {
    CatalogSection(title = stringResource(R.string.settings_design_catalog_icons)) {
        MasroofCard {
            Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                IconSizeSample("xs", MasroofIconSizes.xs)
                IconSizeSample("sm", MasroofIconSizes.sm)
                IconSizeSample("md", MasroofIconSizes.md)
                IconSizeSample("lg", MasroofIconSizes.lg)
                IconSizeSample("xl", MasroofIconSizes.xl)
                IconSizeSample("logo", MasroofIconSizes.logo)
                IconSizeSample("hero", MasroofIconSizes.hero)
            }
        }
    }
}

@Composable
private fun IconSizeSample(
    label: String,
    size: Dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap),
    ) {
        Icon(
            imageVector = MasroofIcons.appLogo,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("${size.value.toInt()}dp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ComponentSection() {
    CatalogSection(title = stringResource(R.string.settings_design_catalog_components)) {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            MasroofCard() {
                Text("MasroofCard · Account", style = MaterialTheme.typography.bodyMedium)
            }
            MasroofCard() {
                Text("MasroofCard · Credit", style = MaterialTheme.typography.bodyMedium)
            }
            MasroofCard() {
                Text("MasroofCard · Inflow", style = MaterialTheme.typography.bodyMedium)
            }
            MasroofMoneyRow(
                label = "Inflow row",
                value = "1,250.00",
                style = MasroofMoneyRowStyle.Inflow,
                leadingIcon = MasroofIcons.externalIn,
            )
            MasroofMoneyRow(
                label = "Outflow row",
                value = "320.50",
                style = MasroofMoneyRowStyle.Outflow,
                leadingIcon = MasroofIcons.externalOut,
            )
            MasroofAmountText(
                amount = "4,500.00",
                role = MasroofAmountRole.Hero,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
                MasroofBadge(text = "Badge", accent = MasroofCardAccent.Account)
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        "3",
                        modifier = Modifier.padding(
                            horizontal = MasroofSpacing.badgeHorizontalPadding,
                            vertical = MasroofSpacing.badgeVerticalPadding,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            IconLabelRow(
                icon = MasroofIcons.periodHint,
                label = "IconLabelRow",
                iconTint = MaterialTheme.colorScheme.primary,
            )
            MasroofHorizontalBar(progress = 0.75f)
            MasroofHorizontalBar(
                progress = 0.45f,
                style = MasroofHorizontalBarStyle.Outflow,
            )
            MasroofRankedBarRow(
                title = "Ranked bar row",
                value = "1,250.00",
                progress = MasroofBarChart.progress(
                    java.math.BigDecimal("1250"),
                    java.math.BigDecimal("2000"),
                ),
                subtitle = "5 purchase / payment transactions",
                style = MasroofHorizontalBarStyle.Outflow,
            )
            val previewValues = remember {
                listOf(
                    java.math.BigDecimal("240"),
                    java.math.BigDecimal("90"),
                    java.math.BigDecimal("380"),
                    java.math.BigDecimal("140"),
                    java.math.BigDecimal("275"),
                )
            }
            var selectedPointIndex by remember { mutableIntStateOf(2) }
            MasroofInteractiveLineChart(
                values = previewValues,
                referenceValue = java.math.BigDecimal("225"),
                selectedPointIndex = selectedPointIndex,
                onPointSelected = { selectedPointIndex = it },
                pointLabel = { index -> "Day ${index + 1}: ${previewValues[index]}" },
            )
            MasroofDonutChart(
                paidFraction = commitmentDonutPaidFraction(
                    java.math.BigDecimal("1000"),
                    java.math.BigDecimal("650"),
                ),
                centerTitle = "Total",
                centerValue = "1,000.00",
            )
        }
    }
}
