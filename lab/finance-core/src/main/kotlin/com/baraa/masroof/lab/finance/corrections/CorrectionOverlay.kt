package com.baraa.masroof.lab.finance.corrections

import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.Money
import java.util.Collections

/** Supplied by the trusted interpretation adapter, never inferred from user metadata. */
enum class ObservationSafety { ORDINARY, SECURITY, UNEXECUTED, INFORMATIONAL }

data class CorrectionObservation(val input: FinancialClassificationInput, val safety: ObservationSafety)

sealed interface FinancialDecision {
    /** A metadata edit keeps any earlier explicit financial decision; it cannot revoke it. */
    data object Keep : FinancialDecision
    data class ConfirmFamilyOrAmount(val type: FinancialType? = null, val amount: Money? = null) : FinancialDecision {
        init { require(type != null || amount != null) }
    }
    data class ResolveFinancialType(val type: FinancialType) : FinancialDecision
    data object SuppressAsNonFinancial : FinancialDecision
}

/** A displayed decision binds to the exact interpretation/evidence the user reviewed. */
data class CorrectionRequest(
    val decisionId: String,
    val key: PostingKey,
    val correctionRevision: Long,
    val expectedInterpretation: Long,
    val expectedEvidenceIds: Set<String>,
    val decision: FinancialDecision,
    val metadata: Map<String, String> = emptyMap(),
    val expectedCorrectionRevision: Long = 0,
) {
    init {
        require(decisionId.isNotBlank() && correctionRevision > 0 && expectedInterpretation >= 0)
        require(expectedCorrectionRevision >= 0)
        require(expectedEvidenceIds.isNotEmpty() && expectedEvidenceIds.none(String::isBlank))
        require(metadata.keys.all { it in setOf("merchant", "counterparty", "note") })
    }
}

enum class CorrectionRejection { IDENTITY, STALE, CONFLICT, EVIDENCE, UNTRUSTED_BASE, UNSUPPORTED_CHANGE }
enum class CorrectionDisposition { FINANCIAL_REVIEW, CLASSIFIED, SUPPRESSED }

/** Constructor is internal to this workflow: callers cannot forge an already confirmed overlay. */
class DecisionOverlay private constructor(
    val key: PostingKey,
    val correctionRevision: Long,
    val interpretationRevision: Long,
    val original: FinancialClassificationInput,
    val latestObservation: CorrectionObservation,
    val safetyHold: Boolean,
    val metadata: Map<String, String>,
    val history: List<CorrectionRequest>,
    internal val financial: FinancialClassificationInput?,
    internal val suppressed: Boolean,
) {
    companion object {
        internal fun create(key: PostingKey, correction: Long, interpretation: Long,
            original: FinancialClassificationInput, metadata: Map<String, String>, history: List<CorrectionRequest>,
            financial: FinancialClassificationInput?, suppressed: Boolean, latest: CorrectionObservation, safetyHold: Boolean) = DecisionOverlay(key, correction,
            interpretation, original.freeze(), latest.copy(input = latest.input.freeze()), safetyHold, immutableMap(metadata), immutableList(history), financial?.freeze(), suppressed)
    }
}

sealed interface CorrectionResult {
    data class Rejected(val reason: CorrectionRejection) : CorrectionResult
    data class Applied(val overlay: DecisionOverlay, val disposition: CorrectionDisposition,
        val classification: FinancialClassification?, val effectiveInput: FinancialClassificationInput?) : CorrectionResult
}

/** Pure transitions. The caller must persist decision + posting atomically before acknowledging success. */
object CorrectionOverlays {
    fun submit(observation: CorrectionObservation, request: CorrectionRequest, previous: DecisionOverlay? = null): CorrectionResult {
        val input = observation.input.freeze()
        val command = request.copy(expectedEvidenceIds = immutableSet(request.expectedEvidenceIds), metadata = immutableMap(request.metadata))
        baseError(observation.copy(input = input), previous)?.let { return CorrectionResult.Rejected(it) }
        if (command.key != input.key) return CorrectionResult.Rejected(CorrectionRejection.IDENTITY)
        if (command.expectedInterpretation != input.revision.interpretation) return CorrectionResult.Rejected(CorrectionRejection.STALE)
        if (command.expectedEvidenceIds != input.evidenceIds) return CorrectionResult.Rejected(CorrectionRejection.EVIDENCE)
        if (previous != null) {
            if (command.correctionRevision < previous.correctionRevision) return CorrectionResult.Rejected(CorrectionRejection.STALE)
            if (command.correctionRevision == previous.correctionRevision) {
                return if (command == previous.history.last()) rebase(observation, previous)
                else CorrectionResult.Rejected(CorrectionRejection.CONFLICT)
            }
            if (previous.history.any { it.decisionId == command.decisionId }) return CorrectionResult.Rejected(CorrectionRejection.CONFLICT)
        }
        if (command.expectedCorrectionRevision != (previous?.correctionRevision ?: 0L)) return CorrectionResult.Rejected(CorrectionRejection.STALE)
        // A new explicit decision starts from the previous manually preserved financial facts, not
        // silently changed parser facts. Keep never erases prior authority or suppression.
        val selected = previous?.financial ?: input
        val amountChange = (command.decision as? FinancialDecision.ConfirmFamilyOrAmount)?.amount
        if (amountChange != null && amountChange.currency != selected.amount.currency && selected.appliedFx != null) {
            // A complete rate belongs to its native currency. This API has no explicit FX-edit proof.
            return CorrectionResult.Rejected(CorrectionRejection.UNSUPPORTED_CHANGE)
        }
        val financial = when (val decision = command.decision) {
            FinancialDecision.Keep -> previous?.financial
            is FinancialDecision.ConfirmFamilyOrAmount -> selected.copy(storedType = decision.type ?: selected.storedType,
                amount = decision.amount ?: selected.amount, authority = CorrectionAuthority.CONFIRMED_FAMILY_OR_AMOUNT)
            is FinancialDecision.ResolveFinancialType -> selected.copy(storedType = decision.type,
                authority = CorrectionAuthority.USER_FINANCIAL_TYPE)
            FinancialDecision.SuppressAsNonFinancial -> previous?.financial
        }
        val suppressed = when (command.decision) {
            FinancialDecision.Keep -> previous?.suppressed ?: false
            FinancialDecision.SuppressAsNonFinancial -> true
            else -> false
        }
        val overlay = DecisionOverlay.create(input.key, command.correctionRevision, input.revision.interpretation,
            previous?.original ?: input, (previous?.metadata ?: emptyMap()) + command.metadata,
            (previous?.history ?: emptyList()) + command, financial, suppressed, observation.copy(input = input),
            previous?.safetyHold == true || observation.safety != ObservationSafety.ORDINARY)
        return evaluate(observation.copy(input = input), overlay)
    }

