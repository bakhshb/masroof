package com.baraa.masroof.presentation.common

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.baraa.masroof.application.locale.AppDigits

/** Shared text boundary also covers digits inside imported labels and SMS evidence. */
@Composable
fun MasroofText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    Text(text = AppDigits.display(text), modifier = modifier, color = color, fontSize = fontSize,
        fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
        minLines = minLines, onTextLayout = onTextLayout, style = style)
}

/** Span ranges stay valid because every digit substitution has the same length. */
@Composable
fun MasroofText(text: AnnotatedString, modifier: Modifier = Modifier,
    color: Color = Color.Unspecified, style: TextStyle = LocalTextStyle.current) {
    val displayed = AnnotatedString(AppDigits.display(text.text), text.spanStyles, text.paragraphStyles)
    Text(displayed, modifier, color = color, style = style)
}

/** A raw SMS body: each paragraph takes its direction from its own text, so Arabic lines read right to left. */
@Composable
fun SmsBodyText(body: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, style: TextStyle = LocalTextStyle.current) =
    MasroofText(body, modifier, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Start,
        overflow = TextOverflow.Ellipsis, maxLines = maxLines,
        style = style.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content))
