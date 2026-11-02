package com.baraa.masroof.testsupport

import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocaleRepository

internal class FakeAppLocaleRepository(private var tag: String = AppLocale.DEFAULT_TAG) : AppLocaleRepository {
    override fun getLanguageTag(): String = tag

    override fun setLanguageTag(languageTag: String) {
        tag = languageTag
    }
}
