package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.baraa.masroof.lab.beta.application.BetaDisplayDigits as AppDigits

/** One character stays one character, so the cursor and underlying input are preserved. */
internal object ApprovedDigitsVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        AnnotatedString(AppDigits.display(text.text), text.spanStyles, text.paragraphStyles),
        OffsetMapping.Identity,
    )
}
