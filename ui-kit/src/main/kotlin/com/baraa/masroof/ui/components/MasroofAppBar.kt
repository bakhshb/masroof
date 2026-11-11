package com.baraa.masroof.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.baraa.masroof.ui.R
import com.baraa.masroof.ui.theme.*

@Composable
fun MasroofLogo(modifier: Modifier = Modifier, size: Dp = MasroofIconSizes.logo, contentDescription: String? = null) {
    Image(painterResource(R.drawable.masroof_logo), contentDescription,
        modifier.size(size).clip(MasroofLogoShape))
}

/** Production header; optional chrome is owned by each isolated host. */
@Composable
fun MasroofAppBar(title: String, modifier: Modifier = Modifier, showLogo: Boolean = true,
    navigation: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val reference = MasroofReference
    Surface(modifier.fillMaxWidth(), color = if (showLogo) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(start = if (showLogo) reference.headerHorizontal else MasroofSpacing.screenHorizontal,
            end = if (showLogo) reference.headerHorizontal else MasroofSpacing.screenHorizontal,
            top = if (showLogo) reference.headerTop else MasroofSpacing.screenVertical, bottom = reference.headerBottom),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            navigation?.invoke()
            if (showLogo) MasroofLogo()
            Text(title, Modifier.weight(1f), style = if (showLogo) reference.brand else MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
