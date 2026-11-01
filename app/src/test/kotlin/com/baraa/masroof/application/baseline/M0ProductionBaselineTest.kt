package com.baraa.masroof.application.baseline

import com.baraa.masroof.presentation.navigation.HomeDestination
import com.baraa.masroof.presentation.navigation.SettingsDestination
import com.baraa.masroof.presentation.navigation.encode
import com.baraa.masroof.presentation.onboarding.OnboardingStep
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0 lock for the production baseline at 5bb7e3b276b3905177482979b236362ad5b2f1df.
 *
 * Layer sizes are reported, not frozen as a growth ceiling.
 */
class M0ProductionBaselineTest {
    @Test
    fun productionTree_reportsLayerCountsWithoutFreezingGrowth() {
        val files = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("expected a non-empty production tree", files.isNotEmpty())
        val byTop = files.groupingBy { it.relativeTo(sourceRoot).invariantSeparatorsPath.substringBefore('/') }
            .eachCount()
        println(
            "M0_PRODUCTION_TREE total=${files.size} " +
                byTop.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" },
        )
        assertTrue(byTop.getValue("application") > 0)
        assertTrue(byTop.getValue("presentation") > 0)
        assertTrue(byTop.getValue("domain") > 0)
        assertTrue(byTop.getValue("data") > 0)
        assertTrue(byTop.getValue("bank") > 0)
        assertFalse(files.any { it.invariantSeparatorsPath.contains("/application/beta/") })
        assertFalse(files.any { it.invariantSeparatorsPath.contains("/presentation/beta/") })
        val gradle = File("build.gradle.kts").readText()
        assertFalse(gradle.contains("project(\":lab:android\")"))
        assertFalse(gradle.contains("parsing-lab"))
    }

    @Test
    fun processingAndReleaseEntrypoints_exist() {
        listOf(
            "sms/receiver/IncomingSmsReceiver.kt",
            "application/sms/LiveSmsIntake.kt",
            "application/ingestion/CaptureBankSmsUseCase.kt",
            "application/sms/LiveSmsWorkScheduler.kt",
            "application/sms/LiveSmsProcessingWorker.kt",
            "application/ingestion/ProcessStoredSmsUseCase.kt",
            "application/sms/HistoricalSmsScanner.kt",
            "application/sms/HistoricalSmsBatchProcessor.kt",
            "application/ingestion/ProcessRawSmsUseCase.kt",
            "application/transaction/ReconciliationCompletionPolicy.kt",
            "parsing/finalize/ParseFinalizer.kt",
            "bank/BankSmsRegistry.kt",
            "application/dashboard/DashboardProjectionBuilder.kt",
            "application/update/ApkInstaller.kt",
            "application/update/UpdateChecker.kt",
        ).forEach { relative ->
            assertTrue(relative, File(sourceRoot, relative).isFile)
        }
    }

    @Test
    fun userPaths_selectBanksThenDiscoverAndReview() {
        assertEquals(
            listOf("WELCOME", "BANKS", "PERMISSION", "IMPORT_DATE", "IMPORTING", "OWNERSHIP", "FINALIZE", "HOME"),
            OnboardingStep.entries.map { it.name },
        )
        assertEquals("my_accounts", SettingsDestination.MyAccounts.encode())
        assertEquals("my_cards", SettingsDestination.MyCards.encode())
        assertEquals("about", SettingsDestination.About.encode())
        assertTrue(HomeDestination.entries.any { it.name == "Review" })
        assertFalse(File(sourceRoot, "presentation/navigation/SettingsDestination.kt").readText().contains("add_card"))
        assertFalse(File(sourceRoot, "presentation/navigation/AppNavigation.kt").readText().contains("Beta"))
    }

    @Test
    fun noTestIsIgnored() {
        val annotation = Regex("""(?m)^\s*@Ignore\b""")
        val ignored = listOf(File("src/test"), File("src/androidTest"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { annotation.containsMatchIn(it.readText()) }
        assertEquals(emptyList<File>(), ignored)
    }

    private companion object {
        val sourceRoot = File("src/main/kotlin/com/baraa/masroof")
    }
}

