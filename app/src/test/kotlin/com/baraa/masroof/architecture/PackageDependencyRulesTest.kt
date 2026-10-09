package com.baraa.masroof.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces the package-level dependency policy while Masroof remains a single Gradle module.
 *
 * Temporary exceptions represent existing dependency debt and must be removed by the roadmap
 * PR named alongside each exception. New exceptions are not permitted.
 */
class PackageDependencyRulesTest {

    @Test
    fun core_hasNoMasroofDependencies() {
        assertPackagesDoNotImport(
            packages = listOf("core"),
            forbiddenImports = listOf("import com.baraa.masroof."),
        )
    }

    @Test
    fun parsing_doesNotDependOnOuterLayers() {
        assertPackagesDoNotImport(
            packages = listOf("parsing"),
            forbiddenImports = outerLayerImports,
        )
    }

    @Test
    fun bank_doesNotDependOnApplicationOrInfrastructure() {
        assertPackagesDoNotImport(
            packages = listOf("bank"),
            forbiddenImports = listOf(
                "import com.baraa.masroof.application.",
                "import com.baraa.masroof.data.",
                "import com.baraa.masroof.presentation.",
                "import com.baraa.masroof.sms.",
            ),
        )
    }

    @Test
    fun data_doesNotDependOnUiOrSms() {
        assertPackagesDoNotImport(
            packages = listOf("data"),
            forbiddenImports = listOf(
                "import com.baraa.masroof.presentation.",
                "import com.baraa.masroof.sms.",
            ),
        )
    }

    @Test
    fun application_doesNotDependOnPresentationOutsideTemporaryExceptions() {
        assertPackagesDoNotImport(
            packages = listOf("application"),
            forbiddenImports = listOf("import com.baraa.masroof.presentation."),
            allowedFiles = setOf(
                // Bootstrap exception — composition root may wire all layers.
                "application/AppContainer.kt",
            ),
        )
    }

    @Test
    fun sms_doesNotOrchestrateApplicationOutsideTemporaryExceptions() {
        assertPackagesDoNotImport(
            packages = listOf("sms"),
            forbiddenImports = listOf("import com.baraa.masroof.application."),
        )
    }

    @Test
    fun application_dashboard_doesNotDependOnBank() {
        assertPackagesDoNotImport(
            packages = listOf("application/dashboard"),
            forbiddenImports = listOf("import com.baraa.masroof.bank."),
        )
    }

    @Test
    fun presentation_doesNotDependOnDataOrSmsOutsideTemporaryExceptions() {
        assertPackagesDoNotImport(
            packages = listOf("presentation"),
            forbiddenImports = listOf(
                "import com.baraa.masroof.data.",
                "import com.baraa.masroof.sms.",
            ),
            allowedFiles = emptySet(),
        )
    }

    @Test
    fun presentation_doesNotUseDomainServicesOrPortsOutsideTemporaryExceptions() {
        assertFilesDoNotImport(
            files = kotlinFilesIn("presentation").filter { it.name.endsWith("ViewModel.kt") },
            forbiddenImports = listOf(
                "import com.baraa.masroof.domain.ownership.",
                "import com.baraa.masroof.domain.period.",
                "import com.baraa.masroof.domain.repository.",
                "import com.baraa.masroof.domain.rules.",
            ),
            allowedFiles = emptySet(),
        )
    }

    @Test
    fun presentation_navigation_doesNotConstructFinancialContainerIds() {
        assertPackagesDoNotImport(
            packages = listOf("presentation/navigation"),
            forbiddenImports = listOf("com.baraa.masroof.domain.ids.", "FinancialContainerIdFactory"),
        )
    }

    @Test
    fun presentation_viewModels_consumePreparedFinancialFacts() {
        assertFilesDoNotImport(
            files = kotlinFilesIn("presentation").filter { it.name.endsWith("ViewModel.kt") },
            forbiddenImports = listOf(
                "com.baraa.masroof.domain.ids.",
                "FinancialContainerIdFactory",
                "FinancialContainerIdParser",
                "ForeignPurchaseSarConverter",
                "TransactionSarEquivalentResolver",
                "AppliedExchangeRateSyncer",
                "CardTransactionInvolvementResolver",
                "LoanRepaymentAttribution",
                "DebitCardSpendClassifier",
                "DashboardTransactionFactsBuilder",
                ".convertsToSar(",
            ),
        )
    }

    @Test
    fun parsing_validationFirewall_doesNotResolveOwnershipOrTransactionMeaning() {
        listOf("parsing/validator", "parsing/finalize").forEach { pkg ->
            assertTrue("$pkg must exist", kotlinFilesIn(pkg).isNotEmpty())
        }
        assertPackagesDoNotImport(
            packages = listOf("parsing/validator", "parsing/finalize"),
            forbiddenImports = listOf(
                "import com.baraa.masroof.domain.ownership.",
                "import com.baraa.masroof.domain.assembly.",
                "import com.baraa.masroof.domain.matching.",
                "import com.baraa.masroof.domain.repository.",
                "import com.baraa.masroof.domain.model.FinancialTransactionType",
                "import com.baraa.masroof.domain.model.TransferOwnershipType",
            ),
        )
    }

