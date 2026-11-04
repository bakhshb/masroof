package com.baraa.masroof.presentation.dashboard

import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.R
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.presentation.locale.AppLocaleContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class DashboardRedesignResourcesTest {
    @Test
    fun customizeStrings_englishAreTranslated() {
        val context = AppLocaleContext.wrap(
            ApplicationProvider.getApplicationContext(),
            AppLocale.TAG_EN,
        )
        assertEquals("Customize", context.getString(R.string.dashboard_customize))
        assertEquals("Customize home screen", context.getString(R.string.dashboard_customize_sheet_title))
        assertEquals("Save layout", context.getString(R.string.dashboard_customize_save))
        assertEquals("Hero card", context.getString(R.string.dashboard_customize_section_hero))
        assertEquals("Loans summary", context.getString(R.string.dashboard_customize_section_loans))
        assertEquals("Shortcuts", context.getString(R.string.dashboard_customize_section_accounts))
        assertEquals("Outflow details", context.getString(R.string.dashboard_expense_details_title))
        assertEquals("Inflow details", context.getString(R.string.dashboard_income_details_title))
        assertEquals("View all ›", context.getString(R.string.dashboard_view_all))
        assertEquals("Accounts", context.getString(R.string.dashboard_accounts_summary_screen_title))
        assertEquals("Cards", context.getString(R.string.dashboard_cards_summary_screen_title))
        assertEquals("Loans", context.getString(R.string.dashboard_loans_summary_screen_title))
        assertEquals(
            "Period transactions (4)",
            context.getString(R.string.dashboard_summary_transactions_title, 4),
        )
    }

    @Config(qualifiers = "ar")
    @Test
    fun customizeStrings_arabicUseDefaultResourceBucket() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("تخصيص", context.getString(R.string.dashboard_customize))
        assertEquals("تخصيص الشاشة الرئيسية", context.getString(R.string.dashboard_customize_sheet_title))
        assertEquals("ملخص القروض", context.getString(R.string.dashboard_customize_section_loans))
        assertEquals("تفاصيل الصادر", context.getString(R.string.dashboard_expense_details_title))
    }
}
