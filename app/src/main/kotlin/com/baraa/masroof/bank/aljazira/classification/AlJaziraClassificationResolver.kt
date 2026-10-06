package com.baraa.masroof.bank.aljazira.classification

import com.baraa.masroof.domain.model.MessageFamily

/**
 * Deterministic evidence resolution:
 * 1. collect every matching rule's candidate,
 * 2. keep the highest specificity tier,
 * 3. one family left → it wins (evidence lists the families it outranked),
 * 4. several families left → UNKNOWN (review), never an arbitrary pick.
 * 5. an informational top tier plus a strong money-movement candidate → UNKNOWN,
 *    listing both families. Security (OTP) is exempt and still wins.
 *
 * Rule registration order cannot change the result.
 */
internal class AlJaziraClassificationResolver(
    private val rules: List<AlJaziraClassificationRule>,
) {
    fun resolve(text: String): AlJaziraClassification {
        val candidates = rules.mapNotNull { it.evaluate(text) }
        if (candidates.isEmpty()) return UNRECOGNIZED

        val topSpecificity = candidates.maxOf { it.specificity }
        val strongest = candidates.filter { it.specificity == topSpecificity }
        // OTP/security stays non-financial even when the body quotes a purchase.
        if (topSpecificity < AlJaziraClassificationSpecificity.SECURITY) {
            crossTierInformationalCollision(strongest, candidates)?.let { return it }
        }
        return pickTier(strongest, candidates)
    }

    /**
     * An account notice, balance notice, or statement must not hide a real
     * money movement. The informational tier still wins when nothing financial matched.
     * A generic fee line is not strong movement. A higher financial tier (refund of a
     * purchase) still outranks the movement it describes.
     */
    private fun crossTierInformationalCollision(
        strongest: List<AlJaziraClassificationCandidate>,
        candidates: List<AlJaziraClassificationCandidate>,
    ): AlJaziraClassification? {
        if (strongest.any { !it.classification.family.isInformational() }) return null
        val financial = candidates.filter { it.isStrongFinancialMovement() }
        if (financial.isEmpty()) return null
        return ambiguousAcrossTiers(strongest + financial)
    }

    private fun pickTier(
        strongest: List<AlJaziraClassificationCandidate>,
        candidates: List<AlJaziraClassificationCandidate>,
    ): AlJaziraClassification {
        if (strongest.map { it.classification.family }.toSet().size > 1) {
            return ambiguous(strongest)
        }

        val winner = strongest
            .sortedWith(compareByDescending<AlJaziraClassificationCandidate> { it.rank }.thenBy { it.ruleId })
            .first()
        val outranked = candidates
            .filter { it.classification.family != winner.classification.family }
            .map { "$OUTRANKED_PREFIX${it.ruleId}" }
            .distinct()
            .sorted()
        return winner.classification.copy(evidence = winner.classification.evidence + outranked)
    }

    private fun ambiguous(strongest: List<AlJaziraClassificationCandidate>): AlJaziraClassification =
        AlJaziraClassification(
            family = MessageFamily.UNKNOWN,
            evidence = listOf(AMBIGUOUS_EVIDENCE) +
                strongest.map { "$CANDIDATE_PREFIX${it.ruleId}" }.distinct().sorted(),
            confidence = AMBIGUOUS_CONFIDENCE,
        )

    private fun ambiguousAcrossTiers(
        conflicting: List<AlJaziraClassificationCandidate>,
    ): AlJaziraClassification {
        val tokens = (
            conflicting.map { "$CANDIDATE_PREFIX${it.ruleId}" } +
                conflicting.map { "$FAMILY_PREFIX${it.classification.family.name}" }
            ).distinct().sorted()
        return AlJaziraClassification(
            family = MessageFamily.UNKNOWN,
            evidence = listOf(AMBIGUOUS_EVIDENCE) + tokens,
            confidence = AMBIGUOUS_CONFIDENCE,
        )
    }

    private fun MessageFamily.isInformational(): Boolean = when (this) {
        MessageFamily.NON_FINANCIAL,
        MessageFamily.BALANCE_NOTICE,
        MessageFamily.OTP,
        -> true
        else -> false
    }

    private fun AlJaziraClassificationCandidate.isStrongFinancialMovement(): Boolean =
        (specificity == AlJaziraClassificationSpecificity.PRODUCT ||
            specificity == AlJaziraClassificationSpecificity.MOVEMENT) &&
            !classification.family.isInformational()

    companion object {
        const val AMBIGUOUS_EVIDENCE = "ambiguous_classification"
        const val OUTRANKED_PREFIX = "outranked:"
        const val CANDIDATE_PREFIX = "candidate:"
        const val FAMILY_PREFIX = "family:"
        private const val AMBIGUOUS_CONFIDENCE = 0.3

        val UNRECOGNIZED = AlJaziraClassification(
            family = MessageFamily.UNKNOWN,
            evidence = listOf("unrecognized_aljazira_format"),
            confidence = 0.3,
        )
    }
}
