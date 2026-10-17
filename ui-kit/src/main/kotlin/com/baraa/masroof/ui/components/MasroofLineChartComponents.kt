package com.baraa.masroof.ui.components

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.baraa.masroof.ui.theme.MasroofBadgeShape
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofThemeExtras
import java.math.BigDecimal
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import com.baraa.masroof.ui.theme.MasroofReference
import com.baraa.masroof.ui.theme.MasroofHeroRole
import kotlin.math.roundToInt

data class LineChartLabels(val dayAxis: String, val previous: String, val next: String, val averageAnnouncement: String)

object MasroofLineChart {
    data class ValueRange(
        val minimum: BigDecimal,
        val maximum: BigDecimal,
    ) {
        val span: BigDecimal
            get() = maximum.subtract(minimum)
    }

    fun valueRange(values: List<BigDecimal>, reference: BigDecimal): ValueRange {
        val allValues = values + reference + BigDecimal.ZERO
        val minimum = allValues.minOrNull() ?: BigDecimal.ZERO
        val maximum = allValues.maxOrNull() ?: BigDecimal.ZERO
        return if (minimum == maximum) {
            ValueRange(BigDecimal.ZERO, BigDecimal.ONE)
        } else {
            ValueRange(minimum, maximum)
        }
    }

    /** This is visual axis scaling only, matching the approved SVG headroom. */
    fun referenceRange(values: List<BigDecimal>): ValueRange {
        val min = (values + BigDecimal.ZERO).minOrNull() ?: BigDecimal.ZERO
        val max = (values + BigDecimal.ZERO).maxOrNull() ?: BigDecimal.ZERO
        val hundred = BigDecimal("100")
        fun headroom(value: BigDecimal) = value.abs().multiply(BigDecimal("1.1"))
            .divide(hundred, 0, java.math.RoundingMode.CEILING).multiply(hundred)
        val low = if (min.signum() < 0) headroom(min).negate() else BigDecimal.ZERO
        val high = if (max.signum() > 0) headroom(max) else if (low.signum() < 0) BigDecimal.ZERO else BigDecimal.ONE
        return ValueRange(low, high)
    }

    fun nearestPointIndex(x: Float, width: Float, pointCount: Int): Int? {
        if (pointCount <= 0 || width <= 0f) return null
        if (pointCount == 1) return 0
        val step = width / (pointCount - 1)
        return (x / step).roundToInt().coerceIn(0, pointCount - 1)
    }
}

internal object MasroofLineChartLayout {
    fun xForPoint(
        index: Int,
        width: Float,
        pointCount: Int,
        mirrorHorizontally: Boolean = false,
    ): Float {
        val logicalX = if (pointCount <= 1) 0f else index * (width / (pointCount - 1))
        return if (mirrorHorizontally) width - logicalX else logicalX
    }

    fun touchXForNearestPoint(
        touchX: Float,
        width: Float,
        mirrorHorizontally: Boolean,
    ): Float = if (mirrorHorizontally) width - touchX else touchX

    fun yFor(
        value: BigDecimal,
        range: MasroofLineChart.ValueRange,
        top: Float,
        chartHeight: Float,
    ): Float {
        val span = range.span.toFloat()
        val fraction = value.subtract(range.minimum).toFloat() / span
        return (top + chartHeight) - (fraction * chartHeight)
    }

    fun tooltipLeft(
        anchorX: Float,
        tooltipWidth: Float,
        chartWidth: Float,
        horizontalPadding: Float,
    ): Float {
        if (tooltipWidth <= 0f) return anchorX
        val maxLeft = (chartWidth - tooltipWidth - horizontalPadding).coerceAtLeast(horizontalPadding)
        return (anchorX - tooltipWidth / 2f).coerceIn(horizontalPadding, maxLeft)
    }

    fun coerceSelectedIndex(index: Int?, pointCount: Int): Int? {
        if (pointCount <= 0) return null
        return index?.takeIf { it in 0 until pointCount }
    }
}

