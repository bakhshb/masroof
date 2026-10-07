package com.baraa.masroof.bank.aljazira.classification

import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.parsing.fixtures.AlJaziraFixtureLoader
import com.baraa.masroof.parsing.normalizer.MessageNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AlJaziraMessageClassifierTest {
    private val normalizer = MessageNormalizer()
    private val classifier = AlJaziraMessageClassifier()
    private val fixtures by lazy { AlJaziraFixtureLoader.loadAllFromClasspath() }

    @Test
    fun everyFixture_classifiesToItsExpectedFamily() {
        fixtures.forEach { fixture ->
            assertEquals(
                fixture.id,
                MessageFamily.valueOf(fixture.expected.messageFamily),
                classify(fixture.body).family,
            )
        }
    }

    @Test
    fun productionRuleOrder_neverChangesClassification() {
        val texts = fixtures.map { normalizer.normalize(it.body).comparisonBody }
        val baseline = texts.map(AlJaziraClassificationResolver(AlJaziraMessageClassifier.RULES)::resolve)
        val orders = listOf(AlJaziraMessageClassifier.RULES.reversed()) +
            (1..SHUFFLES).map { seed -> AlJaziraMessageClassifier.RULES.shuffled(Random(seed)) }

        orders.forEach { order ->
            assertEquals(baseline, texts.map(AlJaziraClassificationResolver(order)::resolve))
        }
    }

    @Test
    fun transferWithFeeLine_explainsThatTransferOutrankedFee() {
        val result = classify(fixture("collision_transfer_fee_line_ar_001"))

        assertEquals(MessageFamily.TRANSFER_OUT, result.family)
        assertEquals(listOf("transfer_out", "outranked:fee_line"), result.evidence)
    }

    @Test
    fun refundWithPurchaseWording_explainsThatRefundOutrankedPurchase() {
        val result = classify(fixture("collision_refund_pos_purchase_ar_001"))

        assertEquals(MessageFamily.REFUND, result.family)
        assertEquals(listOf("refund", "outranked:purchase_pos"), result.evidence)
    }

    @Test
    fun otpWithPurchaseWording_staysOtp() {
        listOf("collision_otp_online_purchase_en_001", "collision_otp_online_purchase_ar_001").forEach { id ->
            val result = classify(fixture(id))
            assertEquals(id, MessageFamily.OTP, result.family)
            assertTrue(id, result.evidence.none { it.startsWith(AlJaziraClassificationResolver.CANDIDATE_PREFIX) })
        }
    }

    @Test
    fun purchaseWithStatementDueWording_isReviewAndListsBothFamilies() {
        val result = classify(fixture("collision_purchase_statement_due_ar_001"))

        assertEquals(MessageFamily.UNKNOWN, result.family)
        assertTrue(result.evidence.contains("candidate:purchase_pos"))
        assertTrue(result.evidence.contains("candidate:statement_notice"))
        assertTrue(result.evidence.contains("family:PURCHASE"))
        assertTrue(result.evidence.contains("family:NON_FINANCIAL"))
    }

    @Test
    fun transferWithBeneficiaryNotice_isReviewAndListsBothFamilies() {
        val result = classify(fixture("collision_transfer_beneficiary_notice_ar_001"))

        assertEquals(MessageFamily.UNKNOWN, result.family)
        assertTrue(result.evidence.contains("candidate:transfer_out"))
        assertTrue(result.evidence.contains("candidate:beneficiary_notice"))
        assertTrue(result.evidence.contains("family:TRANSFER_OUT"))
        assertTrue(result.evidence.contains("family:NON_FINANCIAL"))
    }

    @Test
    fun standaloneStatementAndBeneficiary_stayNonFinancial() {
        assertEquals(MessageFamily.NON_FINANCIAL, classify(fixture("statement_ar_001")).family)
        assertEquals(MessageFamily.NON_FINANCIAL, classify(fixture("beneficiary_notice_ar_001")).family)
        assertEquals(MessageFamily.NON_FINANCIAL, classify(fixture("collision_statement_amount_ar_001")).family)
    }

    @Test
    fun competingMovementTitles_areAmbiguousAndListCandidates() {
        assertEquals(
            listOf("ambiguous_classification", "candidate:fee", "candidate:transfer_out"),
            classify(fixture("collision_titled_fee_transfer_ar_001")).evidence,
        )
        assertEquals(
            listOf("ambiguous_classification", "candidate:transfer_in", "candidate:transfer_out"),
            classify(fixture("collision_transfer_in_and_out_ar_001")).evidence,
        )
    }

    @Test
    fun feeKeywordStrength_dependsOnTitleLine() {
        val titled = classify("رسوم خدمة\nخصمت من حساب: 3001\nبمبلغ: 5.00 SAR")
        val bodyOnly = classify("خصمت من حساب: 3001\nرسوم: 5.00 SAR")

        assertEquals(MessageFamily.FEE, titled.family)
        assertEquals(listOf("fee"), titled.evidence)
        assertEquals(MessageFamily.FEE, bodyOnly.family)
    }

    private fun fixture(id: String): String = fixtures.first { it.id == id }.body

    private fun classify(body: String): AlJaziraClassification = classifier.classify(normalizer.normalize(body))

    private companion object {
        const val SHUFFLES = 50
    }
}
