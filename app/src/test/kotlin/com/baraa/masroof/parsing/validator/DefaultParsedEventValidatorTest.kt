package com.baraa.masroof.parsing.validator

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.parsing.finalize.ParseFinalizer
import com.baraa.masroof.parsing.model.AmountCandidate
import com.baraa.masroof.parsing.model.AmountSourceKind
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.model.ParsedEventDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class DefaultParsedEventValidatorTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)
    private val validator = DefaultParsedEventValidator(clock = clock)
    private val finalizer = ParseFinalizer(validator)

    @Test
    fun missingAmountOnPurchase_isV009Error() {
        val result = validator.validate(validPurchase().copy(amount = null, parseStatus = ParseStatus.PARTIAL))
        assertTrue(result.errors.any { it.code == "V-009" })
        assertFalse(result.isAcceptableForAutomaticUse)
    }

    @Test
    fun missingMerchantOnPurchase_isV008WarningOnly() {
        val result = validator.validate(validPurchase().copy(merchant = null, confidence = Confidence(0.8)))
        assertTrue(result.warnings.any { it.code == "V-008" })
        assertTrue(result.errors.none { it.code == "V-008" })
        assertTrue(result.isAcceptableForAutomaticUse)
    }

    @Test
    fun amountNumericallyEqualToLast4_isNotRejected() {
        // Coincidental equality is allowed; V-001/V-002 need extractor provenance (P4).
        val result = validator.validate(
            ParsedEventDraft(
                rawSmsId = "sms-3",
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.TRANSFER_OUT,
                direction = MoneyDirection.OUTGOING,
                amount = Money.of("3001.00", Currency.SAR),
                sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                cardRef = CardReference(Bank.BANK_ALJAZIRA, "3001"),
                confidence = Confidence(0.9),
                parseStatus = ParseStatus.SUCCESS,
            ),
        )
        assertTrue(result.errors.none { it.code == "V-001" })
        assertTrue(result.errors.none { it.code == "V-002" })
        assertTrue(result.isAcceptableForAutomaticUse)
    }

    @Test
    fun validPurchaseDraft_hasNoErrors() {
        val result = validator.validate(validPurchase())
        assertEquals(emptyList<ValidationFinding>(), result.errors)
        assertTrue(result.isAcceptableForAutomaticUse)
    }

    @Test
    fun otpFamily_doesNotRequireAmountDirectionOrConfidence() {
        val result = validator.validate(
            ParsedEventDraft(
                rawSmsId = "sms-6",
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.OTP,
                confidence = Confidence(0.1),
                parseStatus = ParseStatus.NON_FINANCIAL,
            ),
        )
        assertEquals(emptyList<ValidationFinding>(), result.errors)
    }

    @Test
    fun zeroAmount_isV010() {
        val zero = Money.of("0.00", Currency.SAR)
        assertBlocked(validPurchase().withAmount(zero), "V-010")
    }

    @Test
    fun missingOrInconsistentDirection_isV011() {
        assertBlocked(validPurchase().copy(direction = null), "V-011")
        assertBlocked(validPurchase().copy(direction = MoneyDirection.INCOMING), "V-011")
        assertBlocked(
            validPurchase().copy(messageFamily = MessageFamily.REFUND, merchant = "Shop", purchaseChannel = null),
            "V-011",
        )
        assertBlocked(
            validPurchase().copy(messageFamily = MessageFamily.TRANSFER_IN, purchaseChannel = null),
            "V-011",
        )
    }

    @Test
    fun cardPayment_acceptsEitherSideOfTheSettlement() {
        listOf(MoneyDirection.OUTGOING, MoneyDirection.INCOMING).forEach { direction ->
            val draft = validPurchase().copy(
                messageFamily = MessageFamily.CARD_PAYMENT,
                purchaseChannel = null,
                direction = direction,
            )
            assertTrue(direction.name, validator.validate(draft).isAcceptableForAutomaticUse)
        }
    }

    @Test
    fun confidenceBelowPolicy_isV012_andPolicyIsExplicit() {
        val atThreshold = validPurchase().copy(confidence = Confidence(AutomaticUsePolicy.DEFAULT_MIN_FINANCIAL_CONFIDENCE))
        assertTrue(validator.validate(atThreshold).isAcceptableForAutomaticUse)

        assertBlocked(validPurchase().copy(confidence = Confidence(0.79)), "V-012")

        val strict = DefaultParsedEventValidator(AutomaticUsePolicy(minFinancialConfidence = 0.99), clock)
        assertTrue(strict.validate(validPurchase()).blockingCodes.contains("V-012"))
    }

    @Test
    fun malformedSuffixes_areV013AndV014() {
        assertBlocked(validPurchase().copy(cardRef = CardReference(Bank.BANK_ALJAZIRA, "72A1")), "V-013")
        assertBlocked(validPurchase().copy(cardRef = CardReference(Bank.BANK_ALJAZIRA, "727")), "V-013")
        assertBlocked(
            validPurchase().copy(sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "30012")),
            "V-014",
        )
        assertBlocked(
            validPurchase().copy(destinationAccountRef = AccountReference(Bank.UNKNOWN, "٣٠٠١")),
            "V-014",
        )
    }

    @Test
    fun absentSuffixes_areNotShapeErrors() {
        val draft = validPurchase().copy(
            cardRef = CardReference(Bank.BANK_ALJAZIRA, null),
            sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, null),
        )
        assertTrue(validator.validate(draft).isAcceptableForAutomaticUse)
    }

    @Test
    fun implausibleOccurredAtLocal_isV015() {
        assertBlocked(validPurchase().occurredAt(LocalDateTime.of(1999, 12, 31, 23, 59)), "V-015")
        assertBlocked(validPurchase().occurredAt(LocalDateTime.of(2026, 8, 12, 0, 1)), "V-015")
        assertTrue(
            validator.validate(validPurchase().occurredAt(LocalDateTime.of(2026, 8, 11, 23, 0)))
                .isAcceptableForAutomaticUse,
        )
    }

    @Test
    fun conflictingStrongFacts_areV016AndV017() {
        val sameAccount = AccountReference(Bank.BANK_ALJAZIRA, "3001")
        assertBlocked(
            validPurchase().copy(
                messageFamily = MessageFamily.TRANSFER_OUT,
                purchaseChannel = null,
                sourceAccountRef = sameAccount,
                destinationAccountRef = sameAccount,
            ),
            "V-016",
        )
        assertBlocked(
            validPurchase().copy(messageFamily = MessageFamily.WITHDRAWAL, purchaseChannel = PurchaseChannel.POS),
            "V-017",
        )
    }

    @Test
    fun multipleDistinctAmounts_remainV007() {
        val a = AmountCandidate(Money.of("100.00", Currency.SAR), "بمبلغ", AmountSourceKind.TRANSACTION_AMOUNT)
        val b = AmountCandidate(Money.of("200.00", Currency.SAR), "مبلغ", AmountSourceKind.TRANSACTION_AMOUNT)
        assertBlocked(validPurchase().copy(amountCandidates = listOf(a, b), selectedAmount = a), "V-007")
    }

    @Test
    fun finalizer_neverEmitsSuccessWithValidationErrors_andKeepsEventVisible() {
        val blocked = listOf(
            validPurchase().copy(direction = MoneyDirection.INCOMING),
            validPurchase().copy(confidence = Confidence(0.5)),
            validPurchase().withAmount(Money.of("0.00", Currency.SAR)),
            validPurchase().occurredAt(LocalDateTime.of(2030, 1, 1, 0, 0)),
            validPurchase().copy(cardRef = CardReference(Bank.BANK_ALJAZIRA, "12")),
        )
        blocked.forEach { draft ->
            val result = finalizer.finalize(draft, eventId = "evt-1")
            assertTrue("$draft -> $result", result is ParseResult.ReviewRequired)
            result as ParseResult.ReviewRequired
            assertEquals(ParseStatus.REVIEW_REQUIRED, result.event?.parseStatus)
            assertTrue(result.reasons.isNotEmpty())
        }

        val success = finalizer.finalize(validPurchase(), eventId = "evt-2")
        assertTrue(success is ParseResult.Success)
    }

    private fun assertBlocked(draft: ParsedEventDraft, code: String) {
        val result = validator.validate(draft)
        assertTrue("expected $code in ${result.findings}", result.blockingCodes.contains(code))
        assertFalse(result.isAcceptableForAutomaticUse)
    }

    private fun validPurchase(): ParsedEventDraft {
        val amount = Money.of("51.99", Currency.SAR)
        val selected = AmountCandidate(amount, "بمبلغ", AmountSourceKind.TRANSACTION_AMOUNT)
        return ParsedEventDraft(
            rawSmsId = "sms-5",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.PURCHASE,
            direction = MoneyDirection.OUTGOING,
            amount = amount,
            purchaseChannel = PurchaseChannel.ONLINE,
            cardRef = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            merchant = "Keeta",
            confidence = Confidence(0.95),
            parseStatus = ParseStatus.SUCCESS,
            details = ParsedEventDetails(occurredAtLocal = LocalDateTime.of(2026, 8, 3, 14, 32)),
            amountCandidates = listOf(selected),
            selectedAmount = selected,
        )
    }

    private fun ParsedEventDraft.withAmount(money: Money): ParsedEventDraft {
        val selected = AmountCandidate(money, "بمبلغ", AmountSourceKind.TRANSACTION_AMOUNT)
        return copy(amount = money, amountCandidates = listOf(selected), selectedAmount = selected)
    }

    private fun ParsedEventDraft.occurredAt(local: LocalDateTime): ParsedEventDraft =
        copy(details = details.copy(occurredAtLocal = local))
}
