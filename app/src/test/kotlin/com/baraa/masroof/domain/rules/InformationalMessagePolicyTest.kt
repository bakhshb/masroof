package com.baraa.masroof.domain.rules

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InformationalMessagePolicyTest {
    @Test
    fun informationalFamilies_areAutoIgnoredWithoutReadingText() {
        listOf(
            MessageFamily.OTP,
            MessageFamily.NON_FINANCIAL,
            MessageFamily.BALANCE_NOTICE,
        ).forEach { family ->
            assertTrue(family.name, InformationalMessagePolicy.shouldAutoIgnore(family))
        }
    }

    @Test
    fun unknown_staysReviewableEvenWhenBodyLooksInformational() {
        assertFalse(
            InformationalMessagePolicy.shouldAutoIgnore(
                event(
                    family = MessageFamily.UNKNOWN,
                    amount = null,
                ),
            ),
        )
        assertFalse(InformationalMessagePolicy.shouldAutoIgnore(MessageFamily.UNKNOWN))
    }

    @Test
    fun purchase_isNotAutoIgnoredWhenBodyContainsStatementWording() {
        assertFalse(
            InformationalMessagePolicy.shouldAutoIgnore(
                event(
                    family = MessageFamily.PURCHASE,
                    amount = Money.of("89.50", Currency.SAR),
                ),
            ),
        )
    }

    @Test
    fun financialFamilies_areNotAutoIgnored() {
        listOf(
            MessageFamily.PURCHASE,
            MessageFamily.REFUND,
            MessageFamily.TRANSFER_IN,
            MessageFamily.TRANSFER_OUT,
            MessageFamily.FEE,
        ).forEach { family ->
            assertFalse(family.name, InformationalMessagePolicy.shouldAutoIgnore(family))
        }
    }

    private fun event(family: MessageFamily, amount: Money?) = ParsedEvent(
        id = "pe",
        rawSmsId = "sms",
        bank = Bank.BANK_ALJAZIRA,
        messageFamily = family,
        direction = MoneyDirection.OUTGOING,
        amount = amount,
        purchaseChannel = null,
        sourceAccountRef = null,
        destinationAccountRef = null,
        cardRef = null,
        merchant = null,
        counterparty = null,
        occurredAt = null,
        bankNetworkType = null,
        confidence = Confidence(1.0),
        parseStatus = ParseStatus.SUCCESS,
    )
}
