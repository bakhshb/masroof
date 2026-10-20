package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import com.baraa.masroof.ui.components.MasroofOutlineIcons
import com.baraa.masroof.ui.theme.*

/** Production MasroofSelect. The caller owns stable choice identities and draft state. */
@Composable
internal fun ApprovedSelect(value: String,choices: List<String>,onSelected: (Int)->Unit,
    modifier: Modifier=Modifier,label: String?=null,enabled: Boolean=true,tag: String) {
    var open by remember {mutableStateOf(false)}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(MasroofReference.shortcutGap)) {
        label?.let {ApprovedText(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Box {
            Surface(onClick={open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag(tag),shape=MasroofShapes.small,
                border=BorderStroke(MasroofSpacing.borderWidth,MasroofThemeExtras.extendedColors.cardBorder)) {
                Row(Modifier.padding(horizontal=MasroofSpacing.periodDisplayHorizontalPadding,vertical=MasroofReference.rowGap),
                    verticalAlignment=Alignment.CenterVertically) {
                    ApprovedText(value,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                    Icon(MasroofOutlineIcons.ChevronRight,null,Modifier.size(MasroofReference.icon).graphicsLayer {rotationZ=90f})
                }
            }
            DropdownMenu(open,{open=false}) {
                choices.forEachIndexed {index,text -> DropdownMenuItem(text={ApprovedText(text)},
                    onClick={open=false;onSelected(index)},modifier=Modifier.testTag("$tag-$index"))}
            }
        }
    }
}
