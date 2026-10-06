package com.baraa.masroof.bank.aljazira.classification

import com.baraa.masroof.bank.aljazira.classification.AlJaziraClassificationSpecificity.GENERIC
import com.baraa.masroof.bank.aljazira.classification.AlJaziraClassificationSpecificity.MOVEMENT
import com.baraa.masroof.bank.aljazira.classification.AlJaziraClassificationSpecificity.PRODUCT
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.PurchaseChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class AlJaziraClassificationResolverTest {

    @Test
    fun noMatchingRule_isUnrecognizedUnknown() {
        val result = AlJaziraClassificationResolver(listOf(rule("fee", MOVEMENT, MessageFamily.FEE))).resolve("hello")

        assertEquals(AlJaziraClassificationResolver.UNRECOGNIZED, result)
    }

    @Test
    fun higherTier_winsAndRecordsOutrankedFamilies() {
        val rules = listOf(
            rule("fee_line", GENERIC, MessageFamily.FEE),
            rule("transfer_out", MOVEMENT, MessageFamily.TRANSFER_OUT),
            rule("refund", PRODUCT, MessageFamily.REFUND),
        )

        val result = AlJaziraClassificationResolver(rules).resolve(MATCH_ALL)

        assertEquals(MessageFamily.REFUND, result.family)
        assertEquals(listOf("refund", "outranked:fee_line", "outranked:transfer_out"), result.evidence)
    }

    @Test
    fun differentFamiliesInTopTier_areAmbiguousUnknown() {
        val rules = listOf(
            rule("transfer_out", MOVEMENT, MessageFamily.TRANSFER_OUT),
            rule("fee", MOVEMENT, MessageFamily.FEE),
            rule("fee_line", GENERIC, MessageFamily.FEE),
        )

        val result = AlJaziraClassificationResolver(rules).resolve(MATCH_ALL)

        assertEquals(MessageFamily.UNKNOWN, result.family)
        assertEquals(null, result.direction)
        assertEquals(
            listOf("ambiguous_classification", "candidate:fee", "candidate:transfer_out"),
            result.evidence,
        )
        assertEquals(0.3, result.confidence, 0.0)
    }

    @Test
    fun sameFamilyInTopTier_usesRankThenRuleId_withoutAmbiguity() {
        val pos = rule("purchase_pos", MOVEMENT, MessageFamily.PURCHASE, rank = 1, channel = PurchaseChannel.POS)
        val online = rule("purchase_online", MOVEMENT, MessageFamily.PURCHASE, channel = PurchaseChannel.ONLINE)
        val b = rule("b_rule", PRODUCT, MessageFamily.REFUND)
        val a = rule("a_rule", PRODUCT, MessageFamily.REFUND)

        assertEquals(
            PurchaseChannel.POS,
            AlJaziraClassificationResolver(listOf(online, pos)).resolve(MATCH_ALL).purchaseChannel,
        )
        assertEquals(listOf("a_rule"), AlJaziraClassificationResolver(listOf(b, a)).resolve(MATCH_ALL).evidence)
    }

    @Test
    fun ruleRegistrationOrder_neverChangesResult() {
        val rules = listOf(
            rule("fee_line", GENERIC, MessageFamily.FEE),
            rule("transfer_out", MOVEMENT, MessageFamily.TRANSFER_OUT),
            rule("purchase_pos", MOVEMENT, MessageFamily.PURCHASE, rank = 1),
            rule("purchase_online", MOVEMENT, MessageFamily.PURCHASE),
            rule("refund", PRODUCT, MessageFamily.REFUND) { "refund" in it },
        )
        val texts = listOf(MATCH_ALL, "refund", "nothing")

        val baseline = texts.map { AlJaziraClassificationResolver(rules).resolve(it) }
        for (permutation in rules.permutations()) {
            assertEquals(baseline, texts.map { AlJaziraClassificationResolver(permutation).resolve(it) })
        }
    }

    private fun rule(
        id: String,
        specificity: Int,
        family: MessageFamily,
        rank: Int = 0,
        channel: PurchaseChannel? = null,
        matches: (String) -> Boolean = { it == MATCH_ALL },
    ) = AlJaziraClassificationRule.of(
        id = id,
        specificity = specificity,
        rank = rank,
        matches = matches,
        classify = { AlJaziraClassification(family = family, purchaseChannel = channel, evidence = listOf(id)) },
    )

    private fun <T> List<T>.permutations(): List<List<T>> =
        if (size <= 1) {
            listOf(this)
        } else {
            flatMap { head -> (this - head).permutations().map { listOf(head) + it } }
        }

    private companion object {
        const val MATCH_ALL = "match-all"
    }
}
