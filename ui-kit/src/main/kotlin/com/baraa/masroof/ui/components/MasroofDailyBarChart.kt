package com.baraa.masroof.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.baraa.masroof.ui.theme.*
import java.math.BigDecimal

/** Null values reserve their day without drawing a zero bar or bridging a coverage gap. */
data class MasroofDailyBar(val value: BigDecimal?, val expected: Boolean, val label: String, val axisLabel: String)

@Composable
fun MasroofDailyBarChart(points: List<MasroofDailyBar>, selectedIndex: Int, onSelected: (Int) -> Unit,
    previousLabel: String, nextLabel: String, axisValue: (BigDecimal) -> String, modifier: Modifier = Modifier,
    average: BigDecimal? = null) {
    if (points.isEmpty()) return
    val axisIndices = androidx.compose.runtime.remember(points.size) { MasroofAnalyticsChartMath.axisIndices(points.size) }
    val selected = selectedIndex.coerceIn(points.indices)
    val range = MasroofLineChart.referenceRange(points.mapNotNull { it.value } + listOfNotNull(average))
    val actualColor = MasroofThemeExtras.extendedColors.outflow
    val futureColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textStyle = MaterialTheme.typography.labelSmall.copy(color = textColor)
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inset = with(density) { MasroofAnalyticsTokens.chartInset.toPx() }
    val endInset = with(density) { MasroofAnalyticsTokens.chartEndInset.toPx() }
    val mirror = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Canvas(modifier.fillMaxWidth().height(MasroofSpacing.chartHeight)
        .semantics {
            contentDescription = points[selected].label
            customActions = listOf(
                CustomAccessibilityAction(previousLabel) { onSelected((selected - 1).coerceAtLeast(0)); true },
                CustomAccessibilityAction(nextLabel) { onSelected((selected + 1).coerceAtMost(points.lastIndex)); true })
        }
        .pointerInput(points, mirror) {
            detectTapGestures { tap -> MasroofAnalyticsChartMath.barAt(tap.x - inset, size.width - inset - endInset, points.size)
                ?.let { onSelected(MasroofAnalyticsChartMath.barSlot(it, points.size, mirror)) } }
        }) {
        val top = MasroofAnalyticsTokens.chartTopInset.toPx()
        val bottom = size.height - MasroofAnalyticsTokens.chartBottomInset.toPx()
        val width = (size.width - inset - endInset).coerceAtLeast(1f)
        fun y(value: BigDecimal) = bottom - value.subtract(range.minimum).divide(range.span, 8, java.math.RoundingMode.HALF_UP).toFloat() * (bottom - top)
        val zero = y(BigDecimal.ZERO)
        val cell = width / points.size
        repeat(MasroofAnalyticsTokens.gridIntervals + 1) { index ->
            val value = range.minimum + range.span.multiply(BigDecimal(index)).divide(BigDecimal(MasroofAnalyticsTokens.gridIntervals))
            val yy = y(value)
            drawLine(gridColor, Offset(inset, yy), Offset(inset + width, yy), MasroofSpacing.borderWidth.toPx())
            val label = measurer.measure(axisValue(value), textStyle)
            drawText(label, topLeft = Offset((inset - label.size.width - MasroofSpacing.inlineGap.toPx()).coerceAtLeast(0f), yy - label.size.height / 2))
        }
        // Dashed like the Home average line; the caller states the average in text as well.
        average?.let { value ->
            drawLine(textColor, Offset(inset, y(value)), Offset(inset + width, y(value)), MasroofSpacing.borderWidth.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(MasroofSpacing.smallGap.toPx(), MasroofSpacing.inlineGap.toPx())))
        }
        points.forEachIndexed { index, point ->
            val x = inset + MasroofAnalyticsChartMath.barSlot(index, points.size, mirror) * cell + cell * (1 - MasroofAnalyticsTokens.barWidthFraction) / 2
            point.value?.let { value ->
                val yy = y(value)
                val rect = Size(cell * MasroofAnalyticsTokens.barWidthFraction, kotlin.math.abs(yy - zero).coerceAtLeast(MasroofSpacing.borderWidth.toPx()))
                val start = Offset(x, minOf(yy, zero))
                if (point.expected) {
                    drawRect(futureColor, start, rect, style = Stroke(MasroofSpacing.borderWidth.toPx()))
                    // Pattern plus label, so estimated bars remain identifiable without color.
                    var stripe = start.y
                    while (stripe < start.y + rect.height) {
                        drawLine(futureColor, Offset(x, stripe), Offset(x + rect.width, (stripe + rect.width).coerceAtMost(start.y + rect.height)), MasroofSpacing.borderWidth.toPx())
                        stripe += MasroofSpacing.smallGap.toPx()
                    }
                } else drawRect(actualColor, start, rect)
            }
            if (index == selected) drawLine(textColor, Offset(x + cell * MasroofAnalyticsTokens.barWidthFraction / 2, top), Offset(x + cell * MasroofAnalyticsTokens.barWidthFraction / 2, bottom), MasroofSpacing.borderWidth.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(MasroofSpacing.inlineGap.toPx(), MasroofSpacing.inlineGap.toPx())))
            if (index in axisIndices) {
                val label = measurer.measure(point.axisLabel, textStyle)
                drawText(label, topLeft = Offset((x + cell * MasroofAnalyticsTokens.barWidthFraction / 2 - label.size.width / 2).coerceIn(inset, (size.width - label.size.width).coerceAtLeast(inset)), bottom + MasroofSpacing.inlineGap.toPx()))
            }
        }
    }
}
