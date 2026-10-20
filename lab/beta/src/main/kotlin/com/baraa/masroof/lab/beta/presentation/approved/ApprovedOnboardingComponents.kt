package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.history.BetaImportDateOption
import com.baraa.masroof.ui.components.MasroofOutlineIcons
import com.baraa.masroof.ui.dashboard.DashboardSectionHeading
import com.baraa.masroof.ui.theme.*
import java.time.LocalDate

// Port of ImportDateStep/DateOptionRow; the Beta adapter owns permission and durable scan state.
@Composable
internal fun ApprovedImportDateStep(option: BetaImportDateOption,selected: LocalDate,busy: Boolean,
    onSelectDateOption: (BetaImportDateOption)->Unit,onCustomDate: ()->Unit,onContinue: ()->Unit,
    modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(MasroofSpacing.screenPaddingLarge)) {
        DashboardSectionHeading(
            title = stringResource(R.string.beta_approved_onboarding_import_date_title),
            icon = MasroofOutlineIcons.CalendarCheck,
        )
        Spacer(Modifier.height(MasroofSpacing.screenVertical))
        ApprovedDateOptionRow(option == BetaImportDateOption.CURRENT_MONTH_START, stringResource(R.string.beta_approved_onboarding_date_current_month),!busy,"history-date-CURRENT_MONTH_START") {
            onSelectDateOption(BetaImportDateOption.CURRENT_MONTH_START)
        }
        ApprovedDateOptionRow(option == BetaImportDateOption.LAST_30_DAYS, stringResource(R.string.beta_approved_onboarding_date_last_30),!busy,"history-date-LAST_30_DAYS") {
            onSelectDateOption(BetaImportDateOption.LAST_30_DAYS)
        }
        ApprovedDateOptionRow(option == BetaImportDateOption.LAST_27TH, stringResource(R.string.beta_approved_onboarding_date_last_27),!busy,"history-date-LAST_27TH") {
            onSelectDateOption(BetaImportDateOption.LAST_27TH)
        }
        ApprovedDateOptionRow(option == BetaImportDateOption.CUSTOM, stringResource(R.string.beta_approved_onboarding_date_custom),!busy,"history-date-CUSTOM") {
            onCustomDate()
        }
        Spacer(Modifier.height(MasroofSpacing.sectionGap))
        ApprovedText(stringResource(R.string.beta_approved_onboarding_selected_date, selected.toString()))
        Spacer(Modifier.height(MasroofSpacing.largeGap))
        ApprovedIconTextButton(
            onClick = onContinue,
            enabled = !busy,
            icon = MasroofOutlineIcons.RefreshCw,
            text = stringResource(R.string.beta_approved_onboarding_start_import),
            modifier = Modifier.fillMaxWidth().testTag("history-start"),
        )
    }

}

@Composable
private fun ApprovedDateOptionRow(selected: Boolean,title: String,enabled: Boolean,tag: String,onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(selected = selected, onClick = onClick,enabled=enabled,modifier=Modifier.testTag(tag))
        Icon(
            imageVector = MasroofOutlineIcons.CalendarCheck,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MasroofIconSizes.moneyRowLeading),
        )
        Spacer(Modifier.size(MasroofSpacing.sectionHeaderGap))
        ApprovedText(title, modifier = Modifier.padding(top = MasroofSpacing.hairlineGap))
    }
}