    @Test
    fun liveSmsWork_isExecutionAdapterOnly() {
        val files = listOf(
            "LiveSmsProcessingWorker.kt",
            "LiveSmsWorkScheduler.kt",
            "ExchangeRateEnrichmentScheduler.kt",
        )
            .map { File(sourceRoot, "application/sms/$it") }
        files.forEach { assertTrue("${it.path} must exist", it.isFile) }
        assertFilesDoNotImport(
            files = files,
            forbiddenImports = listOf(
                "import com.baraa.masroof.bank.",
                "import com.baraa.masroof.parsing.",
                "import com.baraa.masroof.data.",
                "import com.baraa.masroof.domain.ownership.",
                "import com.baraa.masroof.domain.assembly.",
                "import com.baraa.masroof.domain.matching.",
                "import com.baraa.masroof.application.transaction.",
            ),
        )
    }

    @Test
    fun dashboardProjection_isReadOnly() {
        val files = listOf(
            "DashboardService.kt",
            "DashboardProjectionBuilder.kt",
            "DashboardEvidenceScope.kt",
            "AppliedExchangeRateSyncer.kt",
            "AnalysisDashboardProjection.kt",
            "AccountsDashboardProjection.kt",
            "CardsDashboardProjection.kt",
            "CommitmentsDashboardProjection.kt",
        ).map { File(sourceRoot, "application/dashboard/$it") }
        val writeCalls = listOf(
            ".save(",
            ".update(",
            ".updateAppliedExchangeRate(",
            ".replaceConfirmedHistoricalMerchantRate(",
            ".replaceExclusiveStaleLinks(",
            ".deleteIfExclusiveRawSmsLink(",
            ".unlinkRawSms(",
            ".linkRawSmsIfAbsent(",
        )
        files.forEach { file ->
            assertTrue("${file.path} must exist", file.isFile)
            val source = file.readText()
            writeCalls.forEach { call ->
                assertFalse(
                    "${file.path} must not call '$call'; persist enrichment in an application workflow",
                    source.contains(call),
                )
            }
        }
    }

    @Test
    fun domain_loan_hasNoProductionSources() {
        val productionSources = kotlinFilesIn("domain/loan")
        assertTrue(
            "Loan label mapping belongs in bank adapters at parse time",
            productionSources.isEmpty(),
        )
    }

    @Test
    fun application_dashboard_doesNotClassifyBankWordingFromSmsBody() {
        val files = listOf(
            "application/dashboard/CurrentAccountTransactionScope.kt",
            "application/dashboard/DebitCardSpendClassifier.kt",
        ).map { File(sourceRoot, it) }
        val forbidden = listOf(
            ".body",
            "comparisonBody",
            "سداد فاتورة",
            "المفوتر",
            "سداد بطاقة",
            "سحب نقدي",
            "خصمت",
            "تسديد",
        )
        files.forEach { file ->
            assertTrue("${file.path} must exist", file.isFile)
            val source = file.readText()
            forbidden.forEach { phrase ->
                assertFalse(
                    "${file.name} must classify from persisted parse facts, not '$phrase'",
                    source.contains(phrase),
                )
            }
        }
    }

    @Test
    fun application_dashboard_doesNotMapArabicLoanLabels() {
        val forbidden = listOf("تمويل شخصي", "سيارة", "عقار")
        kotlinFilesIn("application/dashboard").forEach { file ->
            val relativePath = file.relativeTo(sourceRoot).invariantSeparatorsPath
            val source = file.readText()
            forbidden.forEach { label ->
                assertFalse(
                    "$relativePath must not map Arabic loan labels; use ParsedEventDetails.loanType",
                    label in source,
                )
            }
        }
    }

    private fun assertPackagesDoNotImport(
        packages: List<String>,
        forbiddenImports: List<String>,
        allowedFiles: Set<String> = emptySet(),
    ) = assertFilesDoNotImport(
        files = packages.flatMap(::kotlinFilesIn),
        forbiddenImports = forbiddenImports,
        allowedFiles = allowedFiles,
    )

    private fun assertFilesDoNotImport(
        files: List<File>,
        forbiddenImports: List<String>,
        allowedFiles: Set<String> = emptySet(),
    ) {
        assertTrue("Expected Kotlin sources", files.isNotEmpty())
        files.forEach { file ->
            val relativePath = file.relativeTo(sourceRoot).invariantSeparatorsPath
            if (relativePath in allowedFiles) return@forEach
            val source = file.readText()
            forbiddenImports.forEach { forbiddenImport ->
                assertFalse(
                    "$relativePath must not contain '$forbiddenImport'. " +
                        "Add no exceptions; remove the documented temporary exception in its roadmap PR.",
                    source.contains(forbiddenImport),
                )
            }
        }
    }

    private fun kotlinFilesIn(packageName: String): List<File> {
        val root = File(sourceRoot, packageName)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        val sourceRoot = File("src/main/kotlin/com/baraa/masroof")

        val outerLayerImports = listOf(
            "import com.baraa.masroof.application.",
            "import com.baraa.masroof.bank.",
            "import com.baraa.masroof.data.",
            "import com.baraa.masroof.presentation.",
            "import com.baraa.masroof.sms.",
        )
    }
}
