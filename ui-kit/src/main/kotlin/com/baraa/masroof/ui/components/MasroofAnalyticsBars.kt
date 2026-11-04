package com.baraa.masroof.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import com.baraa.masroof.ui.theme.*
import java.math.BigDecimal
import java.math.RoundingMode

object MasroofAnalyticsChartMath {
    private const val ALL_LABELS_MAX = 7

    fun signedFraction(value: BigDecimal, maximum: BigDecimal): Float =
        if (maximum.signum() <= 0) 0f else value.divide(maximum, 4, RoundingMode.HALF_UP).toFloat().coerceIn(-1f, 1f)

    // A short chart (a week, or salary periods) labels every bar; a long one keeps evenly spaced labels.
    fun axisIndices(count: Int): Set<Int> = if (count <= 0) emptySet() else if (count <= ALL_LABELS_MAX) (0 until count).toSet() else
        (0..MasroofAnalyticsTokens.gridIntervals).map { index ->
            kotlin.math.round(index.toDouble() * (count - 1) / MasroofAnalyticsTokens.gridIntervals).toInt()
        }.toSet()

    fun barAt(x: Float, width: Float, count: Int): Int? =
        if (width <= 0f || count <= 0) null else kotlin.math.floor(x / width * count).toInt().coerceIn(0, count - 1)

    /** Maps a day index to its left-to-right slot and back; RTL puts the first day on the right. */
    fun barSlot(index: Int, count: Int, mirror: Boolean): Int = if (mirror) count - 1 - index else index
}

/** Inline row for bars inside a shared card; labels wrap at large font sizes. */
@Composable
fun MasroofValueBarRow(title: String, value: String, progress: Float, modifier: Modifier = Modifier,
    subtitle: String? = null, style: MasroofHorizontalBarStyle = MasroofHorizontalBarStyle.Outflow,
    previous: Boolean = false, onClick: (() -> Unit)? = null) {
    val extended = MasroofThemeExtras.extendedColors
    val color = when (style) {
        MasroofHorizontalBarStyle.Outflow -> extended.outflow
        MasroofHorizontalBarStyle.Inflow -> extended.inflow
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleSmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.ContentOrLtr),
                color = if (style == MasroofHorizontalBarStyle.Inflow) color else MaterialTheme.colorScheme.onSurface)
        }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Box(Modifier.fillMaxWidth().height(MasroofAnalyticsTokens.barHeight).background(extended.hintBackground, MasroofComponentShapes.tokenSwatch)) {
            val fraction = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight()
                .background(if (previous) color.copy(alpha = MasroofAnalyticsTokens.previousOpacity) else color, MasroofComponentShapes.tokenSwatch))
        }
    }
}

/** Negative = less spending, positive = more; the caller always supplies a textual direction. */
@Composable
fun MasroofDivergingBarRow(title: String, changeLabel: String, fraction: Float, modifier: Modifier = Modifier) {
    val extended = MasroofThemeExtras.extendedColors
    val color = when {
        fraction < 0 -> extended.inflow
        fraction > 0 -> extended.outflow
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(changeLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = color)
        }
        Canvas(Modifier.fillMaxWidth().height(MasroofAnalyticsTokens.splitHeight)) {
            val bounded = fraction.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
            val center = size.width / 2
            drawLine(axis, Offset(center, 0f), Offset(center, size.height), MasroofAnalyticsTokens.zeroAxisWidth.toPx())
            val width = kotlin.math.abs(bounded) * center
            drawRect(color, Offset(if (bounded < 0) center - width else center, (size.height - MasroofAnalyticsTokens.barHeight.toPx()) / 2),
                Size(width, MasroofAnalyticsTokens.barHeight.toPx()))
        }
    }
}

