package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*

// Ported from the production functions recorded in lab/beta/APPROVED_UI_SOURCES.json.
// Production files remain unchanged. This file contains rendering only; Beta workflows own writes.

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

/** Production IconTextButtonOutlined; reused by source transaction actions. */
@Composable
internal fun ApprovedIconTextButtonOutlined(onClick: ()->Unit,icon: ImageVector,text: String,
    modifier: Modifier=Modifier,enabled: Boolean=true) {
    ApprovedOutlinedButton(onClick,modifier,enabled,shape=MaterialTheme.shapes.medium,
        border=BorderStroke(MasroofSpacing.borderWidth,MaterialTheme.colorScheme.outline)) {
        Icon(icon,null,Modifier.size(MasroofIconSizes.moneyRowLeading))
        Spacer(Modifier.width(MasroofSpacing.carouselGap))
        ApprovedText(text)
    }
}

@Composable
internal fun ApprovedOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MasroofShapes.small, colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(MasroofSpacing.borderWidth, MasroofThemeExtras.extendedColors.cardBorder),
    contentPadding: PaddingValues = PaddingValues(horizontal = MasroofSpacing.compactCardContentPadding,
        vertical = MasroofReference.rowGap), content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border,
        contentPadding = contentPadding, content = content)
}

@Composable
internal fun ApprovedTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = MasroofSpacing.none, vertical = MasroofSpacing.inlineGap),
    content: @Composable RowScope.() -> Unit) {
    com.baraa.masroof.ui.components.MasroofTextButton(onClick, modifier, enabled, colors, contentPadding, content)
}

@Composable
internal fun ApprovedDetailRow(label: String, value: String, modifier: Modifier = Modifier, guidance: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface, dense: Boolean = false, divider: Boolean = true) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(top = if (dense) MasroofReference.rowGap else MasroofReference.rowVertical,
                bottom = if (dense) MasroofSpacing.none else MasroofReference.rowVertical),
            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ApprovedGuidanceLabel(label, guidance, Modifier.weight(1f), style = if (dense) MaterialTheme.typography.bodySmall else MasroofReference.pair,
                contentColor = MaterialTheme.colorScheme.onSurface)
            ApprovedText(value, Modifier.weight(1f), style = (if (dense) MaterialTheme.typography.bodySmall else MasroofReference.pair).copy(fontWeight = com.baraa.masroof.ui.theme.MasroofFontWeights.semiBold),
                textAlign = androidx.compose.ui.text.style.TextAlign.End, color = valueColor)
        }
        if (divider && !dense) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun ApprovedHeroCard(
    title: String,
    value: String,
    subtitle: String? = null,
    role: MasroofHeroRole = MasroofHeroRole.Brand,
    modifier: Modifier = Modifier,
    guidance: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    com.baraa.masroof.ui.components.MasroofHeroCard(
        title = com.baraa.masroof.lab.beta.application.BetaDisplayDigits.display(title),
        value = com.baraa.masroof.lab.beta.application.BetaDisplayDigits.display(value),
        subtitle = subtitle?.let(com.baraa.masroof.lab.beta.application.BetaDisplayDigits::display),
        role = role, modifier = modifier,
        icon = if (role == MasroofHeroRole.Card) MasroofOutlineIcons.CreditCard else MasroofOutlineIcons.Wallet,
        titleContent = { titleModifier, foreground ->
            ApprovedGuidanceLabel(title, guidance, titleModifier, style = MasroofReference.pair, contentColor = foreground)
        }, footer = footer)
}


@Composable
internal fun ApprovedBackNavigationIcon(
    onClick: () -> Unit,
    contentDescription: String?,
    enabled: Boolean = true, modifier: Modifier = Modifier,
) {
    val isLtr = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Ltr
    androidx.compose.material3.Surface(onClick = onClick, enabled = enabled,
        modifier = modifier.size(com.baraa.masroof.ui.theme.MasroofReference.iconButton),
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
internal fun ApprovedSectionTitle(title: String,modifier: Modifier=Modifier) {
    ApprovedText(title,modifier=modifier,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface)
}
