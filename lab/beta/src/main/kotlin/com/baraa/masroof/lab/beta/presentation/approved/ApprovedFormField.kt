package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import com.baraa.masroof.ui.theme.*

@Composable
fun ApprovedFormField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false, textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1, shape: Shape = MasroofShapes.small) {
    Column(Modifier, verticalArrangement = Arrangement.spacedBy(MasroofReference.shortcutGap)) {
        ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
            label?.invoke()
        }
        Surface(Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(MasroofSpacing.borderWidth, if (isError) MaterialTheme.colorScheme.error else MasroofThemeExtras.extendedColors.cardBorder)) {
            Row(Modifier.padding(horizontal = MasroofSpacing.periodDisplayHorizontalPadding + MasroofSpacing.borderWidth,
                vertical = MasroofReference.rowGap + MasroofSpacing.borderWidth),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
                leadingIcon?.invoke()
                BasicTextField(value = value, onValueChange = onValueChange,
                    modifier = modifier.weight(1f), enabled = enabled, readOnly = readOnly,
                    textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                    visualTransformation = if (visualTransformation is androidx.compose.ui.text.input.PasswordVisualTransformation) visualTransformation
                        else VisualTransformation { original ->
                            visualTransformation.filter(ApprovedDigitsVisualTransformation.filter(original).text)
                        }, singleLine = singleLine, maxLines = maxLines, minLines = minLines,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { field ->
                        Box {
                            if (value.isEmpty()) placeholder?.invoke()
                            field()
                        }
                    })
                trailingIcon?.invoke()
            }
        }
        supportingText?.invoke()
    }
}
