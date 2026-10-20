package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.ui.theme.*

/** Production SettingsNavRow/MasroofNavCard. Kept distinct from financial reference-detail rows. */
@Composable
internal fun ApprovedSettingsNavigationRow(title: String,subtitle: String,icon: ImageVector,enabled: Boolean,
    onClick: ()->Unit,modifier: Modifier=Modifier) {
    val colors=MasroofThemeExtras.extendedColors
    Card(onClick=onClick,enabled=enabled,modifier=modifier.fillMaxWidth().semantics {role=Role.Button},shape=MasroofCardShape,
        colors=CardDefaults.cardColors(containerColor=colors.navCardSurface,contentColor=MaterialTheme.colorScheme.onSurface),
        elevation=CardDefaults.cardElevation(defaultElevation=MasroofSpacing.none),border=BorderStroke(MasroofSpacing.borderWidth,colors.navCardBorder)) {
        Column(Modifier.padding(horizontal=MasroofSpacing.navCardHorizontalPadding,vertical=MasroofSpacing.navCardVerticalPadding)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(MasroofSpacing.screenPaddingLarge))
                Spacer(Modifier.width(MasroofSpacing.sectionGap))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.hairlineGap)) {
                    ApprovedText(title,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyMedium.copy(fontWeight=MasroofFontWeights.semiBold),color=MaterialTheme.colorScheme.onSurface,textAlign=TextAlign.Start)
                    ApprovedText(subtitle,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Start)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(MasroofSpacing.largeGap))
            }
        }
    }
}
