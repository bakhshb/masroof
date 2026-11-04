package com.baraa.masroof.instrumentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.presentation.locale.AppLocaleContext
import com.baraa.masroof.presentation.review.ReviewDetailScreen
import com.baraa.masroof.presentation.review.ReviewDetailUi
import com.baraa.masroof.presentation.review.ReviewTypeSheet
import com.baraa.masroof.testsupport.ScreenshotArtifactWriter
import com.baraa.masroof.ui.theme.MasroofTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ReviewTeachingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun choosingATypeIsOnlyADraftUntilApply_andCancelDiscardsIt() {
        val open = mutableStateOf(true)
        var saved: FinancialTransactionType? = null
        compose.setContent {
            MasroofTheme { Surface {
                if (open.value) ReviewTypeSheet(false, { open.value = false }, { saved = it })
            } }
        }
        compose.onNodeWithTag("sheet-apply").assertIsNotEnabled()
        compose.onNodeWithTag("review_resolve_EXPENSE").performScrollTo().performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("sheet-cancel").performClick()
        compose.runOnIdle { assertNull(saved); open.value = true }
        compose.onNodeWithTag("sheet-apply").assertIsNotEnabled()
        compose.onNodeWithTag("review_resolve_INCOME").performScrollTo().performClick()
        compose.onNodeWithTag("sheet-apply").performClick()
        compose.runOnIdle { assertEquals(FinancialTransactionType.INCOME, saved) }
    }

    @Test fun unfamiliarMessageShowsTeachingAndKeepsDiagnosticsBehindWhy_inBothThemesAndLanguages() {
        val dark = mutableStateOf(false)
        val language = mutableStateOf("ar")
        val body = "Payment\nAmount:85SAR\nMerchant:SHOP"
        val detail = ReviewDetailUi("review-ui", "sms-ui", ReviewKind.NEEDS_REVIEW,
            R.string.review_kind_needs_review, "TEST BANK", body, "26 Oct 2026",
            MessageFamily.UNKNOWN, null, null, null, listOf("unsupported_bank_message_format"), emptyList(),
            false, false, true, true)
        compose.setContent {
            val context = AppLocaleContext.wrap(LocalContext.current, language.value)
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
                MasroofTheme(darkTheme = dark.value) { Surface {
                    ReviewDetailScreen(detail, false, null, null, null, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
                } }
            }
        }
        listOf("ar" to false, "ar" to true, "en" to false).forEach { (locale, isDark) ->
            compose.runOnIdle { language.value = locale; dark.value = isDark }
            compose.onNodeWithText(body).assertExists()
            compose.onNodeWithTag("review-teach-pattern").assertExists()
            compose.onNodeWithTag("review_resolve_EXPENSE").assertDoesNotExist()
            ScreenshotArtifactWriter.capture(compose, "teaching-review", "$locale-${if (isDark) "dark" else "light"}")
        }
        compose.onNodeWithTag("review-correct-type").performScrollTo().performClick()
        compose.onNodeWithTag("sheet-apply").assertIsNotEnabled()
        compose.onNodeWithTag("sheet-cancel").performClick()
        compose.onNodeWithTag("review-why").performScrollTo().performClick()
        compose.onNodeWithText("Unsupported bank message format").assertExists()
    }

    @Test fun recordedTransactionStartsWithItsCurrentTypeSelected_andRequiresAChange() {
        val selected = mutableStateOf<FinancialTransactionType?>(null)
        var saves = 0
        compose.setContent {
            val context = AppLocaleContext.wrap(LocalContext.current, "en")
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
                MasroofTheme { Surface {
                    com.baraa.masroof.presentation.dashboard.TransactionReclassifyBottomSheet(
                        FinancialTransactionType.EXPENSE, selected.value, false, {}, { selected.value = it }, { saves++ })
                } }
            }
        }
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        compose.onNodeWithTag("sheet-apply").assertIsNotEnabled()
        compose.onNodeWithText("Income", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("sheet-apply").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithTag("sheet-apply").performClick()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(FinancialTransactionType.INCOME, selected.value) }
    }
}