@Composable
fun MasroofForecastSplit(actual: BigDecimal, remaining: BigDecimal, description: String, modifier: Modifier = Modifier) {
    val extended = MasroofThemeExtras.extendedColors
    val expectedColor = MaterialTheme.colorScheme.primary
    val total = actual + remaining
    // Signed net actuals are valid. A share chart is meaningful only for non-negative parts.
    if (actual.signum() < 0 || remaining.signum() < 0 || total.signum() <= 0) return
    val fraction = MasroofBarChart.progress(actual, total)
    Canvas(modifier.fillMaxWidth().height(MasroofAnalyticsTokens.splitHeight).semantics { contentDescription = description }) {
        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
        val actualWidth = size.width * fraction
        drawRect(extended.outflow, Offset(if (rtl) size.width - actualWidth else 0f, 0f), Size(actualWidth, size.height))
        val from = if (rtl) 0f else actualWidth
        val through = if (rtl) size.width - actualWidth else size.width
        val stroke = MasroofSpacing.borderWidth.toPx()
        drawRect(expectedColor, Offset(from, 0f), Size(through - from, size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        var x = from
        while (x < through) {
            drawLine(expectedColor, Offset(x, size.height), Offset((x + size.height).coerceAtMost(through), 0f), stroke)
            x += MasroofSpacing.smallGap.toPx()
        }
    }
}

/** One share of a whole. [weight] is precomputed by the caller's application layer; non-positive parts are not drawn. */
data class MasroofStackedPart(val weight: Float, val color: androidx.compose.ui.graphics.Color)

/** Parts of one whole in one row; follows layout direction. [selected] dims the other parts. */
@Composable
fun MasroofStackedBar(parts: List<MasroofStackedPart>, description: String, modifier: Modifier = Modifier, selected: Int? = null) {
    if (parts.none { it.weight > 0f && it.weight.isFinite() }) return
    Row(modifier.fillMaxWidth().height(MasroofAnalyticsTokens.stackedHeight).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(MasroofAnalyticsTokens.stackedGap)) {
        parts.forEachIndexed { index, part ->
            if (part.weight > 0f && part.weight.isFinite()) Box(Modifier.weight(part.weight).fillMaxHeight().background(
                if (selected == null || selected == index) part.color else part.color.copy(alpha = MasroofAnalyticsTokens.previousOpacity),
                MasroofComponentShapes.tokenSwatch))
        }
    }
}

/** Progress toward a fixed target, with the value inside and a caption below. */
@Composable
fun MasroofProgressRing(progress: Float, value: String, caption: String, modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bounded = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
        Box(Modifier.size(MasroofAnalyticsTokens.ringSize), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val stroke = MasroofAnalyticsTokens.ringStroke.toPx()
                val inset = Offset(stroke / 2, stroke / 2)
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(track, -90f, 360f, false, inset, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                if (bounded > 0f) drawArc(color, -90f, 360f * bounded, false, inset, arc,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            // Scales down to stay inside the ring: "100 / 120" is wider than the hole at titleMedium.
            MasroofFitText(value, MaterialTheme.typography.titleMedium.copy(textDirection = androidx.compose.ui.text.style.TextDirection.ContentOrLtr),
                Modifier.padding(horizontal = MasroofAnalyticsTokens.ringStroke + MasroofSpacing.inlineGap),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A due date placed between today (0) and the period end (1); [count] items share that day. */
data class MasroofTimelineMark(val fraction: Float, val count: Int, val soon: Boolean)

/** Due dates on one line from today to period end; mirrors in RTL. Counts are drawn, never color alone. */
@Composable
fun MasroofDueTimeline(marks: List<MasroofTimelineMark>, startLabel: String, endLabel: String, description: String,
    modifier: Modifier = Modifier) {
    val extended = MasroofThemeExtras.extendedColors
    val line = MaterialTheme.colorScheme.outlineVariant
    val today = MaterialTheme.colorScheme.primary
    val onMark = MaterialTheme.colorScheme.surface
    val style = MaterialTheme.typography.labelSmall
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
        Canvas(Modifier.fillMaxWidth().height(MasroofAnalyticsTokens.markerSize)) {
            val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
            val radius = MasroofAnalyticsTokens.markerSize.toPx() / 2
            val stroke = MasroofSpacing.borderWidth.toPx() * 2
            fun x(fraction: Float) = radius + (size.width - 2 * radius) * (if (rtl) 1f - fraction else fraction).coerceIn(0f, 1f)
            drawLine(line, Offset(0f, radius), Offset(size.width, radius), stroke)
            drawLine(today, Offset(x(0f), radius / 2), Offset(x(0f), radius * 1.5f), stroke * 1.5f)
            marks.forEach { mark ->
                val center = Offset(x(mark.fraction), radius)
                drawCircle(if (mark.soon) extended.liability else extended.card, radius, center)
                val label = measurer.measure(mark.count.toString(), style.copy(color = onMark))
                drawText(label, topLeft = Offset(center.x - label.size.width / 2, center.y - label.size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(startLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(endLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** null cells mean unknown coverage; callers must never infer covered days from missing receipts. */
@Composable
fun MasroofCoverageGrid(days: List<Boolean?>, description: String, modifier: Modifier = Modifier) {
    val covered = MaterialTheme.colorScheme.primary
    val gap = MasroofThemeExtras.extendedColors.outflow
    val unknown = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
        days.chunked(MasroofAnalyticsTokens.coverageColumns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
                repeat(MasroofAnalyticsTokens.coverageColumns) { index ->
                    val item = row.getOrNull(index)
                    Box(Modifier.weight(1f).height(MasroofAnalyticsTokens.coverageCellHeight)
                        .background(if (index >= row.size) androidx.compose.ui.graphics.Color.Transparent else when (item) {
                            true -> covered; false -> gap; null -> unknown
                        }, MasroofComponentShapes.tokenSwatch))
                }
            }
        }
    }
}
