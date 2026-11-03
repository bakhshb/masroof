package com.baraa.masroof.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isSpecified

const val MASROOF_FIT_MIN_SCALE = 0.5f

/** Scales every sized span too, so a money unit shrinks with its number. */
fun AnnotatedString.scaledBy(scale: Float): AnnotatedString = if (scale == 1f) this else AnnotatedString(text,
    spanStyles.map { range -> range.copy(item = range.item.copy(fontSize =
        if (range.item.fontSize.isSpecified) range.item.fontSize * scale else range.item.fontSize)) },
    paragraphStyles)

fun TextStyle.scaledBy(scale: Float): TextStyle =
    if (scale == 1f || !fontSize.isSpecified) this else copy(fontSize = fontSize * scale)

/**
 * Largest scale (down to [MASROOF_FIT_MIN_SCALE]) at which [text] fits [maxWidth] px on one line,
 * or null when even the minimum does not fit. Measured within one composition, so it follows width changes both ways.
 */
fun TextMeasurer.fitScale(text: AnnotatedString, style: TextStyle, maxWidth: Int): Float? {
    if (maxWidth == Constraints.Infinity || !style.fontSize.isSpecified) return 1f
    var scale = 1f
    repeat(4) {
        val width = measure(text.scaledBy(scale), style.scaledBy(scale), softWrap = false, maxLines = 1).size.width
        if (width <= maxWidth) return scale
        if (scale == MASROOF_FIT_MIN_SCALE) return null
        // Width is close to linear in font size; the small margin absorbs hinting.
        scale = (scale * maxWidth / width * 0.98f).coerceAtLeast(MASROOF_FIT_MIN_SCALE)
    }
    return null
}

/**
 * One-line text that shrinks its font until it fits, so money is never cut to "…" or split mid-number.
 * Below the minimum scale it wraps at that scale rather than truncating.
 * Explicit money spans must scale with the base font; tab labels also reuse one common fit scale.
 * Keep these shared measurements until native autosizing preserves both contracts.
 */
@Composable
fun MasroofFitText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier,
    color: Color = Color.Unspecified, textAlign: TextAlign? = null) {
    val measurer = rememberTextMeasurer()
    // Propagated min width keeps textAlign working when the caller fills the row.
    BoxWithConstraints(modifier.then(FitTextIntrinsicsElement(text, style, measurer)), propagateMinConstraints = true) {
        val fit = remember(text, style, constraints.maxWidth) { measurer.fitScale(text, style, constraints.maxWidth) }
        val scale = fit ?: MASROOF_FIT_MIN_SCALE
        Text(text.scaledBy(scale), color = color, style = style.scaledBy(scale), textAlign = textAlign,
            maxLines = if (fit != null) 1 else Int.MAX_VALUE, softWrap = fit == null)
    }
}

@Composable
fun MasroofFitText(text: String, style: TextStyle, modifier: Modifier = Modifier,
    color: Color = Color.Unspecified, textAlign: TextAlign? = null) =
    MasroofFitText(AnnotatedString(text), style, modifier, color, textAlign)

/** BoxWithConstraints cannot answer intrinsics; measure the same fitted text directly so IntrinsicSize rows work. */
private data class FitTextIntrinsicsElement(val text: AnnotatedString, val style: TextStyle,
    val measurer: TextMeasurer) : ModifierNodeElement<FitTextIntrinsicsNode>() {
    override fun create() = FitTextIntrinsicsNode(text, style, measurer)
    override fun update(node: FitTextIntrinsicsNode) {
        node.text = text; node.style = style; node.measurer = measurer
        node.invalidateMeasurement()
    }
}

private class FitTextIntrinsicsNode(var text: AnnotatedString, var style: TextStyle,
    var measurer: TextMeasurer) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    private fun height(width: Int): Int {
        val fit = measurer.fitScale(text, style, width)
        val scale = fit ?: MASROOF_FIT_MIN_SCALE
        return measurer.measure(text.scaledBy(scale), style.scaledBy(scale), softWrap = fit == null,
            maxLines = if (fit != null) 1 else Int.MAX_VALUE, constraints = Constraints(maxWidth = width)).size.height
    }

    private fun width(scale: Float): Int =
        measurer.measure(text.scaledBy(scale), style.scaledBy(scale), softWrap = false, maxLines = 1).size.width

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = height(width)
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = height(width)
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = width(MASROOF_FIT_MIN_SCALE)
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = width(1f)
}
