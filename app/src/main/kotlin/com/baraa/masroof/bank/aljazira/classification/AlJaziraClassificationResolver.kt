package com.baraa.masroof.bank.aljazira.classification

import com.baraa.masroof.domain.model.MessageFamily

/**
 * Deterministic evidence resolution:
 * 1. collect every matching rule's candidate,
 * 2. keep the highest specificity tier,
 * 3. one family left → it wins (evidence lists the families it outranked),
 * 4. several families left → UNKNOWN (review), never an arbitrary pick.
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

    companion object {
        const val AMBIGUOUS_EVIDENCE = "ambiguous_classification"
        const val OUTRANKED_PREFIX = "outranked:"
        const val CANDIDATE_PREFIX = "candidate:"
        private const val AMBIGUOUS_CONFIDENCE = 0.3

        val UNRECOGNIZED = AlJaziraClassification(
            family = MessageFamily.UNKNOWN,
            evidence = listOf("unrecognized_aljazira_format"),
            confidence = 0.3,
        )
    }
}
