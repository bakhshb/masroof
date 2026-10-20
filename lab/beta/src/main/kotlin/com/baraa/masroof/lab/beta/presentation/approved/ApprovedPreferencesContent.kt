package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*

/** Content copied from production SettingsAppScreen; shell navigation remains owned by the lab. */
@Composable
internal fun ApprovedPreferencesContent(languageTag: String, themeMode: String, enabled: Boolean,
    onSelectLanguage: (String)->Unit,onSelectTheme: (String)->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            MasroofCard {
                ApprovedText(stringResource(R.string.beta_approved_settings_theme_title), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.padding(vertical = MasroofSpacing.sectionGap), horizontalArrangement = Arrangement.spacedBy(MasroofReference.shortcutGap)) {
                    listOf(Triple("LIGHT", R.string.beta_approved_settings_theme_light, MasroofOutlineIcons.Sun),
                        Triple("DARK", R.string.beta_approved_settings_theme_dark, MasroofOutlineIcons.Moon),
                        Triple("SYSTEM", R.string.beta_approved_settings_theme_system, MasroofOutlineIcons.Monitor)).forEach { (mode, label, icon) ->
                        ApprovedChoice(stringResource(label), themeMode == mode, { onSelectTheme(mode) }, icon = icon, enabled=enabled, modifier=Modifier.testTag("settings-theme-$mode"))
                    }
                }
            }
            MasroofCard {
                ApprovedText(stringResource(R.string.beta_approved_settings_language_title), style = MaterialTheme.typography.titleMedium)
                listOf("ar" to R.string.beta_approved_settings_language_arabic, "en" to R.string.beta_approved_settings_language_english).forEach { (tag, label) ->
                    val current = tag.startsWith("en") == languageTag.startsWith("en")
                    Row(Modifier.fillMaxWidth().clickable(enabled=enabled) { onSelectLanguage(tag) }.testTag("settings-language-$tag").padding(vertical = MasroofReference.rowVertical),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        ApprovedText(stringResource(label), style = MasroofReference.pair)
                        if (current) ApprovedBadge(stringResource(R.string.beta_approved_reference_current_language), MasroofCardAccent.Inflow)
                        else ApprovedText(stringResource(R.string.beta_approved_reference_available_language), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.cardBorder)
                }
            }
            MasroofCard {
                ApprovedText(stringResource(R.string.beta_approved_reference_colour_meanings), style = MaterialTheme.typography.titleMedium)
                val colors = com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors
                val legends = listOf(Triple(R.string.beta_approved_dashboard_total_inflow, MasroofOutlineIcons.ArrowDownLeft, colors.inflow to colors.inflowSoft),
                    Triple(R.string.beta_approved_approved_total_expenses, MasroofOutlineIcons.ArrowUpRight, colors.outflow to colors.outflowSoft),
                    Triple(R.string.beta_approved_reference_credit_tab, MasroofOutlineIcons.CreditCard, colors.card to colors.cardSoft),
                    Triple(R.string.beta_approved_approved_commitments, MasroofOutlineIcons.Clock3, colors.liability to colors.liabilitySoft))
                legends.forEach { (label, icon, palette) ->
                    Row(Modifier.padding(top = MasroofSpacing.sectionGap), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
                        Surface(shape = com.baraa.masroof.ui.theme.MasroofShapes.small, color = palette.second) {
                            androidx.compose.foundation.layout.Box(Modifier.size(MasroofReference.iconBox), contentAlignment = Alignment.Center) {
                                Icon(icon, null, Modifier.size(MasroofReference.icon), tint = palette.first)
                            }
                        }
                        ApprovedText(stringResource(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
    }
}
