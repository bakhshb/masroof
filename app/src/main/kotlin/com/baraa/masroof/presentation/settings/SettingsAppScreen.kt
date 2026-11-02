
package com.baraa.masroof.presentation.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofReference

@Composable
fun SettingsAppScreen(languageTag: String, themeMode: ThemeMode, onBack: () -> Unit,
    onSelectLanguage: (String) -> Unit, onSelectTheme: (ThemeMode) -> Unit) {
    MasroofSecondaryScaffold(stringResource(R.string.reference_preferences_title), onBack,
        stringResource(R.string.settings_back)) { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            MasroofCard {
                MasroofText(stringResource(R.string.settings_theme_title), style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = MasroofSpacing.sectionGap), horizontalArrangement = Arrangement.spacedBy(MasroofReference.shortcutGap), verticalArrangement = Arrangement.spacedBy(MasroofReference.shortcutGap)) {
                    listOf(Triple(ThemeMode.LIGHT, R.string.settings_theme_light, MasroofIcons.sun),
                        Triple(ThemeMode.DARK, R.string.settings_theme_dark, MasroofIcons.theme),
                        Triple(ThemeMode.SYSTEM, R.string.settings_theme_system, MasroofIcons.monitor)).forEach { (mode, label, icon) ->
                        MasroofChoice(stringResource(label), themeMode == mode, { onSelectTheme(mode) }, icon = icon)
                    }
                }
            }
            MasroofCard {
                MasroofText(stringResource(R.string.settings_language_title), style = MaterialTheme.typography.titleMedium)
                listOf(AppLocale.TAG_AR to R.string.settings_language_arabic, AppLocale.TAG_EN to R.string.settings_language_english).forEach { (tag, label) ->
                    val current = AppLocale.isEnglish(tag) == AppLocale.isEnglish(languageTag)
                    Row(Modifier.fillMaxWidth().selectable(selected = current, role = Role.RadioButton) { onSelectLanguage(tag) }.padding(vertical = MasroofReference.rowVertical),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        MasroofText(stringResource(label), style = MasroofReference.pair)
                        if (current) MasroofBadge(stringResource(R.string.reference_current_language), MasroofCardAccent.Inflow)
                        else MasroofText(stringResource(R.string.reference_available_language), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.cardBorder)
                }
            }
            MasroofCard {
                MasroofText(stringResource(R.string.reference_colour_meanings), style = MaterialTheme.typography.titleMedium)
                val colors = com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors
                val legends = listOf(Triple(R.string.dashboard_total_inflow, MasroofIcons.externalIn, colors.inflow to colors.inflowSoft),
                    Triple(R.string.approved_total_expenses, MasroofIcons.externalOut, colors.outflow to colors.outflowSoft),
                    Triple(R.string.reference_credit_tab, MasroofIcons.cardPayment, colors.card to colors.cardSoft),
                    Triple(R.string.approved_commitments, MasroofIcons.clock, colors.liability to colors.liabilitySoft))
                legends.forEach { (label, icon, palette) ->
                    Row(Modifier.padding(top = MasroofSpacing.sectionGap), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
                        Surface(shape = com.baraa.masroof.ui.theme.MasroofShapes.small, color = palette.second) {
                            androidx.compose.foundation.layout.Box(Modifier.size(MasroofReference.iconBox), contentAlignment = Alignment.Center) {
                                Icon(icon, null, Modifier.size(MasroofReference.icon), tint = palette.first)
                            }
                        }
                        MasroofText(stringResource(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
