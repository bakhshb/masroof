package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*

// Ported from the production functions recorded in lab/beta/APPROVED_UI_SOURCES.json.
// Production files remain unchanged. This file contains rendering only; Beta workflows own writes.

@Composable
internal fun ApprovedText(text: String,modifier: Modifier = Modifier,style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,maxLines: Int = Int.MAX_VALUE,overflow: TextOverflow = TextOverflow.Clip) {
    Text(com.baraa.masroof.lab.beta.application.BetaDisplayDigits.display(text),modifier,style=style,color=color,maxLines=maxLines,overflow=overflow)
}
@Composable
internal fun ApprovedBadge(
    text: String,
    accent: MasroofCardAccent,
    modifier: Modifier = Modifier,
) {
    val extended = MasroofThemeExtras.extendedColors
    val (background, foreground) = when (accent) {
        MasroofCardAccent.Account -> extended.accountSoft to extended.account
        MasroofCardAccent.Credit -> extended.cardSoft to extended.card
        MasroofCardAccent.Liability -> extended.liabilitySoft to extended.liability
        MasroofCardAccent.Inflow -> extended.inflowSoft to extended.inflow
        MasroofCardAccent.Outflow -> extended.outflowSoft to extended.outflow
        MasroofCardAccent.None -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = MasroofBadgeShape,
        color = background,
    ) {
        ApprovedText(
            text,
            modifier = Modifier.padding(
                horizontal = MasroofSpacing.badgeHorizontalPadding,
                vertical = MasroofSpacing.badgeVerticalPadding,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ApprovedGuidanceLabel(
    label: String,
    message: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ApprovedText(label, Modifier.weight(1f), style = style, color = contentColor)
        message?.takeIf { it.isNotBlank() }?.let {
            MasroofGuidanceButton(label, it, contentColor = contentColor)
        }
    }
}

@Composable
internal fun ApprovedIconLabelRow(
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
            ApprovedText(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        trailing?.let {
            ApprovedText(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        guidance?.let { MasroofGuidanceButton(label, it) }
    }
}

@Composable
internal fun ApprovedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MasroofShapes.small, colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = MasroofSpacing.compactCardContentPadding,
        vertical = MasroofReference.rowGap), content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.Button(onClick, modifier, enabled, shape, colors, elevation, border,
        contentPadding = contentPadding, content = content)
}

internal data class ApprovedReviewListItem(val title: String,val kindLabel: String,val ignored: Boolean,
    val amountLabel: String?,val dateLabel: String,val reasonLabel: String,val actionLabel: String)

@Composable
internal fun ApprovedReviewListCard(item: ApprovedReviewListItem,enabled: Boolean,modifier: Modifier,actionModifier: Modifier,onClick: () -> Unit) {
    MasroofCard(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            ApprovedText(item.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            ApprovedBadge(item.kindLabel,
                if (item.ignored) MasroofCardAccent.Inflow
                else MasroofCardAccent.Liability)
        }
        Spacer(Modifier.height(MasroofSpacing.sectionGap))
        item.amountLabel?.let { MasroofReferenceMoney(it) }
        ApprovedText(item.dateLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val reason = item.reasonLabel
        ApprovedGuidanceLabel(item.kindLabel, reason,
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(MasroofSpacing.sectionGap))
        ApprovedButton(onClick, actionModifier.fillMaxWidth(), enabled=enabled) {
            ApprovedText(item.actionLabel)
        }
    }
}

@Composable
internal fun ApprovedIconTextButton(
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
            MasroofLogo(size = MasroofIconSizes.moneyRowLeading)
        } else {
            Icon(
                imageVector = icon!!,
                contentDescription = null,
                modifier = Modifier.size(MasroofIconSizes.moneyRowLeading),
            )
        }
        Spacer(Modifier.width(MasroofSpacing.sectionHeaderGap))
        ApprovedText(text)
    }
}
