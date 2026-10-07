package com.baraa.masroof.bank.aljazira

import com.baraa.masroof.bank.BankRoutingResult
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.parsing.model.AmountCandidate
import com.baraa.masroof.parsing.model.AmountSourceKind
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.ParsedEventDraft
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.parsing.validator.DefaultParsedEventValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Mandatory P4 regression coverage beyond the parameterized fixture corpus.
 */
class AlJaziraParserRegressionTest {
    private val pipeline = AlJaziraParsingPipeline()
    private val validator = DefaultParsedEventValidator()
    private val detector = AlJaziraBankDetector()
    private val registry = BankSmsRegistry(listOf(AlJaziraSmsAdapter()))

    @Test
    fun cardLast4BeforeAmount_doesNotBecomeAmount() {
        val result = parse(
            """
            شراء عبر الانترنت
            بطاقة: 7271
            لدى: Keeta
            بمبلغ: 51.99 SAR
            في: 14:32 03-08-2026
            الرصيد المتاح: SAR 17230.03
            """.trimIndent(),
        )
        val success = result as ParseResult.Success
        assertEquals(Money.of("51.99", Currency.SAR), success.event.amount)
        assertEquals("7271", success.event.cardRef?.last4)
        assertEquals(Money.of("17230.03", Currency.SAR), success.details.availableBalance)
    }

    @Test
    fun cardBalancesAroundAmount_stayDistinct() {
        val result = parse(
            """
            شراء عبر نقاط البيع (Samsung Pay)
            بطاقة ائتمانية: 7271
            لدى: TEST_SHOP_C
            بمبلغ: 178.02 SAR
            في: 09:08 30-07-2026
            الرصيد المتاح: 18346.84 SAR
            إجمالي المبلغ المستحق:802.62 SAR
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(Money.of("178.02", Currency.SAR), result.event.amount)
        assertEquals("7271", result.event.cardRef?.last4)
        assertEquals(Money.of("18346.84", Currency.SAR), result.details.availableBalance)
        assertEquals(Money.of("802.62", Currency.SAR), result.details.outstandingBalance)
    }

    @Test
    fun transferOutInter_d360BeneficiaryBank_realWorldShape() {
        val result = parse(
            """
            عملية حوالة مالية صادرة مقبولة
            خصمت من حساب: 3001
            الى: براء ف. صالح بخ
            مبلغ العملية: 100.00 SAR
            المعرف البديل \الايبان : 2670
            [بنك دال ثلاثمائة وستون]
            في: 2026-08-14 18:41
            رقم المعاملة: 2BTMS11841719460
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.TRANSFER_OUT, result.event.messageFamily)
        assertEquals("3001", result.event.sourceAccountRef?.maskedNumber)
        assertEquals(Bank.BANK_ALJAZIRA, result.event.sourceAccountRef?.bank)
        assertEquals("2670", result.event.destinationAccountRef?.maskedNumber)
        assertEquals(Bank.UNKNOWN, result.event.destinationAccountRef?.bank)
        assertEquals(Money.of("100.00", Currency.SAR), result.event.amount)
        assertEquals("براء ف. صالح بخ", result.event.counterparty)
        assertEquals("2BTMS11841719460", result.details.transactionReference)
        assertEquals(BankNetworkType.INTER_BANK, result.event.bankNetworkType)
    }

