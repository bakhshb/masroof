package com.baraa.masroof.lab.beta.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedChoiceContent

/** Compatibility binding; the approved renderer owns all choice geometry. */
@Composable
internal fun BetaChoice(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, label: @Composable () -> Unit) =
    ApprovedChoiceContent(selected, onClick, modifier, enabled, label)
