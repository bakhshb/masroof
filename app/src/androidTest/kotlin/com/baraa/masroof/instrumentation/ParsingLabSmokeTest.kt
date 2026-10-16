package com.baraa.masroof.instrumentation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.baraa.masroof.lab.presentation.ParsingLabEntry
import com.baraa.masroof.BuildConfig
import com.baraa.masroof.R
import com.baraa.masroof.presentation.settings.SettingsAboutScreen
import com.baraa.masroof.presentation.settings.SettingsHubScreen
import com.baraa.masroof.presentation.settings.SettingsUiState
import com.baraa.masroof.presentation.settings.SettingsDeveloperScreen
import com.baraa.masroof.presentation.theme.MasroofTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ParsingLabSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun normalAppSettingsOpensDeveloperMenuAndLabOnlyThroughAbout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.baraa.masroof", context.packageName)
        assertTrue(BuildConfig.ENABLE_PARSING_LAB)
        compose.setContent {
            var screen by remember { mutableStateOf("settings") }
            MasroofTheme {
                when (screen) {
                    "settings" -> SettingsHubScreen(state = SettingsUiState(loading = false), reviewRequiredCount = 0,
                        onBack = {}, onOpenAccounts = {}, onOpenCards = {}, onOpenLoans = {}, onOpenCommitments = {},
                        onOpenReview = {}, onOpenApp = {}, onOpenDataBackup = {}, onOpenStatementReconciliation = {},
                        onOpenAbout = { screen = "about" })
                    "about" -> SettingsAboutScreen(
                        appVersion = "test", isNightlyBuild = false,
                        updateChannel = com.baraa.masroof.application.update.UpdateChannel.STABLE,
                        githubTokenConfigured = false, updateState = SettingsUiState().updateState, updateMessage = null,
                        onBack = { screen = "settings" }, onOpenLogs = {},
                        onOpenDeveloperSettings = { screen = "developer" },
                        onSaveGithubToken = {}, onClearGithubToken = {}, onSelectUpdateChannel = {},
                        onCheckForUpdates = {}, onDownloadUpdate = {}, onInstallUpdate = {}, onClearUpdateMessage = {},
                    )
                    "developer" -> SettingsDeveloperScreen(
                        onBack = { screen = "about" },
                        onOpenLab = { screen = "lab" },
                        onOpenBeta = {},
                    )
                    else -> ParsingLabEntry(onBack = { screen = "developer" })
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_developer_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_about_section)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.settings_developer_title)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.settings_parsing_lab_title)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("مصروف · مختبر التحليل").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("مصروف · مختبر التحليل").assertIsDisplayed()
    }

    @Test fun seedsSamples_runsDatedBatch_andShowsExpectedComparison() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("masroof_parsing_lab.db")
        compose.setContent { ParsingLabEntry {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("إضافة الجزيرة وعينات توضيحية").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("إضافة الجزيرة وعينات توضيحية").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("4 رسائل · يستخدم المكتبة المشتركة").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("التجربة").performClick()
        compose.onNodeWithText("استيراد رسائل البنك").assertExists()
        compose.onAllNodes(hasText("من تاريخ", substring = true)).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("تأكيد").assertExists()
        compose.onNodeWithText("إلغاء").performClick()
        compose.onAllNodes(hasText("إلى تاريخ", substring = true)).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("تأكيد").performClick()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.READ_SMS)
        compose.onNodeWithText("استيراد الرسائل").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("استُوردت", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("استيراد رسائل البنك").performScrollTo()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
            File(context.getExternalFilesDir(null), "parsing-lab-import.png"))
        compose.onNodeWithText("النتائج").performClick()
        compose.onNodeWithText("الاختبارات السابقة").performScrollTo().performClick()
        compose.onNodeWithText("تشغيل وحفظ النتائج").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("مطابقة الحقول الموسومة: 4 / 4 عينة موسومة · 0 غير موسومة").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("مطابقة الحقول الموسومة: 4 / 4 عينة موسومة · 0 غير موسومة").performScrollTo().assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
            File(context.getExternalFilesDir(null), "parsing-lab-results.png"))
        compose.onAllNodesWithText("عرض الحقائق والأدلة والمقارنة").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("مراجعة الإجابة الصحيحة").performScrollTo().performClick()
        compose.onNodeWithText("حفظ للاختبارات القادمة").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("حُفظ التصحيح", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("مقارنة الأدلة").performScrollTo().performClick()
        compose.onNodeWithText("تشغيل المقارنة وحفظها").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("4 رسالة · 4 لها إجابة · 0 بلا تقييم").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("4 رسالة · 4 لها إجابة · 0 بلا تقييم").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("مطابقة الحقول المراجعة: الحالي 4 / 4 · الأدلة 4 / 4").performScrollTo().assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
            File(context.getExternalFilesDir(null), "parsing-lab-evidence-comparison.png"))
        compose.onNodeWithText("القواعد").performClick()
        compose.onNodeWithText("مكتبة الفهم المشتركة v1").assertExists()
        compose.onNodeWithText("حفظ كإصدار جديد").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("مكتبة الفهم المشتركة v2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("مكتبة الفهم المشتركة v2").performScrollTo()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
            File(context.getExternalFilesDir(null), "parsing-lab-shared-rules.png"))
        compose.onNodeWithText("البنوك").performClick()
        compose.onNodeWithText("البنوك التجريبية").performScrollTo().assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
            File(context.getExternalFilesDir(null), "parsing-lab-banks.png"))
    }
}
