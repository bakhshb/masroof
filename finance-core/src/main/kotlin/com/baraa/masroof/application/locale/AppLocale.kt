package com.baraa.masroof.application.locale

import java.util.Locale

object AppLocale {
    const val TAG_AR: String = "ar"
    const val TAG_EN: String = "en"
    const val DEFAULT_TAG: String = TAG_AR

    fun languageCode(languageTag: String): String =
        languageTag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)

    fun isEnglish(languageTag: String): Boolean = languageCode(languageTag) == TAG_EN

    fun displayLocale(languageTag: String): Locale =
        if (isEnglish(languageTag)) {
            Locale.ENGLISH
        } else {
            Locale.Builder().setLanguage(TAG_AR)
                .setUnicodeLocaleKeyword("nu", AppDigits.numberingSystem).build()
        }
}
