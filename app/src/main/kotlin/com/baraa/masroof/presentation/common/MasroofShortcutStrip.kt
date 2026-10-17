package com.baraa.masroof.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

typealias MasroofShortcutItem = com.baraa.masroof.ui.components.MasroofShortcutItem

@Composable
fun MasroofShortcutStrip(items: List<MasroofShortcutItem>, modifier: Modifier = Modifier) =
    com.baraa.masroof.ui.components.MasroofShortcutStrip(items, modifier,
        displayText = com.baraa.masroof.application.locale.AppDigits::display)
