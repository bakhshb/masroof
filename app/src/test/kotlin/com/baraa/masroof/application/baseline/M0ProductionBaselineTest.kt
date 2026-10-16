package com.baraa.masroof.application.baseline

import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.presentation.navigation.HomeDestination
import com.baraa.masroof.presentation.navigation.SettingsDestination
import com.baraa.masroof.presentation.navigation.encode
import com.baraa.masroof.presentation.onboarding.OnboardingStep
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0 lock for the production baseline at 5bb7e3b276b3905177482979b236362ad5b2f1df.
 *
 * Beta files are counted apart from the frozen production tree. Golden ledger
 * bytes are hashed so an expectation change has to be explicit. Device startup
 * and bulk SMS import are left unset: this suite does not invent those samples.
 */
class M0ProductionBaselineTest {
    @Test
    fun productionTree_matchesTheFrozenLayerCounts() {
        val files = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val beta = files.filter(::isBetaSource)
        val frozen = files.filterNot(::isBetaSource)
        assertEquals(5, beta.size)
        assertEquals(
            setOf(
                "application/beta/BetaEnvironment.kt",
                "application/beta/BetaContainer.kt",
                "presentation/beta/BetaActivity.kt",
                "presentation/beta/BetaEntry.kt",
                "presentation/beta/BetaShell.kt",
            ),
            beta.map { it.relativeTo(sourceRoot).invariantSeparatorsPath }.toSet(),
        )
        assertEquals(557, frozen.size)
        assertEquals(168, count(frozen, "application"))
        assertEquals(164, count(frozen, "presentation"))
        assertEquals(84, count(frozen, "domain"))
        assertEquals(79, count(frozen, "data"))
        assertEquals(27, count(frozen, "bank"))
        assertEquals(3, count(frozen, "core"))
        assertEquals(20, count(frozen, "parsing"))
        assertEquals(10, count(frozen, "sms"))
        assertEquals(2, frozen.count { it.parentFile == sourceRoot })
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
    fun roomV18_andImportableBackupSchemas_areTheBaseline() {
        assertEquals(18, MasroofDatabase.VERSION)
        assertEquals("masroof.db", MasroofDatabase.NAME)
        assertEquals("71f1193767b3aa12bcb6cc6e7ce77147", MasroofDatabase.IDENTITY_HASH)
        assertEquals(
            mapOf(
                5 to "d192ca81655b31f5e6c203239148ad42",
                6 to "bae309478a1209a31e06cfcbde30b56f",
                7 to "5b63e94003a7d853d7896f020bbec1ec",
                8 to "051d1cf4633e66c7ae6b851428871ab4",
                9 to "a673ee53e423dc6952d5514ed2d14206",
                10 to "7e3a9698a7d188a855b1e6736a1fd073",
                11 to "7ab199e3e48084cea08345da07a0daeb",
                12 to "7e4054824f833d3869883fe0565893db",
                13 to "91c2c7a8f0b96cf4a9dc6088ba92e085",
                14 to "91c2c7a8f0b96cf4a9dc6088ba92e085",
                15 to "50ee81de5b142c23e1fe6afe86cae53d",
                16 to "9ca34d4e25b5d478101c5b89add2cc70",
                17 to "f18e84acb2bf790d0355fdb694267a1a",
                18 to "71f1193767b3aa12bcb6cc6e7ce77147",
            ),
            MasroofDatabase.IMPORTABLE_BACKUP_VERSIONS,
        )
    }

    @Test
    fun toolchainAndSideloadUpdate_matchTheReviewedBuild() {
        val versions = File("../gradle/libs.versions.toml").readText()
        assertEquals("1.9.24", version(versions, "kotlin"))
        assertEquals("8.5.2", version(versions, "agp"))
        assertEquals("2.6.1", version(versions, "room"))
        assertEquals("2.9.0", version(versions, "workmanager"))
        assertEquals("2024.06.00", version(versions, "composeBom"))
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("applicationId = \"com.baraa.masroof\""))
        assertTrue(gradle.contains("compileSdk = 34"))
        assertTrue(gradle.contains("targetSdk = 34"))
        assertTrue(gradle.contains("minSdk = 26"))
        val release = File("../.github/workflows/release.yml").readText()
        assertTrue(release.contains("assembleRelease"))
        assertTrue(release.contains("-PappVersionCode="))
        assertTrue(release.contains("RELEASE_KEYSTORE_BASE64"))
        assertTrue(release.contains("storePassword=\${RELEASE_KEYSTORE_PASSWORD}"))
        assertFalse(release.contains("storePassword=\""))
        val gitignore = File("../.gitignore").readText()
        assertTrue(gitignore.contains("keystore.properties"))
        assertTrue(gitignore.contains("*.keystore"))
        assertFalse(File("../keystore.properties").exists())
        val installer = File(sourceRoot, "application/update/ApkInstaller.kt").readText()
        assertTrue(installer.contains("application/vnd.android.package-archive"))
        assertTrue(installer.contains("Intent.ACTION_VIEW"))
        val checker = File(sourceRoot, "application/update/UpdateChecker.kt").readText()
        assertTrue(checker.contains("manifest.versionCode > installedVersionCode"))
    }

