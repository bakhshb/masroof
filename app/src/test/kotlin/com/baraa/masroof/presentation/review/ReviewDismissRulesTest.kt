package com.baraa.masroof.presentation.review

import com.baraa.masroof.domain.model.MessageFamily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewDismissRulesTest {
    @Test
    fun explicitInformationalReason_isDismissible() {
        assertTrue(
            shouldOfferNonFinancialDismiss(
                messageFamily = MessageFamily.UNKNOWN,
                reasons = listOf("non_financial_or_informational_message"),
            ),
        )
    }

    @Test
    fun parsedInformationalFamily_isDismissibleWithoutBody() {
        assertTrue(
            shouldOfferNonFinancialDismiss(
                messageFamily = MessageFamily.NON_FINANCIAL,
                reasons = listOf("unknown_message_family"),
            ),
        )
    }

    @Test
    fun unknown_isNotDismissedFromStatementWording() {
        assertFalse(
            shouldOfferNonFinancialDismiss(
                messageFamily = MessageFamily.UNKNOWN,
                reasons = listOf("unknown_message_family"),
            ),
        )
    }

    @Test
    fun purchase_isNotDismissible() {
        assertFalse(
            shouldOfferNonFinancialDismiss(
                messageFamily = MessageFamily.PURCHASE,
                reasons = listOf("needs_review"),
            ),
        )
    }
}
