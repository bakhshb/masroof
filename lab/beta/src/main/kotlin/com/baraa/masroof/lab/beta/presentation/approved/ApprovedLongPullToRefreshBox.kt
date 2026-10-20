package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofSizes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlin.math.min
import kotlin.math.roundToInt

/** Pull distance before refresh can arm (~ deliberate long pull). */
val ApprovedLongPullToRefreshThreshold: Dp = MasroofSizes.size180

/** Must stay at/above [ApprovedLongPullToRefreshThreshold] this long before release triggers refresh. */
const val ApprovedLongPullToRefreshHoldMs: Long = 400L

/**
 * Pull-to-refresh: drag down past [threshold], **hold** for [holdDurationMs], then release.
 * Normal scrolling does not refresh — only overscroll at the top (not consumed by the list) counts.
 */
@Composable
fun ApprovedLongPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    threshold: Dp = ApprovedLongPullToRefreshThreshold,
    holdDurationMs: Long = ApprovedLongPullToRefreshHoldMs,
    content: @Composable BoxScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    var pullDistance by remember { mutableFloatStateOf(0f) }
    var thresholdHeldSince by remember { mutableStateOf<Long?>(null) }
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    val maxPullPx = thresholdPx * 1.25f
    val isRefreshingState = rememberUpdatedState(isRefreshing)
    val onRefreshState = rememberUpdatedState(onRefresh)
    val scrollOffsetState = rememberUpdatedState(scrollState.value)

    LaunchedEffect(pullDistance, thresholdPx) {
        if (pullDistance >= thresholdPx) {
            if (thresholdHeldSince == null) {
                thresholdHeldSince = System.currentTimeMillis()
            }
        } else {
            thresholdHeldSince = null
        }
    }

    LaunchedEffect(scrollState.value) {
        if (scrollState.value > 0) {
            pullDistance = 0f
            thresholdHeldSince = null
        }
    }

    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) {
            pullDistance = 0f
            thresholdHeldSince = null
        }
    }

    val nestedScrollConnection = remember(thresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pullDistance > 0f && available.y < 0f) {
                    val consumed = min(-available.y, pullDistance)
                    pullDistance -= consumed
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (
                    !isRefreshingState.value &&
                    scrollOffsetState.value == 0 &&
                    consumed.y == 0f &&
                    available.y > 0f &&
                    source == NestedScrollSource.Drag
                ) {
                    pullDistance = min(pullDistance + available.y, maxPullPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!isRefreshingState.value && pullDistance > 0f && pullDistance < thresholdPx) {
                    pullDistance = 0f
                    thresholdHeldSince = null
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(thresholdPx, holdDurationMs) {
                awaitEachGesture {
                    var cancelledByScroll = false
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        if (scrollOffsetState.value > 0) {
                            cancelledByScroll = true
                            pullDistance = 0f
                            thresholdHeldSince = null
                        }
                        val event = awaitPointerEvent(PointerEventPass.Final)
                    } while (event.changes.any { it.pressed })

                    if (
                        !isRefreshingState.value &&
                        !cancelledByScroll &&
                        scrollOffsetState.value == 0
                    ) {
                        val heldMs = thresholdHeldSince?.let { System.currentTimeMillis() - it } ?: 0L
                        val shouldRefresh =
                            pullDistance >= thresholdPx && heldMs >= holdDurationMs
                        if (shouldRefresh) {
                            onRefreshState.value()
                        }
                    }
                    pullDistance = 0f
                    thresholdHeldSince = null
                }
            }
            .nestedScroll(nestedScrollConnection),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .then(
                    if (pullDistance > 0f) {
                        Modifier.offset { IntOffset(0, pullDistance.roundToInt()) }
                    } else {
                        Modifier
                    },
                ),
            content = content,
        )

        if (pullDistance > 0f || isRefreshing) {
            val atThreshold = pullDistance >= thresholdPx
            val heldMs = thresholdHeldSince?.let { System.currentTimeMillis() - it } ?: 0L
            val holdComplete = atThreshold && heldMs >= holdDurationMs
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = MasroofSpacing.sectionGap)
                    .alpha(
                        when {
                            isRefreshing -> 1f
                            holdComplete -> 1f
                            atThreshold -> 0.85f
                            else -> min(1f, pullDistance / thresholdPx)
                        },
                    ),
                color = when {
                    isRefreshing -> MaterialTheme.colorScheme.primary
                    holdComplete -> MaterialTheme.colorScheme.secondary
                    atThreshold -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}
