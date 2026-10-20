package com.baraa.masroof.lab.beta.presentation.approved

import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Full-window modal host; only the inner sheet has a maximum width. */
@Composable
internal fun ApprovedBottomSheet(
    title: String, onDismiss: () -> Unit, onApply: () -> Unit, applyLabel: String,
    subtitle: String? = null, canApply: Boolean = true, busy: Boolean = false,
    resetLabel: String? = null, onReset: (() -> Unit)? = null,
    sheetTag: String = "masroof-bottom-sheet", bodyTag: String = "sheet-body",
    applyTag: String = "sheet-apply", cancelTag: String = "sheet-cancel",
    content: @Composable ColumnScope.() -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val parentDirection = LocalLayoutDirection.current
    val currentBusy by rememberUpdatedState(busy)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    var dragPixels by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { MasroofSheetTokens.dismissDragDistance.toPx() }
    fun settleDrag() {
        if (!currentBusy && dragPixels >= threshold) currentDismiss()
        else scope.launch { drag.snapTo(dragPixels); settling = true
            drag.animateTo(0f, tween(MasroofSheetTokens.motionMillis)); dragPixels = 0f; settling = false }
    }
    val dragState = rememberDraggableState { delta ->
        if (!currentBusy) dragPixels = (dragPixels + delta).coerceAtLeast(0f)
    }
    val nestedScroll = remember(threshold) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (currentBusy || dragPixels <= 0f || available.y >= 0f) return Offset.Zero
                val consumed = available.y.coerceAtLeast(-dragPixels)
                dragPixels += consumed
                return Offset(0f, consumed)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (currentBusy || available.y <= 0f || source != NestedScrollSource.Drag) return Offset.Zero
                dragPixels += available.y
                return Offset(0f, available.y)
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                settleDrag()
                return Velocity.Zero
            }
        }
    }
    Dialog(onDismissRequest = { if (!currentBusy) currentDismiss() }, properties = DialogProperties(
        dismissOnBackPress = !busy, dismissOnClickOutside = false,
        usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        // Do not derive the Android window width from locale-wrapped DisplayMetrics.
        // MATCH_PARENT follows the current Activity window, including split-screen and folding.
        // Keep DialogLayout's platform measurement path: Compose 1.6's false branch
        // substitutes Configuration-derived specs and rewrites Window size on every layout.
        // The explicit MATCH_PARENT below supplies full-window specs to the true branch.
        val view = LocalView.current
        val window = remember(view) {
            generateSequence(view as View) { it.parent as? View }
                .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        }
        SideEffect {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        CompositionLocalProvider(LocalContext provides parentContext, LocalConfiguration provides parentConfiguration,
            LocalLayoutDirection provides parentDirection) {
            Box(Modifier.fillMaxSize().testTag("sheet-overlay")) {
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = MasroofSheetTokens.scrimAlpha))
                    .clickable(enabled = !busy, interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = stringResource(R.string.beta_approved_sheet_close_without_saving)) { currentDismiss() }
                    .testTag("sheet-scrim"))
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    val availableHeight = maxHeight * MasroofSheetTokens.windowHeightFraction
                    LaunchedEffect(maxWidth, maxHeight) {
                        // A resize cancels the old drag and anchors the sheet in its new window.
                        dragPixels = 0f
                        drag.snapTo(0f)
                        settling = false
                    }
                    Surface(modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = MasroofSheetTokens.maxWidth)
                        .fillMaxWidth().heightIn(max = availableHeight)
                        .offset { IntOffset(0, (if (settling) drag.value else dragPixels).roundToInt()) }.nestedScroll(nestedScroll)
                        .testTag(sheetTag), shape = MasroofSheetTokens.shape,
                        color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = MasroofElevation.sheetTonal) {
                        Column(Modifier.fillMaxWidth().heightIn(max = availableHeight)) {
                            Box(Modifier.fillMaxWidth().height(MasroofSpacing.minimumTouchTarget)
                                .draggable(dragState, Orientation.Vertical, enabled = !busy, onDragStopped = { settleDrag() })
                                .testTag("sheet-drag-handle"), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(MasroofSheetTokens.handleWidth, MasroofSheetTokens.handleHeight)
                                    .background(MasroofThemeExtras.extendedColors.cardBorder, MasroofComponentShapes.radius999))
                            }
                Row(Modifier.fillMaxWidth().draggable(dragState, Orientation.Vertical, enabled = !busy,
                    onDragStopped = { settleDrag() }).padding(horizontal = MasroofSheetTokens.horizontalPadding),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        ApprovedText(title, style = MaterialTheme.typography.titleMedium, fontWeight = MasroofFontWeights.bold)
                        subtitle?.let { ApprovedText(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    IconButton(onClick = onDismiss, enabled = !busy,
                        modifier = Modifier.testTag("sheet-close")) {
                        Icon(Icons.Default.Close, stringResource(R.string.beta_approved_sheet_close_without_saving), Modifier.size(MasroofIconSizes.md))
                    }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = MasroofSheetTokens.horizontalPadding,
                        vertical = MasroofSheetTokens.sectionGap).testTag(bodyTag),
                    verticalArrangement = Arrangement.spacedBy(MasroofSheetTokens.sectionGap), content = content)
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = MasroofSheetTokens.horizontalPadding, vertical = MasroofSpacing.sectionHeaderGap)
                    .testTag("sheet-actions")) {
                    if (onReset != null && resetLabel != null) ApprovedTextButton(onClick = onReset, enabled = !busy,
                        modifier = Modifier.testTag("sheet-reset")) { ApprovedText(resetLabel) }
                    Row(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                        ApprovedOutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f)
                            .heightIn(min = MasroofSpacing.minimumTouchTarget).testTag(cancelTag),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                            ApprovedText(stringResource(R.string.beta_approved_sheet_cancel))
                        }
                        ApprovedButton(onClick = onApply, enabled = canApply && !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = MasroofPalette.Primary,
                                contentColor = MasroofLightColors.OnPrimary),
                            modifier = Modifier.weight(1f).heightIn(min = MasroofSpacing.minimumTouchTarget).testTag(applyTag)) {
                            ApprovedText(applyLabel)
                        }
                    }
                }
                        }
                    }
                }
            }
        }
    }
}
