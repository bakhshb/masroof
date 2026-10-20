package com.baraa.masroof.lab.beta.presentation.approved

import com.baraa.masroof.ui.components.MasroofOutlineIcons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.ui.theme.*

@Composable
fun ApprovedPeriodDisplay(label: String, modifier: Modifier = Modifier, guidance: String? = null) {
    Surface(modifier.padding(bottom = MasroofSpacing.inlineGap).fillMaxWidth(), shape = MasroofShapes.small,
        border = BorderStroke(MasroofSpacing.borderWidth, MasroofThemeExtras.extendedColors.cardBorder)) {
        ReferencePeriodContent(label, guidance)
    }
}

@Composable
private fun ReferencePeriodContent(label: String, guidance: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = MasroofSpacing.periodDisplayHorizontalPadding + MasroofSpacing.borderWidth,
        vertical = MasroofReference.navigationItemVertical + MasroofSpacing.borderWidth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Icon(MasroofOutlineIcons.CalendarDays, null, Modifier.size(MasroofReference.icon))
        // Reserve the trailing label before measuring a long translated date.
        // Otherwise it can receive zero width and make the entire toolbar screen-height.
        ApprovedText(label, modifier = Modifier.weight(1f).padding(horizontal = MasroofSpacing.inlineGap),
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApprovedText(stringResource(R.string.beta_parity_salary_period), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            guidance?.let { ApprovedGuidanceLabel("", it) }
        }
    }
}

/** Same display range as production FinancialPeriodUiFormatter; the workflow owns period boundaries. */
internal fun approvedPeriodRange(from: java.time.LocalDate,endInclusive: java.time.LocalDate,
    locale: java.util.Locale,compact: Boolean=false): String {
    val dayMonth=java.time.format.DateTimeFormatter.ofPattern(if(compact) "d MMM" else "d MMMM",locale)
    return "${dayMonth.format(from)} — ${dayMonth.format(endInclusive)} ${endInclusive.year}"
}