    fun rebase(observation: CorrectionObservation, overlay: DecisionOverlay): CorrectionResult {
        val input = observation.input.freeze()
        baseError(observation.copy(input = input), overlay)?.let { return CorrectionResult.Rejected(it) }
        return evaluate(observation.copy(input = input), overlay)
    }

    private fun baseError(observation: CorrectionObservation, previous: DecisionOverlay?): CorrectionRejection? {
        val input = observation.input
        return when {
        input.authority != CorrectionAuthority.NONE || input.revision.correction != 0L -> CorrectionRejection.UNTRUSTED_BASE
        previous != null && input.key != previous.key -> CorrectionRejection.IDENTITY
        previous != null && input.revision.interpretation < previous.interpretationRevision -> CorrectionRejection.STALE
        input.evidenceIds.isEmpty() || input.evidenceIds.any(String::isBlank) -> CorrectionRejection.EVIDENCE
        previous != null && !input.evidenceIds.containsAll(previous.latestObservation.input.evidenceIds) -> CorrectionRejection.EVIDENCE
        previous != null && input.revision.interpretation == previous.interpretationRevision && observation != previous.latestObservation -> CorrectionRejection.CONFLICT
        else -> null
        }
    }

    private fun evaluate(observation: CorrectionObservation, overlay: DecisionOverlay): CorrectionResult {
        val base = observation.input
        val next = DecisionOverlay.create(overlay.key, overlay.correctionRevision, base.revision.interpretation,
            overlay.original, overlay.metadata, overlay.history, overlay.financial, overlay.suppressed,
            observation, overlay.safetyHold || observation.safety != ObservationSafety.ORDINARY)
        if (next.suppressed) return if (next.safetyHold) CorrectionResult.Applied(next,
            CorrectionDisposition.FINANCIAL_REVIEW, FinancialClassification.Review(immutableSet(
                base.unresolved + (next.financial?.unresolved ?: emptySet()) + ReviewReason.VALIDATION)), null)
        else CorrectionResult.Applied(next, CorrectionDisposition.SUPPRESSED, null, null)
        val selected = next.financial ?: base.copy(authority = CorrectionAuthority.METADATA_ONLY)
        val selectedIds = listOfNotNull((selected.source as? FinancialEndpoint.Owned)?.id,
            (selected.destination as? FinancialEndpoint.Owned)?.id, selected.financingLoan).toSet()
        val linkedIds = selectedIds + selectedIds.mapNotNull { selected.assets[it]?.parentId }
        val changedOwnership = next.financial != null && linkedIds.any { selected.assets[it] != base.assets[it] }
        val unresolved = selected.unresolved + base.unresolved +
            (if (next.safetyHold) setOf(ReviewReason.VALIDATION) else emptySet()) +
            (if (changedOwnership) setOf(ReviewReason.OWNERSHIP) else emptySet()) +
            // This workflow has no checked transfer-pair proof. A manual type is not that proof.
            (if (selected.storedType == FinancialType.SELF_TRANSFER) setOf(ReviewReason.AMBIGUOUS_MATCH) else emptySet())
        val effective = selected.copy(revision = PostingRevision(next.correctionRevision, base.revision.interpretation),
            assets = base.assets, evidenceIds = base.evidenceIds, validatedParse = base.validatedParse,
            unresolved = unresolved).freeze()
        val classification = FinancialImpactClassifier.classify(effective).freeze()
        return CorrectionResult.Applied(next, if (classification is FinancialClassification.Classified)
            CorrectionDisposition.CLASSIFIED else CorrectionDisposition.FINANCIAL_REVIEW, classification, effective)
    }
}

private fun FinancialClassificationInput.freeze() = copy(assets = immutableMap(assets), evidenceIds = immutableSet(evidenceIds),
    unresolved = immutableSet(unresolved))
private fun FinancialClassification.freeze(): FinancialClassification = when (this) {
    is FinancialClassification.Review -> copy(reasons = immutableSet(reasons))
    is FinancialClassification.Classified -> copy(input = input.freeze(), metrics = immutableMap(metrics), accountMovements = immutableList(accountMovements))
}
private fun <T> immutableSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
