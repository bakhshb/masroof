package com.baraa.masroof.bank.contract

import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput

/**
 * Minimal second-bank adapter used to prove the shared adapter contract is bank-agnostic.
 *
 * Understands exactly two formats: `STUB PURCHASE SAR <amount>` and `STUB OTP <code>`.
 */
class StubBankSmsAdapter : BankSmsAdapter {
    override val bank: Bank = BANK

    override fun detect(sender: String, body: String): BankDetectionResult =
        if (sender.equals(SENDER, ignoreCase = true)) {
            BankDetectionResult.Detected(
                bank = bank,
                confidence = Confidence(score = 1.0),
                evidence = listOf("sender:$sender"),
            )
        } else {
            BankDetectionResult.Unknown(
                reasons = listOf("sender_not_recognized_as_stub_bank"),
            )
        }

    override fun parse(input: SmsParseInput): ParseResult {
        PURCHASE.matchEntire(input.body.trim())?.let { match ->
            return ParseResult.Success(
                event(input, MessageFamily.PURCHASE, ParseStatus.SUCCESS)
                    .copy(
                        direction = MoneyDirection.OUTGOING,
                        amount = Money.of(match.groupValues[1], Currency.SAR),
                        purchaseChannel = PurchaseChannel.POS,
                    ),
            )
        }
        if (OTP.matches(input.body.trim())) {
            return ParseResult.NonFinancial(
                reason = "otp",
                event = event(input, MessageFamily.OTP, ParseStatus.NON_FINANCIAL),
            )
        }
        return ParseResult.Unsupported(reason = "stub_bank_format_not_supported")
    }

    private fun event(input: SmsParseInput, family: MessageFamily, status: ParseStatus) = ParsedEvent(
        id = "evt-${input.rawSmsId}",
        rawSmsId = input.rawSmsId,
        bank = bank,
        messageFamily = family,
        direction = null,
        amount = null,
        purchaseChannel = null,
        sourceAccountRef = null,
        destinationAccountRef = null,
        cardRef = null,
        merchant = null,
        counterparty = null,
        occurredAt = null,
        bankNetworkType = null,
        confidence = Confidence(score = 1.0),
        parseStatus = status,
    )

    companion object {
        const val SENDER = "StubBank"
        val BANK: Bank = Bank("STUB_BANK")
        private val PURCHASE = Regex("STUB PURCHASE SAR (\\d+\\.\\d{2})")
        private val OTP = Regex("STUB OTP \\d{4,6}")

        val contractSamples = BankSmsAdapterContractSamples(
            positiveSenders = listOf(SENDER, "STUBBANK", "stubbank"),
            negativeSenders = listOf("Stub", "StubBank2", "AlJazira", "not-a-bank-sender"),
            financial = listOf(ContractSms("stub_purchase", SENDER, "STUB PURCHASE SAR 12.50")),
            nonFinancial = listOf(ContractSms("stub_otp", SENDER, "STUB OTP 4821")),
            noAutomaticFinancialOutput = listOf(
                ContractSms("stub_unknown", SENDER, "STUB something new happened"),
            ),
        )
    }
}
