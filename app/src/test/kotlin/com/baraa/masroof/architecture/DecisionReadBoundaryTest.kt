package com.baraa.masroof.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Processing reads user decisions from `user_decision` only (plan 2.3). The legacy stores, review resolutions
 * (`resolutionKind`, [LEGACY_TOKENS]), are still
 * written in the same transaction as each decision, so only the files in [ALLOWED] may name them: their writers,
 * the stores and mappers themselves, screens that display a review, and the decision
 * reads kept on purpose (docs/ARCHITECTURE.md, "Reading decisions"). The allowlist is per file: an allowed file may name
 * any legacy token anywhere in it, so keep each entry's reason exact. Fix a new read by going through
 * `UserDecisionRepository.effectiveFor`; never add an entry without the owner's reason.
 */
class DecisionReadBoundaryTest {

    @Test
    fun productionReadsDecisionsFromTheStoreOnly() {
        assertTrue("Expected Kotlin sources", sources.isNotEmpty())
        assertEquals(emptyList<String>(), violations(sources.map { relativePath(it) to it.readText() }))
    }

    @Test
    fun everyAllowedFileStillNamesALegacyStore() {
        val named = sources.filter { file -> LEGACY_TOKENS.any { it.containsMatchIn(file.readText()) } }
            .map(::relativePath).toSet()
        assertEquals(emptySet<String>(), ALLOWED.keys - named)
    }

    @Test
    fun rule_flagsEachLegacyReadOutsideTheAllowedFiles() {
        val samples = listOf(
            "application/Sample1.kt" to "if (review.resolutionKind == null) post()",
            "application/Sample6.kt" to "kind == ReviewResolutionKind.USER_NON_FINANCIAL",
            "application/Sample10.kt" to "val bank = decisions.effectiveFor(id).selectedBankId",
            ALLOWED.keys.first() to "review.resolutionKind",
        )
        assertEquals(samples.take(2).map { it.first }, violations(samples))
    }

    private fun violations(files: List<Pair<String, String>>): List<String> =
        files.filter { (path, text) -> path !in ALLOWED && LEGACY_TOKENS.any { it.containsMatchIn(text) } }.map { it.first }

    private fun relativePath(file: File): String =
        file.canonicalFile.relativeTo(roots.first { file.canonicalFile.startsWith(it.canonicalFile) }.canonicalFile).invariantSeparatorsPath

    private companion object {
        // The app and the modules that moved out of it (the stores live in :data, the models in :finance-core).
        val roots = listOf("src/main", "../data/src/main", "../finance-core/src/main").map { File(it, "kotlin/com/baraa/masroof") }
        val sources: List<File> = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

        val LEGACY_TOKENS = listOf(
            Regex("""\bresolutionKind\b"""),
            Regex("""\bReviewResolutionKind\b"""),
        )

        private const val WRITER = "writes a decision's legacy trace in the same transaction as its user_decision row"
        private const val STORE = "the legacy store itself: model, interface, Room repository, DAO, entity or mapper"
        private const val DISPLAY = "shows a review on screen; never decides processing"

        val ALLOWED: Map<String, String> = mapOf(
            "application/review/ReviewWorkflowService.kt" to WRITER,
            "application/transaction/TransactionRestoreService.kt" to WRITER,
            "application/review/ReviewQueueUpdater.kt" to "$WRITER; never overwrites a user resolution",
            "data/repository/RoomManualReviewResolutionRepository.kt" to WRITER,
            "data/repository/RoomTransactionIgnoreRepository.kt" to WRITER,
            "data/repository/RoomTransactionReclassificationRepository.kt" to WRITER,
            "domain/model/ReviewResolutionKind.kt" to STORE,
            "domain/model/ReviewItem.kt" to STORE,
            "domain/repository/ReviewRepository.kt" to STORE,
            "domain/repository/ManualReviewResolutionRepository.kt" to STORE,
            "data/repository/RoomReviewRepository.kt" to STORE,
            "data/room/dao/ReviewItemDao.kt" to STORE,
            "data/room/entity/ReviewItemEntity.kt" to STORE,
            "data/room/mapper/ReviewItemMapper.kt" to STORE,
            "presentation/review/ReviewPresentationMapper.kt" to DISPLAY,
            "application/interpretation/InterpretationProvenanceWorkflow.kt" to "$DISPLAY (the provenance badge)",
            // Kept reads: the store is not an equivalent source for these yet (docs/ARCHITECTURE.md).
            "application/review/ReviewCorrectionClosure.kt" to
                "USER_CORRECTION closure: whether a correction's own pass posted is not in user_decision",
            "application/interpretation/BankInterpretationService.kt" to
                "learning feedback keys review decisions by review id and reads the resolved row's type",
            "application/ingestion/PatternEvidenceProtection.kt" to
                "a settled review, automatic settlement included, keeps its evidence; that is review state",
            "application/patterns/PendingPatternsWorkflow.kt" to "queue state: a REQUIRED review with no resolution",
        )
    }
}
