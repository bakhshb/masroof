package com.baraa.masroof.application.locale

interface AppLocaleRepository {
    fun getLanguageTag(): String

    fun setLanguageTag(languageTag: String)
}
