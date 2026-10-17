package com.baraa.masroof.instrumentation

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.R
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.ui.dashboard.*
import com.baraa.masroof.ui.theme.*
import java.io.File
import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Pure synthetic renderers: no AppContainer, database, SMS or financial writes. */
class SharedHomeExtractionParityTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val window = TestRule { base, description -> object : Statement() {
        override fun evaluate() {
            Assume.assumeTrue("Emulator display override only", Build.HARDWARE in setOf("ranchu", "goldfish"))
            val original = command("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }
                ?.substringAfter(':')?.trim() ?: "reset"
            try {
                command(if (description.methodName.contains("Fold")) "wm size 1800x2400" else "wm size 1080x2400")
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                base.evaluate()
            } finally { command("wm size $original") }
        }
    } }
    @get:Rule val rule: TestRule = RuleChain.outerRule(window).around(compose)
    @Test fun arabicLightPhone() = verify("ar", false, false)
    @Test fun arabicDarkPhone() = verify("ar", true, false)
    @Test fun englishLightPhone() = verify("en", false, false)
    @Test fun englishDarkPhone() = verify("en", true, false)
    @Test fun arabicLightFold() = verify("ar", false, true)
    @Test fun arabicDarkFold() = verify("ar", true, true)
    @Test fun englishLightFold() = verify("en", false, true)
    @Test fun englishDarkFold() = verify("en", true, true)

