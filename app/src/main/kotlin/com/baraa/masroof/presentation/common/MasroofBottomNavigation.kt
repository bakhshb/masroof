package com.baraa.masroof.presentation.common

import androidx.compose.runtime.Composable

typealias MasroofNavigationItem = com.baraa.masroof.ui.components.MasroofNavigationItem

@Composable
fun MasroofBottomNavigation(items: List<MasroofNavigationItem>, onSelect: (Int) -> Unit) =
    com.baraa.masroof.ui.components.MasroofBottomNavigation(items, onSelect,
        displayText = com.baraa.masroof.application.locale.AppDigits::display)
