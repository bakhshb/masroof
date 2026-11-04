package com.baraa.masroof.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.locale.AppDigits

/** Days counted as "soon": amber from two days out, so a bill never reads calm on the day it is due. */
internal const val DUE_SOON_DAYS = 2L

/** "Today", "Tomorrow", "In N days" or "N days late" with its accent; shared by Commitments and analysis. */
@Composable internal fun dueCountdown(days: Long): Pair<String, MasroofCardAccent> {
    val accent = if (days <= DUE_SOON_DAYS) MasroofCardAccent.Liability else MasroofCardAccent.None
    return AppDigits.display(when {
        days < 0 -> stringResource(R.string.analysis_due_overdue, -days)
        days == 0L -> stringResource(R.string.analysis_due_today)
        days == 1L -> stringResource(R.string.analysis_due_tomorrow)
        else -> stringResource(R.string.analysis_due_in_days, days)
    }) to if (days < 0) MasroofCardAccent.Outflow else accent
}