    private fun verify(language: String, dark: Boolean, fold: Boolean) {
        var original by mutableStateOf(true)
        var mode by mutableStateOf("populated")
        var tab by mutableIntStateOf(0)
        var shortcut by mutableIntStateOf(-1)
        var point by mutableIntStateOf(1)
        val arabic = language == "ar"
        val labels = if (arabic) listOf("الرئيسية", "الحركات", "المراجعة", "الإعدادات")
            else listOf("Home", "Transactions", "Review", "Settings")
        val shortcuts = if (arabic) listOf("الحسابات", "البطاقات", "القروض", "الالتزامات")
            else listOf("Accounts", "Cards", "Loans", "Commitments")
        val icons = listOf(MasroofIcons.dashboard, MasroofIcons.recentTransactions, MasroofIcons.reviewQueue, MasroofIcons.settings)
        val amounts = listOf(BigDecimal("-20"), BigDecimal("40"), BigDecimal("90"))
        val fontScale = if (fold) 1.3f else 1f
        compose.waitUntil(10000) { (compose.activity.resources.configuration.screenWidthDp >= 600) == fold }
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.forLanguageTag(language))) }
            val context = LocalContext.current.createConfigurationContext(config)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalDensity provides Density(density.density, fontScale)) {
                MasroofTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxWidth().testTag("m2-home-fixture"), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(MasroofSpacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                            val state = DashboardHeroVisualState(if (arabic) "صافي حركة الحسابات" else "Net account movement",
                                if (mode == "populated") "1,234.50 SAR" else "—",
                                if (arabic) "الوارد" else "Inflow", if (mode == "populated") "2,000 SAR" else "—",
                                if (arabic) "الصادر" else "Outflow", if (mode == "populated") "765.50 SAR" else "—")
                            if (original) com.baraa.masroof.instrumentation.m2reference.MasroofHeroCard(state.title, state.remaining) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(verticalArrangement = Arrangement.spacedBy(MasroofReference.heroFooterGap)) {
                                        MasroofText(state.inflowLabel, style = MaterialTheme.typography.bodySmall)
                                        MasroofText(state.inflow, style = MasroofReference.heroFooterValue)
                                    }
                                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(MasroofReference.heroFooterGap)) {
                                        MasroofText(state.outflowLabel, style = MaterialTheme.typography.bodySmall)
                                        MasroofText(state.outflow, style = MasroofReference.heroFooterValue)
                                    }
                                }
                            } else DashboardHomeHero(state, MasroofIcons.appLogo, {}, {})
                            if (mode == "populated") {
                                if (original) com.baraa.masroof.instrumentation.m2reference.MasroofShortcutStrip(shortcuts.mapIndexed { i, label ->
                                    com.baraa.masroof.instrumentation.m2reference.MasroofShortcutItem(label, icons[i], MasroofCardAccent.Account) { shortcut = i }
                                }) else MasroofShortcutStrip(shortcuts.mapIndexed { i, label -> MasroofShortcutItem(label, icons[i], MasroofCardAccent.Account) { shortcut = i } })
                                if (original) com.baraa.masroof.instrumentation.m2reference.MasroofInteractiveLineChart(amounts, BigDecimal("30"),
                                    Modifier.testTag("m2-chart"), point, { point = it }, { "day-$it" }, axisLabel = { (it + 1).toString() }, currencyLabel = "SAR", showAverage = true)
                                else MasroofInteractiveLineChart(amounts, BigDecimal("30"), Modifier.testTag("m2-chart"), point,
                                    { point = it }, { "day-$it" }, axisLabel = { (it + 1).toString() }, currencyLabel = "SAR", showAverage = true)
                            } else Text(when (mode) {
                                "unknown" -> if (arabic) "غير متوفر" else "Unavailable"
                                "empty" -> if (arabic) "لا توجد عمليات" else "No transactions"
                                "loading" -> if (arabic) "جار التحميل" else "Loading"
                                else -> if (arabic) "تعذر التحميل" else "Could not load"
                            }, modifier = Modifier.testTag("m2-status"))
                            if (original) com.baraa.masroof.instrumentation.m2reference.MasroofBottomNavigation(labels.mapIndexed { i, label ->
                                com.baraa.masroof.instrumentation.m2reference.MasroofNavigationItem(label, icons[i], tab == i)
                            }) { tab = it } else MasroofBottomNavigation(labels.mapIndexed { i, label -> MasroofNavigationItem(label, icons[i], tab == i) }) { tab = it }
                        }
                    }
                }
            }
        }
        val prefix = "m2-home-$language-${if (dark) "dark" else "light"}-${if (fold) "fold" else "phone"}"
        listOf("populated", "unknown", "empty", "loading", "error").forEach { state ->
            compose.runOnIdle { original = true; mode = state }
            compose.waitForIdle()
            val before = compose.onNodeWithTag("m2-home-fixture").captureToImage().asAndroidBitmap()
            capture("$prefix-$state-before", before, language, dark, fontScale)
            compose.runOnIdle { original = false }
            compose.waitForIdle()
            val after = compose.onNodeWithTag("m2-home-fixture").captureToImage().asAndroidBitmap()
            capture("$prefix-$state-shared", after, language, dark, fontScale)
            assertEquals(before.width, after.width); assertEquals(before.height, after.height)
            val a = IntArray(before.width * before.height); val b = IntArray(after.width * after.height)
            before.getPixels(a, 0, before.width, 0, 0, before.width, before.height)
            after.getPixels(b, 0, after.width, 0, 0, after.width, after.height)
            assertEquals("$prefix $state extraction pixels", 0, a.indices.count { a[it] != b[it] })
        }
        compose.onNodeWithText(labels[2]).performClick()
        compose.runOnIdle { assertEquals(2, tab); mode = "populated" }
        compose.onNodeWithTag("dashboard-shortcut-2").performClick()
        compose.runOnIdle { assertEquals(2, shortcut) }
        val actions = compose.onNodeWithTag("m2-chart").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.first().action()) }
        compose.runOnIdle { assertEquals(0, point) }
    }

    private fun command(value: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(value)).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun capture(name: String, bitmap: Bitmap, language: String, dark: Boolean, fontScale: Float) {
        val context = compose.activity
        val directory = File(context.getExternalFilesDir(null), "m2-home-parity").apply { check(isDirectory || mkdirs()) }
        val png = File(directory, "$name.png")
        png.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val metadata = File(directory, "$name.txt")
        metadata.writeText("language=$language\ndark=$dark\nfontScale=$fontScale\napi=${Build.VERSION.SDK_INT}\n${context.resources.configuration}\n" + compose.onRoot().printToString())
        command("mkdir -p /sdcard/beta-dashboard-screenshots")
        command("cp ${png.absolutePath} /sdcard/beta-dashboard-screenshots/$name.png")
        command("cp ${metadata.absolutePath} /sdcard/beta-dashboard-screenshots/$name.txt")
        assertEquals(metadata.readText(), command("cat /sdcard/beta-dashboard-screenshots/$name.txt"))
    }
}