    @Test
    fun userPaths_areDiscoveryThenReview_notManualCreation() {
        assertEquals(
            listOf("WELCOME", "PERMISSION", "IMPORT_DATE", "IMPORTING", "OWNERSHIP", "FINALIZE", "HOME"),
            OnboardingStep.values().map { it.name },
        )
        assertEquals("my_accounts", SettingsDestination.MyAccounts.encode())
        assertEquals("my_cards", SettingsDestination.MyCards.encode())
        assertEquals("developer_settings", SettingsDestination.DeveloperSettings.encode())
        assertTrue(HomeDestination.values().any { it.name == "Review" })
        assertFalse(File(sourceRoot, "presentation/navigation/SettingsDestination.kt").readText().contains("add_card"))
        assertFalse(File(sourceRoot, "presentation/navigation/AppNavigation.kt").readText().contains("Beta"))
    }

    @Test
    fun goldenLedger_hashesAndActiveScenarios_stayFixed() {
        val root = goldenRoot()
        val files = root.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty().sortedBy { it.name }
        assertEquals(GOLDEN_SHA256.keys.sorted(), files.map { it.name })
        files.forEach { file ->
            assertEquals(file.name, GOLDEN_SHA256.getValue(file.name), sha256(file))
            val text = file.readText()
            assertTrue(text.contains("\"status\": \"ACTIVE\"") || text.contains("\"status\":\"ACTIVE\""))
        }
        assertEquals(
            listOf(
                "live_inbox_twin",
                "m1_two_day_same_amount",
                "m5_cross_bank_suffix",
                "m6_account_refund",
                "m8_asof_fx",
                "one_intra_pair",
                "repeated_separate_notices",
            ),
            files.map { file ->
                Regex(""""id"\s*:\s*"([^"]+)"""").find(file.readText())!!.groupValues[1]
            },
        )
        val oracle = File("src/test/kotlin/com/baraa/masroof/application/dashboard/GoldenLedgerOracleTest.kt").readText()
        val characterization = File(
            "src/test/kotlin/com/baraa/masroof/application/dashboard/DashboardFixtureCharacterizationTest.kt",
        ).readText()
        assertTrue(oracle.contains("fun oneIntraPair_matchesOracle_acrossReprocessing"))
        assertTrue(oracle.contains("fun accountRefund_matchesOracle_acrossReprocessing"))
        assertTrue(oracle.contains("fun asOfFx_matchesOracle_acrossReprocessing"))
        assertTrue(characterization.contains("fun scopedEvidence_matchesWholeHistoryProjection_forFixtureCorpus"))
        assertTrue(characterization.contains("fun scopedEvidence_matchesWholeHistoryProjection_acrossLongHistory"))
    }

    @Test
    fun knownIgnoredTests_areAnEmptyBaseline() {
        val annotation = Regex("""(?m)^\s*@Ignore\b""")
        val ignored = listOf(File("src/test"), File("src/androidTest"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { annotation.containsMatchIn(it.readText()) }
        assertEquals(emptyList<File>(), ignored)
        assertNull(M0DeviceBaseline.startupP50Millis)
        assertNull(M0DeviceBaseline.importTenThousandSmsP50Millis)
    }

    private fun count(files: List<File>, top: String): Int =
        files.count { it.relativeTo(sourceRoot).invariantSeparatorsPath.startsWith("$top/") }

    private fun isBetaSource(file: File): Boolean {
        val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
        return relative.startsWith("application/beta/") || relative.startsWith("presentation/beta/")
    }

    private fun version(toml: String, key: String): String =
        Regex("""(?m)^$key = "([^"]+)"""").find(toml)?.groupValues?.get(1)
            ?: error("Missing version $key")

    private fun goldenRoot(): File = listOf(
        File("src/test/resources/testdata/golden_ledger"),
        File("app/src/test/resources/testdata/golden_ledger"),
    ).first { it.isDirectory }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        val sourceRoot = File("src/main/kotlin/com/baraa/masroof")

        val GOLDEN_SHA256 = mapOf(
            "active_live_inbox_twin.json" to "2d4f2b205b2a59c7b63cf43e5c6b96713d0132ae544d6b8dc6fff09c0520f72b",
            "active_m1_two_day_same_amount.json" to "e3b8546fcba4d7afd9188d37304c86c55f68ef1b73f1f128237ea03f4fa59297",
            "active_m5_cross_bank_suffix.json" to "ba094159302f73b3b0d0e2e6b01a712e92dfb4f54080f5dbb59671525d4efb57",
            "active_m6_account_refund.json" to "1a4ef4d7098b64f946456d12c47a24adcf5346d491876c5608433f9531ecb2bc",
            "active_m8_asof_fx.json" to "e082efdf7bfb76dfa5fd075db38cfa923069261f788a264b43962fe25524d813",
            "active_one_intra_pair.json" to "e464804ff448411e5c6140a84b7e8a8ea88a8b76c345d0daddf2c549cce4deb3",
            "active_repeated_separate_notices.json" to "67c1e1a0c2d1d4e14fa1d54147aa7aff6b448397504c16c9e47d91ab949d3966",
        )
    }
}

/**
 * Device samples are recorded only after an on-device run. Null means unmeasured.
 */
object M0DeviceBaseline {
    val startupP50Millis: Long? = null
    val importTenThousandSmsP50Millis: Long? = null
}
