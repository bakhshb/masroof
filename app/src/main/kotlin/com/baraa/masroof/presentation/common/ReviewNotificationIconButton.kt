package com.baraa.masroof.presentation.common

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.ui.theme.MasroofReference
import com.baraa.masroof.ui.theme.MasroofShapes
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofThemeExtras

@Composable
fun ReviewNotificationIconButton(reviewCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MasroofThemeExtras.extendedColors
    Box(modifier.size(MasroofReference.iconButton)) {
        Surface(onClick = onClick, modifier = Modifier.matchParentSize(), shape = MasroofShapes.small,
            border = BorderStroke(MasroofSpacing.borderWidth, colors.cardBorder)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(MasroofIcons.notifications,
                    if (reviewCount > 0) pluralStringResource(R.plurals.notification_center_badge_count, reviewCount, reviewCount)
                    else stringResource(R.string.notification_center_title),
                    Modifier.size(MasroofReference.icon))
            }
        }
        if (reviewCount > 0) Surface(modifier = Modifier.align(Alignment.TopEnd)
            .offset(x = MasroofReference.notificationOffsetX, y = MasroofReference.notificationOffsetY)
            .defaultMinSize(MasroofReference.notificationBadge, MasroofReference.notificationBadge), shape = MasroofShapes.extraLarge,
            color = colors.liability, contentColor = if (com.baraa.masroof.ui.theme.masroofIsDark())
                colors.liabilitySoft else MaterialTheme.colorScheme.surface) {
            Box(contentAlignment = Alignment.Center) {
                MasroofText(if (reviewCount > 99) "99+" else reviewCount.toString(), style = MasroofReference.notificationCount,
                    modifier = Modifier.padding(horizontal = MasroofSpacing.hairlineGap))
            }
        }
    }
}
