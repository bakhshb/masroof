package com.baraa.masroof.lab.beta.presentation

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedBottomSheet

/** Beta labels/callbacks only. The former Material3 modal implementation has been removed. */
@Composable
internal fun BetaBottomSheet(title: String,busy: Boolean,onCancel: () -> Unit,
    onApply: () -> Unit,canApply: Boolean = true,applyTag: String = "dash-sheet-apply",cancelTag: String = "dash-sheet-cancel",applyLabel: String? = null,content: @Composable ColumnScope.() -> Unit) =
    ApprovedBottomSheet(title,onCancel,onApply,applyLabel ?: stringResource(R.string.beta_controls_apply),
        canApply=canApply,busy=busy,sheetTag="dash-sheet",bodyTag="dash-sheet-body",applyTag=applyTag,cancelTag=cancelTag,content=content)
