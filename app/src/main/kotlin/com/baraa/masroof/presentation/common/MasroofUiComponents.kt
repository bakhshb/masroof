package com.baraa.masroof.presentation.common

import com.baraa.masroof.ui.components.MasroofOutlineIcons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.baraa.masroof.presentation.common.MasroofButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofOutlinedButton as OutlinedButton
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.presentation.common.MasroofTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.baraa.masroof.ui.theme.MasroofIconSizes
import com.baraa.masroof.ui.theme.MasroofSpacing

@Composable
fun BackNavigationIcon(
    onClick: () -> Unit,
    contentDescription: String?,
) {
    val isLtr = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Ltr
    androidx.compose.material3.Surface(onClick = onClick,
        modifier = Modifier.size(com.baraa.masroof.ui.theme.MasroofReference.iconButton),
        shape = com.baraa.masroof.ui.theme.MasroofShapes.small,
        border = BorderStroke(MasroofSpacing.borderWidth, com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.cardBorder)) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector = MasroofOutlineIcons.ArrowRight,
            contentDescription = contentDescription,
            modifier = Modifier.size(com.baraa.masroof.ui.theme.MasroofReference.icon)
                .graphicsLayer { scaleX = if (isLtr) -1f else 1f },
        )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onViewAll: (() -> Unit)? = null,
    viewAllLabel: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MasroofIconSizes.sectionHeader),
            )
            MasroofSectionTitle(title = title, modifier = Modifier.fillMaxWidth())
        }
        if (onViewAll != null && viewAllLabel != null) {
            TextButton(onClick = onViewAll) {
                Text(
                    viewAllLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun IconLabelRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    guidance: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.rowHorizontalPadding),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(MasroofIconSizes.appBarAction),
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        trailing?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        guidance?.let { MasroofGuidanceButton(label, it) }
    }
}

@Composable
fun IconTextButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    brandedLogo: Boolean = false,
) {
    require(icon != null || brandedLogo) {
        "IconTextButton requires either icon or brandedLogo"
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (brandedLogo) {
            MasroofLogoMark(size = MasroofIconSizes.moneyRowLeading)
        } else {
            Icon(
                imageVector = icon!!,
                contentDescription = null,
                modifier = Modifier.size(MasroofIconSizes.moneyRowLeading),
            )
        }
        Spacer(Modifier.width(MasroofSpacing.sectionHeaderGap))
        Text(text)
    }
}

@Composable
fun IconTextButtonOutlined(
    onClick: () -> Unit,
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(MasroofSpacing.borderWidth, MaterialTheme.colorScheme.outline),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(MasroofIconSizes.moneyRowLeading),
        )
        Spacer(Modifier.width(MasroofSpacing.carouselGap))
        Text(text)
    }
}
