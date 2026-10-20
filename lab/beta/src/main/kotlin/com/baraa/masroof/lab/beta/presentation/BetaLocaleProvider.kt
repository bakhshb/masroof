package com.baraa.masroof.lab.beta.presentation

import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/** A composition-scoped language. Never mutates the host application's resources or locale. */
@Composable
internal fun BetaLocaleProvider(language: String, content: @Composable () -> Unit) {
    val host=LocalContext.current
    val configuration=LocalConfiguration.current
    val localized=remember(host,configuration,language) {
        val copy=Configuration(configuration)
        val requested=if(language=="SYSTEM") configuration.locales[0] else Locale.forLanguageTag(language)
        copy.setLocale(Locale.Builder().setLocale(requested).setUnicodeLocaleKeyword("nu","latn").build())
        android.view.ContextThemeWrapper(host,0).apply {applyOverrideConfiguration(copy)}
    }
    val config=localized.resources.configuration
    CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides config,
        LocalLayoutDirection provides if(config.layoutDirection==android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        content=content)
}
