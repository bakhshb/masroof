package com.baraa.masroof.lab.finance.projection

import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PersistedSarProjectionTest {
    private val account = asset("account", AssetKind.ACCOUNT)
    private val facility = asset("facility", AssetKind.CREDIT_FACILITY)
    private val primary = asset("primary", AssetKind.CREDIT_CARD, facility, CreditCardRole.PRIMARY)
    private val supplementary = asset("supplementary", AssetKind.CREDIT_CARD, facility, CreditCardRole.SUPPLEMENTARY)
    private val loan = asset("loan", AssetKind.LOAN)
    private val time = Instant.parse("2026-08-01T10:00:00Z")
    private val period = DisplayPeriod(time, time.plusSeconds(60), ZoneId.of("Asia/Riyadh"), PeriodKind.SELECTED, null)
    private fun asset(id: String, kind: AssetKind, parent: OwnedAsset? = null, role: CreditCardRole? = null) =
        OwnedAsset(AssetId(Environment.BETA, BankId("ALJAZIRA"), id), kind, null, parent?.id, role)
    private fun input(id: String = "purchase", type: FinancialType = FinancialType.EXPENSE,
        currency: Currency = Currency.USD, amount: String = "23", source: OwnedAsset? = account,
        destination: OwnedAsset? = null, fx: AppliedFx? = AppliedFx(BigDecimal("3.756957"), "SMS")) =
        FinancialClassificationInput(PostingKey(Environment.BETA, id), PostingRevision(0, 1), type,
            Money.of(amount, currency), TransactionTime(time, period.zone),
            source?.let { FinancialEndpoint.Owned(it.id) } ?: FinancialEndpoint.External,
            destination?.let { FinancialEndpoint.Owned(it.id) } ?: FinancialEndpoint.External,
            listOf(account, facility, primary, supplementary, loan).associateBy { it.id }, setOf("e-$id"), true, appliedFx = fx)
    private fun fee(input: FinancialClassificationInput, amount: String = "1.99", currency: Currency = Currency.SAR) =
        LinkedInternationalFee(input.key, input.revision, input.evidenceIds.single(), Money.of(amount, currency))
    private fun available(input: FinancialClassificationInput, fee: LinkedInternationalFee? = null) =
        PersistedSarProjection.project(input, fee) as SarRowProjection.Available
    private fun unavailable(input: FinancialClassificationInput, fee: LinkedInternationalFee? = null) =
        (PersistedSarProjection.project(input, fee) as SarRowProjection.Unavailable).reason
    private fun decimal(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test fun fullPrecisionRoundThenFeeAndNativeFactsUnchanged() {
        val input = input()
        val result = available(input, fee(input))
        decimal("88.40", result.sarAmount.amount)
        decimal("86.41", available(input).sarAmount.amount)
        assertEquals(input, result.native.input)
        assertEquals(Currency.USD, input.amount.currency)
        assertEquals("3.756957", input.appliedFx!!.rateToSar.toPlainString())
        assertEquals(Currency.SAR, result.accountMovements.single().amount.currency)
        decimal("88.40", result.metrics.getValue(SummaryMetric.ACTUAL_ACCOUNT_SPENDING).amount)
    }

    @Test fun halfEvenUsesConvertedMagnitudeBeforeFeeAndHandlesZero() {
        decimal("1.00", available(input(amount = "1", fx = AppliedFx(BigDecimal("1.005"), "SMS"))).sarAmount.amount)
        decimal("1.02", available(input(amount = "1", fx = AppliedFx(BigDecimal("1.015"), "SMS"))).sarAmount.amount)
        decimal("0.00", available(input(amount = "0")).sarAmount.amount)
    }

    @Test fun allActualPersistedSourcesUseStoredRateAndOnlySmsUsesFee() {
        listOf("SMS" to "88.40", "HISTORICAL_MERCHANT" to "86.41", "MARKET" to "86.41").forEach { (source, expected) ->
            val row = input(fx = AppliedFx(BigDecimal("3.756957"), source))
            decimal(expected, available(row, fee(row)).sarAmount.amount)
        }
    }

    @Test fun refundsExcludeFeeAndRemainSignedInNetCreditSpending() {
        val row = input(type = FinancialType.REFUND, source = null, destination = supplementary, amount = "6.51")
        val result = available(row, fee(row, "500"))
        decimal("24.46", result.sarAmount.amount)
        decimal("-24.46", result.metrics.getValue(SummaryMetric.CREDIT_PURCHASES).amount)
        decimal("-24.46", result.metrics.getValue(SummaryMetric.SPENDING_NET).amount)
        assertEquals(facility.id, result.creditFacility)
        assertTrue(result.accountMovements.isEmpty())
    }

    @Test fun nativeSarNeverMultipliesAppliedFxOrAddsForeignFee() {
        val row = input(currency = Currency.SAR)
        decimal("23", available(row, fee(row, "20", Currency.USD)).sarAmount.amount)
        assertEquals(Currency.SAR, available(row).native.input.amount.currency)
    }

    @Test fun missingOrUnknownFxRemainsUnavailableNotZero() {
        assertEquals(SarProjectionBlocker.MISSING_APPLIED_FX, unavailable(input(fx = null)))
        assertEquals(SarProjectionBlocker.UNKNOWN_FX_SOURCE, unavailable(input(fx = AppliedFx(BigDecimal.ONE, "guessed"))))
    }

    @Test fun feeMustBeSamePostingRevisionAndLinkedEvidenceAndSar() {
        val row = input()
        assertEquals(SarProjectionBlocker.INVALID_FEE_LINK, unavailable(row, fee(input(id = "other"))))
        assertEquals(SarProjectionBlocker.INVALID_FEE_LINK, unavailable(row, fee(row).copy(revision = PostingRevision(0, 2))))
        assertEquals(SarProjectionBlocker.INVALID_FEE_LINK, unavailable(row, fee(row).copy(evidenceId = "elsewhere")))
        assertEquals(SarProjectionBlocker.NON_SAR_FEE, unavailable(row, fee(row, currency = Currency.USD)))
    }

    @Test fun invalidFinancialGateCannotBeLiftedByFxFacts() {
        assertEquals(SarProjectionBlocker.FINANCIAL_REVIEW, unavailable(input().copy(validatedParse = false)))
        assertEquals(SarProjectionBlocker.FINANCIAL_REVIEW, unavailable(input(type = FinancialType.UNKNOWN)))
        assertEquals(SarProjectionBlocker.FINANCIAL_REVIEW, unavailable(input().copy(unresolved = setOf(ReviewReason.OWNERSHIP))))
    }

    @Test fun periodUsesStoredInstantHalfOpenAndUnresolvedOutsidePeriodDoesNotPoisonTotals() {
        val rows = listOf(input(id = "start"), input(id = "last").copy(time = TransactionTime(period.until.minusNanos(1), period.zone)),
            input(id = "end", fx = null).copy(time = TransactionTime(period.until, period.zone)),
            input(id = "before", fx = null).copy(time = TransactionTime(period.from.minusNanos(1), period.zone)))
        val result = SarPeriodProjector.project(Environment.BETA, period, rows)
        assertEquals(2, result.includedCount)
        decimal("172.82", result.totals!!.getValue(SummaryMetric.SPENDING_GROSS).amount)
        assertTrue(result.unavailable.isEmpty())
    }

    @Test fun missingOneInPeriodRowMakesAllCompleteTotalsUnavailable() {
        val result = SarPeriodProjector.project(Environment.BETA, period, listOf(input(), input(id = "missing", fx = null)))
        assertEquals(1, result.includedCount)
        assertEquals(mapOf(input(id = "missing").key to SarProjectionBlocker.MISSING_APPLIED_FX), result.unavailable)
        assertNull(result.totals); assertNull(result.facilitySpending); assertNull(result.loanPayments)
    }

    @Test fun primaryAndSupplementaryPurchasesRefundAndSettlementDoNotDoubleCount() {
        val rows = listOf(input(id = "primary", source = primary, amount = "10"),
            input(id = "supplementary", source = supplementary, amount = "20"),
            input(id = "refund", type = FinancialType.REFUND, source = null, destination = supplementary, amount = "40"),
            input(id = "settlement", type = FinancialType.CREDIT_CARD_PAYMENT, destination = facility, amount = "100"))
        val result = SarPeriodProjector.project(Environment.BETA, period, rows)
        // Per-row rounding:37.57+75.14-150.28, while settlement375.70 is a separate account outflow.
        decimal("-37.57", result.facilitySpending!!.getValue(facility.id).amount)
        decimal("-37.57", result.totals!!.getValue(SummaryMetric.CREDIT_PURCHASES).amount)
        decimal("375.70", result.totals.getValue(SummaryMetric.CREDIT_SETTLEMENTS).amount)
        decimal("375.70", result.totals.getValue(SummaryMetric.ACTUAL_ACCOUNT_SPENDING).amount)
    }

    @Test fun loanRepaymentAndFinancingFeeAreLoanPaymentNotSpendingOrCardSettlement() {
        val row = input(type = FinancialType.LOAN_REPAYMENT, destination = loan, amount = "100")
        val attributed = input(id = "financing", type = FinancialType.FEE, amount = "100").copy(financingLoan = loan.id)
        val result = SarPeriodProjector.project(Environment.BETA, period, listOf(row, attributed))
        decimal("751.40", result.loanPayments!!.getValue(loan.id).amount)
        decimal("0", result.totals!!.getValue(SummaryMetric.SPENDING_GROSS).amount)
        decimal("0", result.totals.getValue(SummaryMetric.CREDIT_SETTLEMENTS).amount)
        decimal("751.40", result.totals.getValue(SummaryMetric.ACTUAL_ACCOUNT_SPENDING).amount)
    }

    @Test fun emptyPeriodIsKnownZeroNotUnavailable() {
        val result = SarPeriodProjector.project(Environment.BETA, period, emptyList())
        assertEquals(0, result.includedCount)
        assertTrue(result.totals!!.values.all { it.currency == Currency.SAR && it.amount.signum() == 0 })
        assertTrue(result.facilitySpending!!.isEmpty()); assertTrue(result.loanPayments!!.isEmpty())
    }

    @Test fun duplicateStableKeysCannotCountTwoRevisionsOrApplyFeeTwice() {
        assertThrows(IllegalArgumentException::class.java) {
            SarPeriodProjector.project(Environment.BETA, period, listOf(input(), input().copy(revision = PostingRevision(1, 1)))) }
        assertThrows(IllegalArgumentException::class.java) {
            SarPeriodProjector.project(Environment.BETA, period, listOf(input()), listOf(fee(input()), fee(input()))) }
    }

    @Test fun mixedEnvironmentsAndForeignFeeKeysAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SarPeriodProjector.project(Environment.MIGRATION_REHEARSAL, period, listOf(input())) }
        assertThrows(IllegalArgumentException::class.java) {
            SarPeriodProjector.project(Environment.BETA, period, listOf(input()), listOf(fee(input(id = "unknown")))) }
        assertThrows(IllegalArgumentException::class.java) {
            SarPeriodProjector.project(Environment.BETA, period, listOf(input(), input(id = "duplicate").copy(evidenceIds = input().evidenceIds))) }
    }

    @Test fun returnedFinancialMapsCannotBeMutated() {
        val mutableAssets = input().assets.toMutableMap()
        val mutableEvidence = input().evidenceIds.toMutableSet()
        val mutableReasons = mutableSetOf<ReviewReason>()
        val callerInput = input().copy(assets = mutableAssets, evidenceIds = mutableEvidence, unresolved = mutableReasons)
        val inputs = mutableListOf(callerInput)
        val row = available(callerInput)
        val period = SarPeriodProjector.project(Environment.BETA, period, inputs)
        mutableAssets.clear(); mutableEvidence.clear(); mutableReasons += ReviewReason.OWNERSHIP; inputs.clear()
        assertEquals(5, row.native.input.assets.size)
        assertEquals(setOf("e-purchase"), row.native.input.evidenceIds)
        assertTrue(row.native.input.unresolved.isEmpty())
        assertEquals(1, period.includedCount)
        assertThrows(UnsupportedOperationException::class.java) {
            (row.metrics as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) {
            (row.accountMovements as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) {
            (row.native.metrics as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) {
            (row.native.accountMovements as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) {
            (row.native.input.assets as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) {
            (row.native.input.evidenceIds as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) {
            (row.native.input.unresolved as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) {
            (period.totals as MutableMap).clear() }
    }
}