@Composable
fun MasroofLineChart(values: List<BigDecimal>, referenceValue: BigDecimal, labels: LineChartLabels,
    modifier: Modifier = Modifier, selectedPointIndex: Int? = null,
    onPointSelected: ((Int) -> Unit)? = null, pointLabel: (Int) -> String = { "" },
    role: MasroofHeroRole = MasroofHeroRole.Brand,
    axisLabel: (Int) -> String = { "" }, currencyLabel: String = "", showAverage: Boolean = false, displayText: (String) -> String = { it }) {
    MasroofInteractiveLineChart(values, referenceValue, labels, modifier, selectedPointIndex,
        onPointSelected, pointLabel, role, axisLabel, currencyLabel, showAverage, displayText)
}

@Composable
fun MasroofInteractiveLineChart(values: List<BigDecimal>, referenceValue: BigDecimal, labels: LineChartLabels,
    modifier: Modifier = Modifier, selectedPointIndex: Int? = null,
    onPointSelected: ((Int) -> Unit)? = null, pointLabel: (Int) -> String = { "" },
    role: MasroofHeroRole = MasroofHeroRole.Brand,
    axisLabel: (Int) -> String = { "" }, currencyLabel: String = "", showAverage: Boolean = false, displayText: (String) -> String = { it }) {
    if (values.isEmpty()) return
    val selected = MasroofLineChartLayout.coerceSelectedIndex(selectedPointIndex, values.size)
    val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl
    val line = MasroofReference.chartColor(role)
    val grid = MasroofThemeExtras.extendedColors.cardBorder
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MasroofReference.chartLabel.copy(color = muted)
    val range = remember(values, referenceValue, showAverage) {
        MasroofLineChart.referenceRange(if (showAverage) values + referenceValue else values)
    }
    val density = LocalDensity.current
    val left = with(density) { MasroofReference.chartLeft.toPx() }
    val right = with(density) { MasroofReference.chartRight.toPx() }
    val top = with(density) { MasroofReference.chartTop.toPx() }
    val bottom = with(density) { MasroofReference.chartBottom.toPx() }
    val stroke = with(density) { MasroofReference.chartStroke.toPx() }
    val radius = with(density) { MasroofReference.chartPoint.toPx() }
    val selectedRadius = with(density) { MasroofReference.selectedPoint.toPx() }
    val dash = with(density) { MasroofReference.chartDash.toPx() }
    val dashStroke = with(density) { MasroofReference.chartDashStroke.toPx() }
    val unitLeft = with(density) { MasroofReference.axisUnitLeft.toPx() }
    val unitBaseline = with(density) { MasroofReference.axisUnitBaseline.toPx() }
    val tickOffset = with(density) { MasroofReference.axisLabelOffset.toPx() }
    val tickBaseline = with(density) { MasroofReference.axisLabelBaselineOffset.toPx() }
    val dateBaseline = with(density) { MasroofReference.dateBaselineOffset.toPx() }
    val dayBottom = with(density) { MasroofReference.axisDayBottom.toPx() }
    val gridStroke = with(density) { MasroofSpacing.borderWidth.toPx() }
    val dayAxis = labels.dayAxis
    val previousLabel = labels.previous
    val nextLabel = labels.next
    val averageAnnouncement = labels.averageAnnouncement
    val semantics = Modifier.semantics {
        selected?.let { contentDescription = displayText(pointLabel(it)) +
            if (showAverage) " · $averageAnnouncement" else "" }
        if (onPointSelected != null && selected != null) customActions = buildList {
            if (selected > 0) add(CustomAccessibilityAction(previousLabel) { onPointSelected(selected - 1); true })
            if (selected < values.lastIndex) add(CustomAccessibilityAction(nextLabel) { onPointSelected(selected + 1); true })
        }
    }
    Canvas(modifier.fillMaxWidth().height(MasroofReference.chartHeight).then(semantics)
        .pointerInput(values.size, onPointSelected, mirror, left, right) {
            val select = onPointSelected ?: return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var scrubbing: Boolean? = null
                fun selectAt(x: Float) {
                    val width = (size.width - left - right).coerceAtLeast(1f)
                    val localX = (x - left).coerceIn(0f, width)
                    val logical = MasroofLineChartLayout.touchXForNearestPoint(localX, width, mirror)
                    MasroofLineChart.nearestPointIndex(logical, width, values.size)?.let(select)
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val movement = change.position - down.position
                    if (!change.pressed) {
                        if (scrubbing != true && movement.getDistance() < viewConfiguration.touchSlop) {
                            selectAt(down.position.x); change.consume()
                        }
                        break
                    }
                    if (scrubbing == null && movement.getDistance() >= viewConfiguration.touchSlop) {
                        scrubbing = kotlin.math.abs(movement.x) > kotlin.math.abs(movement.y)
                        if (scrubbing == false) break
                    }
                    if (scrubbing == true) { selectAt(change.position.x); change.consume() }
                }
            }
        }) {
        val width = (size.width - left - right).coerceAtLeast(1f)
        val plotHeight = (size.height - top - bottom).coerceAtLeast(1f)
        fun point(index: Int) = Offset(left + MasroofLineChartLayout.xForPoint(index, width, values.size, mirror),
            MasroofLineChartLayout.yFor(values[index], range, top, plotHeight))
        fun label(text: String, x: Float, y: Float, rightAligned: Boolean = false) {
            val result = textMeasurer.measure(displayText(text), style = labelStyle)
            drawText(result, topLeft = Offset(if (rightAligned) x - result.size.width else x, y - result.firstBaseline))
        }
        label(currencyLabel, unitLeft, unitBaseline)
        val fractions = if (values.all { it.signum() == 0 }) listOf(BigDecimal.ZERO)
            else listOf(BigDecimal.ONE, BigDecimal("0.5"), BigDecimal.ZERO)
        fractions.forEach { fraction ->
            val value = range.minimum + range.span.multiply(fraction)
            val y = MasroofLineChartLayout.yFor(value, range, top, plotHeight)
            drawLine(grid, Offset(left, y), Offset(left + width, y), strokeWidth = gridStroke)
            val number = java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols(java.util.Locale.US)).format(value)
            label(number, left - tickOffset, y + tickBaseline, true)
        }
        if (showAverage) {
            val averageY = MasroofLineChartLayout.yFor(referenceValue, range, top, plotHeight)
            drawLine(muted, Offset(left, averageY), Offset(left + width, averageY),
                MasroofReference.averageLineStroke.toPx(), pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(MasroofReference.averageDash.toPx(), MasroofReference.averageDash.toPx())))
        }
        val path = Path()
        values.indices.forEach { i -> val p = point(i); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        drawPath(path, line, style = Stroke(stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        values.indices.forEach { drawCircle(line, radius, point(it)) }
        selected?.let { index ->
            val p = point(index)
            drawLine(muted.copy(alpha = com.baraa.masroof.ui.theme.MasroofOpacity.muted),
                Offset(p.x, top), Offset(p.x, top + plotHeight), gridStroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashStroke, dash)))
            drawCircle(surface, selectedRadius, p)
            drawCircle(line, selectedRadius, p, style = Stroke(stroke))
        }
        val tickIndices = if (values.size <= 4) values.indices.toList() else
            listOf(0, (values.lastIndex * 4.0 / 13.0).roundToInt(), (values.lastIndex * 9.0 / 13.0).roundToInt(), values.lastIndex).distinct()
        tickIndices.forEach { i ->
            val text = axisLabel(i)
            val layout = textMeasurer.measure(displayText(text), style = labelStyle)
            val x = when {
                i == 0 -> if (mirror) point(i).x - layout.size.width else point(i).x
                i == values.lastIndex -> if (mirror) point(i).x else point(i).x - layout.size.width
                else -> point(i).x - layout.size.width / 2f
            }
            drawText(layout, topLeft = Offset(x, top + plotHeight + dateBaseline - layout.firstBaseline))
        }
        label(dayAxis, left + width, size.height - dayBottom, true)
    }
}