    @Test
    fun transferOutInter_sourceAlJazira_destinationUnknown() {
        val result = parse(
            """
            عملية حوالة مالية صادرة مقبولة
            خصمت من حساب: 3002
            الى: TEST_BENEFICIARY
            مبلغ العملية: 13,258.00 SAR
            المعرف البديل \الايبان : 0593
            [البنك العربي الوطني]
            في: 2026-08-01 12:26
            رقم المعاملة: TEST_REFERENCE_1
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals("3002", result.event.sourceAccountRef?.maskedNumber)
        assertEquals(Bank.BANK_ALJAZIRA, result.event.sourceAccountRef?.bank)
        assertEquals("0593", result.event.destinationAccountRef?.maskedNumber)
        assertEquals(Bank.UNKNOWN, result.event.destinationAccountRef?.bank)
        assertEquals(Money.of("13258.00", Currency.SAR), result.event.amount)
        assertEquals("TEST_REFERENCE_1", result.details.transactionReference)
        assertEquals(BankNetworkType.INTER_BANK, result.event.bankNetworkType)
    }

    @Test
    fun transferInInter_externalSourceUnknown_destinationAlJazira() {
        val result = parse(
            """
            حوالة واردة: محلية
            عبر: بنك الرياض
            مبلغ: SAR 100.00
            إلى: 3001
            اسم المرسل: TEST_COMPANY
            رقم حساب المرسل: 8888
            البنك المرسل: بنك الرياض
            في: 2026-08-03 09:12
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(BankNetworkType.INTER_BANK, result.event.bankNetworkType)
        assertEquals("8888", result.event.sourceAccountRef?.maskedNumber)
        assertEquals(Bank.UNKNOWN, result.event.sourceAccountRef?.bank)
        assertEquals("3001", result.event.destinationAccountRef?.maskedNumber)
        assertEquals(Bank.BANK_ALJAZIRA, result.event.destinationAccountRef?.bank)
    }

    @Test
    fun transferInIntra_bothAccountsBankAlJazira() {
        val result = parse(
            """
            حوالة واردة داخلية
            مبلغ: SAR 4,445.67
            إلى: 3003
            اسم المرسل: TEST_PERSON
            رقم حساب المرسل: 3001
            البنك المرسل: بنك الجزيرة
            في: 2026-08-03 10:38
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.TRANSFER_IN, result.event.messageFamily)
        assertEquals(BankNetworkType.INTRA_BANK, result.event.bankNetworkType)
        assertEquals(Bank.BANK_ALJAZIRA, result.event.sourceAccountRef?.bank)
        assertEquals("3001", result.event.sourceAccountRef?.maskedNumber)
        assertEquals(Bank.BANK_ALJAZIRA, result.event.destinationAccountRef?.bank)
        assertEquals("3003", result.event.destinationAccountRef?.maskedNumber)
        assertFalse(result.toString().contains("SELF_TRANSFER"))
    }

    @Test
    fun crossBankTransfer_isInterBank() {
        val result = parse(
            """
            حوالة واردة: محلية
            عبر: بنك الرياض
            مبلغ: SAR 4,445.67
            إلى: 3001
            اسم المرسل: TEST_COMPANY
            البنك المرسل: بنك الرياض
            في: 2026-08-03 09:12
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(BankNetworkType.INTER_BANK, result.event.bankNetworkType)
        assertEquals(Bank.BANK_ALJAZIRA, result.event.destinationAccountRef?.bank)
        assertNull(result.event.sourceAccountRef)
    }

    @Test
    fun nonTransferLocalAccounts_remainBankAlJazira() {
        val purchase = parse(
            """
            شراء من نقاط البيع
            بطاقة مدى: 2210
            لدى: TEST_GROCER
            بمبلغ: 120.00 SAR
            خصمت من حساب: 3001
            في: 11:05 01-08-2026
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(Bank.BANK_ALJAZIRA, purchase.event.sourceAccountRef?.bank)
        assertEquals("3001", purchase.event.sourceAccountRef?.maskedNumber)

        val bill = parse(
            """
            سداد فاتورة
            المفوتر: TEST_BILLER
            بمبلغ: 210.00 SAR
            من حساب: 3001
            في: 2026-08-03 16:40
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(Bank.BANK_ALJAZIRA, bill.event.sourceAccountRef?.bank)
    }

    @Test
    fun internalAtmWithdrawal_hisabRaqam_extractsSourceAccount() {
        val result = parse(
            """
            سحب نقدي داخلي صراف الي
            بطاقة 8219:مدى
            حساب رقم: 3001
            بمبلغ: SAR 2,200.00
            مكان السحب: جــدة - 7225
            في: 2026-08-02 17:41
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.WITHDRAWAL, result.event.messageFamily)
        assertEquals(Bank.BANK_ALJAZIRA, result.event.sourceAccountRef?.bank)
        assertEquals("3001", result.event.sourceAccountRef?.maskedNumber)
        assertEquals("8219", result.event.cardRef?.last4)
        assertEquals(Money.of("2200.00", Currency.SAR), result.event.amount)
    }

    @Test
    fun madaGooglePayPos_parsesWithoutSourceAccount() {
        val result = parse(
            """
            شراء عبر نقاط البيع (Google Pay)
            بطاقة مدى: 8219
            لدى: MALAYSIA FOODS RESTA
            بمبلغ: 127.00 SAR
            في: 13:24 03-08-2026
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.PURCHASE, result.event.messageFamily)
        assertEquals("8219", result.event.cardRef?.last4)
        assertEquals("MALAYSIA FOODS RESTA", result.event.merchant)
        assertEquals(Money.of("127.00", Currency.SAR), result.event.amount)
        assertNull(result.event.sourceAccountRef)
    }

    @Test
    fun creditCardPurchase_isPurchaseNotCardPayment() {
        val result = parse(
            """
            شراء عبر نقاط البيع (Samsung Pay)
            بطاقة ائتمانية: 7271
            لدى: TEST_SHOP_C
            بمبلغ: 178.02 SAR
            في: 09:08 30-07-2026
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.PURCHASE, result.event.messageFamily)
        assertTrue(result.event.messageFamily != MessageFamily.CARD_PAYMENT)
    }

    @Test
    fun creditCardPayment_isCardPaymentNotPurchase() {
        val result = parse(
            """
            سداد بطاقة ائتمانية
            من حساب: 3001
            بطاقة: 7271
            بمبلغ: 802.62 SAR
            في: 2026-08-04 09:00
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.CARD_PAYMENT, result.event.messageFamily)
        assertTrue(result.event.messageFamily != MessageFamily.PURCHASE)
        assertEquals(Bank.BANK_ALJAZIRA, result.event.sourceAccountRef?.bank)
    }

    @Test
    fun creditCardSettlement_tasdidWording_parsesAsCardPayment() {
        val result = parse(
            """
            بطاقة إئتمانية: تسديد
            بطاقة: 7271;إئتمانية
            مبلغ: SAR 15,000.00
            من: 3001
            في: 2026-07-27 07:47
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.CARD_PAYMENT, result.event.messageFamily)
        assertEquals("3001", result.event.sourceAccountRef?.maskedNumber)
        assertEquals("7271", result.event.cardRef?.last4)
        assertEquals(Money.of("15000.00", Currency.SAR), result.event.amount)
    }

    @Test
    fun otp_isNonFinancialWithoutAmount() {
        val result = parse("رمز التحقق الخاص بك هو 482911. لا تشاركه مع أي شخص.")
        assertTrue(result is ParseResult.NonFinancial)
        val nf = result as ParseResult.NonFinancial
        assertEquals(MessageFamily.OTP, nf.event?.messageFamily)
        assertEquals(ParseStatus.NON_FINANCIAL, nf.event?.parseStatus)
        assertNull(nf.event?.amount)
    }

    @Test
    fun localTransferVerificationCode_isNonFinancialWithoutAmount() {
        val result = parse(
            """
            رمز التحقق: 3108
            السبب: تحويل محلي - تطبيق الجوال
            المبلغ: 513
            التاريخ: 14:31 08-08-2026
            """.trimIndent(),
        )
        assertTrue(result is ParseResult.NonFinancial)
        val nf = result as ParseResult.NonFinancial
        assertEquals(MessageFamily.OTP, nf.event?.messageFamily)
        assertEquals(ParseStatus.NON_FINANCIAL, nf.event?.parseStatus)
        assertNull(nf.event?.amount)
    }

    @Test
    fun balanceNotice_extractsBalanceWithoutTransactionAmount() {
        val result = parse(
            """
            إشعار رصيد
            حساب: 3001
            الرصيد المتاح: SAR 17230.03
            في: 2026-08-03 08:00
            """.trimIndent(),
        ) as ParseResult.NonFinancial
        assertEquals(MessageFamily.BALANCE_NOTICE, result.event?.messageFamily)
        assertNull(result.event?.amount)
        assertEquals(Money.of("17230.03", Currency.SAR), result.details.availableBalance)
        assertEquals(Bank.BANK_ALJAZIRA, result.event?.sourceAccountRef?.bank)
    }

    @Test
    fun unknownFormat_requiresReviewWithoutGuessedFamily() {
        val result = parse("تنبيه بنك الجزيرة: حدث تحديث في خدماتك. راجع التطبيق للتفاصيل.")
        assertTrue(result is ParseResult.ReviewRequired)
        val review = result as ParseResult.ReviewRequired
        assertEquals(MessageFamily.UNKNOWN, review.event?.messageFamily)
        assertEquals(ParseStatus.REVIEW_REQUIRED, review.event?.parseStatus)
        assertNull(review.event?.amount)
    }

    @Test
    fun billPayment_keepsBillerSeparateFromMerchant() {
        val result = parse(
            """
            سداد فاتورة
            المفوتر: TEST_BILLER
            بمبلغ: 210.00 SAR
            من حساب: 3001
            في: 2026-08-03 16:40
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.BILL_PAYMENT, result.event.messageFamily)
        assertEquals("TEST_BILLER", result.details.biller)
        assertNull(result.event.merchant)
    }

    @Test
    fun coincidentalAmountEqualToLast4_isNotUniversallyRejected() {
        val money = Money.of("3001.00", Currency.SAR)
        val selected = AmountCandidate(money, "مبلغ", AmountSourceKind.TRANSACTION_AMOUNT)
        val draft = ParsedEventDraft(
            rawSmsId = "coin-1",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.TRANSFER_OUT,
            direction = MoneyDirection.OUTGOING,
            amount = money,
            sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            confidence = Confidence(0.9),
            parseStatus = ParseStatus.SUCCESS,
            amountCandidates = listOf(selected),
            selectedAmount = selected,
        )
        val validation = validator.validate(draft)
        assertTrue(validation.errors.none { it.code == "V-001" })
        assertTrue(validation.errors.none { it.code == "V-002" })
        assertTrue(validation.isAcceptableForAutomaticUse)
    }

    @Test
    fun amountProvenance_valueMismatch_isRejected() {
        val draftAmount = Money.of("100.00", Currency.SAR)
        val selectedValue = Money.of("200.00", Currency.SAR)
        val selected = AmountCandidate(selectedValue, "مبلغ", AmountSourceKind.TRANSACTION_AMOUNT)
        val draft = ParsedEventDraft(
            rawSmsId = "mismatch-1",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.PURCHASE,
            amount = draftAmount,
            merchant = "Shop",
            confidence = Confidence(0.9),
            parseStatus = ParseStatus.SUCCESS,
            amountCandidates = listOf(selected),
            selectedAmount = selected,
        )
        val validation = validator.validate(draft)
        assertTrue(validation.errors.any { it.code == "AMOUNT_VALUE_MISMATCH" })
        assertFalse(validation.isAcceptableForAutomaticUse)
    }

    @Test
    fun sameMoneyFromTwoEvidences_isNotV007() {
        val money = Money.of("100.00", Currency.SAR)
        val a = AmountCandidate(money, "بمبلغ", AmountSourceKind.TRANSACTION_AMOUNT)
        val b = AmountCandidate(money, "مبلغ", AmountSourceKind.TRANSACTION_AMOUNT)
        val draft = ParsedEventDraft(
            rawSmsId = "same-1",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.PURCHASE,
            direction = MoneyDirection.OUTGOING,
            amount = money,
            merchant = "Shop",
            confidence = Confidence(0.9),
            parseStatus = ParseStatus.SUCCESS,
            amountCandidates = listOf(a, b),
            selectedAmount = a,
        )
        val validation = validator.validate(draft)
        assertTrue(validation.errors.none { it.code == "V-007" })
        assertTrue(validation.isAcceptableForAutomaticUse)
    }

    @Test
    fun distinctMoneyValues_triggerV007() {
        val a = AmountCandidate(Money.of("100.00", Currency.SAR), "بمبلغ", AmountSourceKind.TRANSACTION_AMOUNT)
        val b = AmountCandidate(Money.of("200.00", Currency.SAR), "مبلغ", AmountSourceKind.TRANSACTION_AMOUNT)
        val draft = ParsedEventDraft(
            rawSmsId = "ambig-1",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.PURCHASE,
            amount = null,
            merchant = "Shop",
            confidence = Confidence(0.5),
            parseStatus = ParseStatus.REVIEW_REQUIRED,
            amountCandidates = listOf(a, b),
            selectedAmount = null,
        )
        val validation = validator.validate(draft)
        assertTrue(validation.errors.any { it.code == "V-007" })
    }

    @Test
    fun exactAlJaziraSender_isDetected() {
        val result = detector.detect("AlJazira", "body")
        assertTrue(result is BankDetectionResult.Detected)
        assertEquals(Bank.BANK_ALJAZIRA, (result as BankDetectionResult.Detected).bank)
    }

    @Test
    fun transferInInter_newAcceptedFormat_depositedToAccountAndValueLabel() {
        val result = parse(
            """
            عملية حوالة مالية واردة
            أودعت إلى حساب: 3001
            القيمة: 1,000.00 SAR
            من: نجاه ط. بنتن
            [بنك الرياض]
            خصمت من حساب : 9941
            في: 21:21 30-07-2026
            رقم المعاملة: 2BTMS12121410751
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.TRANSFER_IN, result.event.messageFamily)
        assertEquals(Money.of("1000.00", Currency.SAR), result.event.amount)
        assertEquals("3001", result.event.destinationAccountRef?.maskedNumber)
        assertEquals(Bank.BANK_ALJAZIRA, result.event.destinationAccountRef?.bank)
        assertNull(result.event.sourceAccountRef)
        assertEquals("نجاه ط. بنتن", result.event.counterparty)
        assertEquals(BankNetworkType.INTER_BANK, result.event.bankNetworkType)
        assertEquals(ParseStatus.SUCCESS, result.event.parseStatus)
    }

    @Test
    fun activationCode_isNonFinancialWithoutAmount() {
        val result = parse(
            "رمز التفعيل : 3083 لإضافة المستفيد (عبر التطبيق) : (براء فائز بن صالح بخش)",
        )
        assertTrue(result is ParseResult.NonFinancial)
        val nf = result as ParseResult.NonFinancial
        assertEquals(MessageFamily.NON_FINANCIAL, nf.event?.messageFamily)
        assertEquals(ParseStatus.NON_FINANCIAL, nf.event?.parseStatus)
        assertNull(nf.event?.amount)
    }

    @Test
    fun onlinePurchaseUsdAmount_isParsed() {
        val result = parse(
            """
            شراء عبر الانترنت
            بطاقة ائتمانية: 7271
            لدى: CURSOR, AI POWERED IDE
            بمبلغ: USD 23.00
            في: 2026-08-06 20:22
            الدولة: USA
            رسوم العمليات الدولية: 1.99
            سعر الصرف: 3.756957
            الرصيد المتاح: SAR 16958.89
            إجمالي المبلغ المستحق: SAR 2694.32
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.PURCHASE, result.event.messageFamily)
        assertEquals(Money.of("23.00", Currency.USD), result.event.amount)
        assertEquals("CURSOR, AI POWERED IDE", result.event.merchant)
    }

    @Test
    fun englishRefund_midLineAmount_isParsed() {
        val result = parse(
            """
            Credit Card: Refund
            Card: Credit Number: 8332
            From: Tamara
            Amount: 75.65 SAR
            Balance: 17230.68 SAR
            Date: 2026-08-05 17:41
            Due Amount: 2694.32 SAR
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.REFUND, result.event.messageFamily)
        assertEquals(Money.of("75.65", Currency.SAR), result.event.amount)
        assertEquals("Tamara", result.event.merchant)
    }

    @Test
    fun creditCardRefund_hamzaSpellingAndNumberLabel_isParsed() {
        val result = parse(
            """
            بطاقة إئتمانية: إسترداد مبلغ
            بطاقة: Credit
            رقم: 7271
            من: CURSOR, AI POWERED IDE
            مبلغ: 6.51 USD
            رصيد: 11303.00 SAR
            في: 18:23 17-08-2026
            إجمالي المبلغ المستحق:7683.86 SAR
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.REFUND, result.event.messageFamily)
        assertEquals(Money.of("6.51", Currency.USD), result.event.amount)
        assertEquals("7271", result.event.cardRef?.last4)
        assertEquals("CURSOR, AI POWERED IDE", result.event.merchant)
        assertEquals(ParseStatus.SUCCESS, result.event.parseStatus)
    }

    @Test
    fun creditCardStatementNotice_isNonFinancialWithoutAmount() {
        val result = parse(
            """
            بطاقة إئتمانية: إصدار كشف حساب
            بطاقة: 7271 بطاقة إئتمانية
            إجمالي المبلغ المستحق: SAR 0.00
            تاريخ الاستحقاق: 07/09/2026
            """.trimIndent(),
        )
        assertTrue(result is ParseResult.NonFinancial)
        val nf = result as ParseResult.NonFinancial
        assertEquals(MessageFamily.NON_FINANCIAL, nf.event?.messageFamily)
        assertNull(nf.event?.amount)
    }

    @Test
    fun beneficiaryStatusNotice_isNonFinancialWithoutAmount() {
        val result = parse(
            """
            اسم المستفيد : براء ف بن
            الاسم المختصر : حسابي D360
            حالة: غير نشط
            حساب: SA2036036036045864332670
            بنك: D360 بنك
            في : 14:04 2026-07-29
            """.trimIndent(),
        )
        assertTrue(result is ParseResult.NonFinancial)
        val nf = result as ParseResult.NonFinancial
        assertEquals(MessageFamily.NON_FINANCIAL, nf.event?.messageFamily)
        assertNull(nf.event?.amount)
    }

    @Test
    fun mokafatyLoyaltyPoints_isNonFinancialWithoutAmount() {
        val result = parse(
            "إجمالي رصيد نقاطك في برنامج مكافآتي هو 9966.04 نقطة.",
        )
        assertTrue(result is ParseResult.NonFinancial)
        val nf = result as ParseResult.NonFinancial
        assertEquals(MessageFamily.NON_FINANCIAL, nf.event?.messageFamily)
        assertEquals(ParseStatus.NON_FINANCIAL, nf.event?.parseStatus)
        assertNull(nf.event?.amount)
    }

    @Test
    fun englishOnlinePurchaseOtp_isNonFinancialWithoutAmount() {
        val result = parse(
            """
            One Time Password for Online Purchase
            Code: 8811
            For: SAUDI ELECTRICITY COMPANY
            Amount: SAR 438.5
            Date: 2026-08-12 07:49
            """.trimIndent(),
        )
        assertTrue(result is ParseResult.NonFinancial)
        val nf = result as ParseResult.NonFinancial
        assertEquals(MessageFamily.OTP, nf.event?.messageFamily)
        assertEquals(ParseStatus.NON_FINANCIAL, nf.event?.parseStatus)
        assertNull(nf.event?.amount)
    }

    @Test
    fun arabicOneTimePassword_cardReveal_isNonFinancialWithoutAmount() {
        val result = parse(
            """
            كلمة مرور صالحة لمرة واحدة
            رمز التفعيل: 7559
            السبب: إظهار بيانات البطاقة
            التاريخ: 09:55 09-08-2026
            """.trimIndent(),
        )
        assertTrue(result is ParseResult.NonFinancial)
        val nf = result as ParseResult.NonFinancial
        assertEquals(MessageFamily.NON_FINANCIAL, nf.event?.messageFamily)
        assertEquals(ParseStatus.NON_FINANCIAL, nf.event?.parseStatus)
        assertNull(nf.event?.amount)
    }

    @Test
    fun arabicOneTimePassword_onlinePurchaseZeroAmount_isNonFinancialWithoutAmount() {
        val result = parse(
            """
            كلمة مرور صالحة لمرة واحدة للشراء عبر الإنترنت
            كلمة المرور: 4607
            لدى: Hungerstation
            مبلغ: SAR 0.0
            في: 10:01 09-08-2026
            """.trimIndent(),
        )
        assertTrue(result is ParseResult.NonFinancial)
        val nf = result as ParseResult.NonFinancial
        assertEquals(MessageFamily.OTP, nf.event?.messageFamily)
        assertEquals(ParseStatus.NON_FINANCIAL, nf.event?.parseStatus)
        assertNull(nf.event?.amount)
    }

    @Test
    fun saudiElectricityOnlinePurchase_stillParsesAsPurchase() {
        val result = parse(
            """
            شراء عبر الانترنت
            بطاقة ائتمانية: 7271
            بمبلغ :438.50 SAR
            لدى :SAUDI ELECTRICITY COMP
            في :07:49 12-08-2026
            الرصيد المتاح :SAR 13954.24
            إجمالي المبلغ المستحق:3921.11 SAR
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.PURCHASE, result.event.messageFamily)
        assertEquals(Money.of("438.50", Currency.SAR), result.event.amount)
        assertEquals("7271", result.event.cardRef?.last4)
        assertEquals("SAUDI ELECTRICITY COMP", result.event.merchant)
    }

    @Test
    fun nearMissSenders_areNotAlJazira() {
        listOf("JaziraNews", "OtherBank", "MyJaziraService", "jazira").forEach { sender ->
            val detection = detector.detect(sender, "شراء بمبلغ: 10.00 SAR")
            assertTrue("$sender should be Unknown", detection is BankDetectionResult.Unknown)
            val route = registry.route(sender, "شراء عبر الانترنت بمبلغ: 10.00 SAR")
            assertTrue("$sender must not route to AlJazira", route is BankRoutingResult.NotMatched)
        }
    }

    @Test
    fun plausibleNearMiss_isSuspectedNotParsed() {
        val detection = detector.detect("NotAlJazira", "شراء بمبلغ: 10.00 SAR")
        assertTrue(detection is BankDetectionResult.Suspected)
        val route = registry.route("AlJaziraX", "شراء عبر الانترنت بمبلغ: 10.00 SAR")
        assertTrue(route is BankRoutingResult.SuspectedBank)
    }

    @Test
    fun intraTransfer_bareDmyDateTime_extractsOccurredAt() {
        val result = parse(
            """
            حوالة واردة داخلية
            مبلغ: SAR 5,500.00
            إلى: 3002
            اسم المرسل: براء بخش
            رقم حساب المرسل: 3001
            البنك المرسل: بنك الجزيرة
            07:36 27-08-2026
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(java.time.LocalDateTime.parse("2026-08-27T07:36"), result.details.occurredAtLocal)
    }

    @Test
    fun intraTransferOut_bareDmyDateTime_extractsOccurredAt() {
        val result = parse(
            """
            حوالة صادرة الى حسابك الجاري
            من: 3001
            مبلغ: SAR 5,500.00
            إلى: 3002
            07:36 27-08-2026
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(java.time.LocalDateTime.parse("2026-08-27T07:36"), result.details.occurredAtLocal)
    }

    @Test
    fun typographyVariantLabels_doNotDegradeMerchantDisplayText() {
        val result = parse(
            """
            شـراء عبر الإنترنت
            بطاقة: 7271
            لـدي: مطعم الأصيل
            بِمبلغ: 51.99 SAR
            في: 14:32 03-08-2026
            """.trimIndent(),
        ) as ParseResult.Success
        assertEquals(MessageFamily.PURCHASE, result.event.messageFamily)
        assertEquals(Money.of("51.99", Currency.SAR), result.event.amount)
        assertEquals("مطعم الأصيل", result.event.merchant)
    }

    @Test
    fun unrecognizedSender_isNotRoutedToAlJazira() {
        val route = registry.route("OtherBank", "شراء عبر الانترنت بمبلغ: 10.00 SAR")
        assertTrue(route is BankRoutingResult.NotMatched)
        assertEquals(
            "sender_not_recognized_as_bank_aljazira",
            (route as BankRoutingResult.NotMatched).reason,
        )
    }

    private fun parse(body: String): ParseResult =
        pipeline.parse(
            SmsParseInput(
                rawSmsId = "regression",
                sender = "AlJazira",
                body = body,
                receivedAt = Instant.parse("2026-08-10T00:00:00Z"),
            ),
        )
}
