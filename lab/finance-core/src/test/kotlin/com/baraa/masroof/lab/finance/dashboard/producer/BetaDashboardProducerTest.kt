package com.baraa.masroof.lab.finance.dashboard.producer

import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.matching.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.projection.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BetaDashboardProducerTest {
    private val bank = BankId("BANK_ALJAZIRA")
    private val query = DashboardQuery(Environment.BETA, Instant.parse("2026-10-01T00:00:00Z"),
        Instant.parse("2026-11-01T00:00:00Z"), ZoneId.of("Asia/Riyadh"), Currency.SAR)
    private fun asset(name: String, kind: AssetKind = AssetKind.ACCOUNT, parent: OwnedAsset? = null,
        role: CreditCardRole? = null) = OwnedAsset(AssetId(Environment.BETA, bank, name), kind, "1234", parent?.id, role)
    private val account = asset("account")
    private val other = asset("other")
    private val facility = asset("facility", AssetKind.CREDIT_FACILITY)
    private val credit = asset("credit", AssetKind.CREDIT_CARD, facility, CreditCardRole.PRIMARY)
    private val supplement = asset("supplement", AssetKind.CREDIT_CARD, facility, CreditCardRole.SUPPLEMENTARY)
    private val debit = asset("debit", AssetKind.DEBIT_CARD, account)
    private val loan = asset("loan", AssetKind.LOAN)
    private fun money(value: String, currency: Currency = Currency.SAR) = Money.of(value, currency)
    private fun signed(value: String, currency: Currency = Currency.SAR) = SignedAmount(BigDecimal(value), currency)
    private fun period(kind: PeriodKind, from: Instant = query.from, until: Instant = query.until) = DisplayPeriod(from, until, query.zone, kind, null)
    private fun input(name: String = "purchase", type: FinancialType = FinancialType.EXPENSE, amount: String = "10",
        source: OwnedAsset? = account, destination: OwnedAsset? = null, registry: List<OwnedAsset> = listOf(account),
        currency: Currency = Currency.SAR) = FinancialClassificationInput(PostingKey(Environment.BETA, name), PostingRevision(0, 1), type,
        money(amount, currency), TransactionTime(query.from.plusSeconds(86400), query.zone),
        source?.let { FinancialEndpoint.Owned(it.id) } ?: FinancialEndpoint.External,
        destination?.let { FinancialEndpoint.Owned(it.id) } ?: FinancialEndpoint.External,
        registry.associateBy { it.id }, setOf("evidence-$name"), true)
    private fun posting(input: FinancialClassificationInput = input()) = (FinancialPostingRules.prepare(input) as PostingPreparation.Ready).posting
    private fun batch(rows: List<StoredFinancialPosting> = listOf(posting()), assets: List<OwnedAsset> = listOf(account)) =
        DashboardReadBatch(query, ReadRevision("atomic-beta-revision-42", query.until), rows.map { DashboardLedgerRow(it, true) }, assets,
            DashboardCoverage(true, true, true, 0))
    private fun build(batch: DashboardReadBatch = batch()) = BetaDashboardBuilder.build(batch)
    private fun metric(result: DashboardProduction, metric: SummaryMetric) = result.financialSummary!!.metrics.getValue(metric)?.amount
    private fun equal(value: String, actual: BigDecimal?) { assertNotNull(actual); assertEquals(0, BigDecimal(value).compareTo(actual)) }

    @Test fun readerLoadsOneBoundedBatchAndKeepsExactRevision() = runTest {
        var calls = 0
        val source = DashboardReadSource { request -> calls++; assertEquals(query, request); batch() }
        val producer = BetaDashboardProducer(source)
        val result = producer.read(query)
        assertEquals(1, calls)
        assertEquals(ReadRevision("atomic-beta-revision-42", query.until), result.revision)
        assertNotNull(result.analysis)
        assertNull(result.commitments)
        assertEquals(1, result.transactions.size)
        equal("10", result.fleetFlow!!.externalOut.amount)
    }

    @Test fun analysisIsComputedWhileMissingMerchantAndCommitmentCoverageStayUnavailable() {
        val result = build()
        assertNotNull(result.snapshot.analysis)
        assertFalse(DashboardSection.ANALYSIS in result.unavailableSections)
        assertTrue(DashboardSection.TRENDS in result.unavailableSections) // PURCHASES lacks typed family proof.
        assertNotNull(result.snapshot.analysis!!.trends.single { it.scope == TrendScope.OVERALL }.averageDailySpending)
        assertTrue(result.unavailableSections.containsAll(setOf(DashboardSection.MERCHANT_TOTALS, DashboardSection.COMMITMENTS, DashboardSection.RUNTIME_SOURCE)))
        equal("10", metric(result, SummaryMetric.SPENDING_GROSS))
        equal("-10", metric(result, SummaryMetric.NET_CASH_FLOW))
        assertEquals(SummaryMetric.entries.toSet(), result.financialSummary!!.metrics.keys)
    }

    @Test fun missingObservationsAreNotBalancesDuesOrLoanZeros() {
        val assets = listOf(account, facility, credit, loan, debit)
        val result = build(batch(emptyList(), assets)).snapshot
        assertNull(result.accounts.single().balance)
        assertNull(result.creditFacilities.single().statementDue)
        assertNull(result.creditFacilities.single().cards.single().availableBalance)
        assertNull(result.loans.single().remaining)
        assertNull(result.loans.single().latestInstallment)
        assertNull(result.totals!!.facilityDue)
        assertNull(result.totals.loanRemaining)
        assertNull(result.debitCards.single().spending)
        assertNull(result.loans.single().salaryPayment)
        assertNull(result.creditFacilities.single().salarySpending)
    }

    @Test fun completeEmptyHistoryHasRealFinancialZerosButNoInventedObservations() {
        val result = build(batch(emptyList()))
        assertTrue(result.financialSummary!!.metrics.values.all { it != null && it.amount.signum() == 0 })
        assertEquals(0, result.financialSummary.transactionCount)
        assertEquals(0, result.snapshot.fleetFlow!!.cashPosition.amount.signum())
        assertNull(result.snapshot.accounts.single().balance)
        assertEquals(0, result.snapshot.totals!!.facilityDue!!.amount.signum()) // Complete registry has no facilities.
    }

    @Test fun unknownLedgerRegistryOrReviewCoverageNeverBecomesAnEmptyCompleteSummary() = runTest {
        for (coverage in listOf(DashboardCoverage(false, true, true, 0), DashboardCoverage(true, false, true, 0),
            DashboardCoverage(true, true, true, null))) {
            val source = batch(emptyList()).copy(coverage = coverage)
            val result = build(source)
            assertNull(result.financialSummary)
            assertNull(result.snapshot.analysis)
            if (!coverage.completeLedger || !coverage.completeRegistry) {
                assertNull(result.snapshot.fleetFlow)
                assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { BetaDashboardProducer { source }.read(query) } }
            }
        }
    }

    @Test fun inactiveRowsAndTheirEvidenceNeverContributeFinancially() {
        val active = posting()
        val inactive = posting(input("inactive", amount = "500").copy(evidenceIds = active.command.evidenceIds))
        val result = build(batch().copy(rows = listOf(DashboardLedgerRow(inactive, false), DashboardLedgerRow(active, true))))
        equal("10", metric(result, SummaryMetric.SPENDING_GROSS))
        assertEquals(listOf(active.command.key), result.snapshot.transactions.map { it.key })
        assertEquals(1, result.financialSummary!!.transactionCount)
        val stolen = LinkedInternationalFee(inactive.command.key, inactive.command.revision, inactive.command.evidenceIds.single(), money("1"))
        assertThrows(IllegalArgumentException::class.java) { build(batch().copy(rows = listOf(DashboardLedgerRow(inactive, false)), fees = listOf(stolen))) }
    }

    @Test fun revisionPayloadAndPostingGateAreRecheckedInsteadOfTrustingClassifiedValues() {
        val valid = posting()
        val noGate = valid.copy(classification = valid.classification.copy(input = valid.classification.input.copy(validatedParse = false)))
        val falseMetric = valid.copy(classification = valid.classification.copy(metrics = valid.classification.metrics + (SummaryMetric.SPENDING_GROSS to signed("999"))))
        val wrongCommand = valid.copy(command = valid.command.copy(revision = PostingRevision(0, 2)))
        for (invalid in listOf(noGate, falseMetric, wrongCommand)) {
            assertThrows(IllegalArgumentException::class.java) { build(batch(listOf(invalid))) }
        }
        val manual = posting(input().copy(validatedParse = false, authority = CorrectionAuthority.USER_FINANCIAL_TYPE))
        equal("10", metric(build(batch(listOf(manual))), SummaryMetric.SPENDING_GROSS))
    }

    @Test fun duplicateCurrentKeysAndCrossEnvironmentRowsAreRejected() {
        val current = posting()
        assertThrows(IllegalArgumentException::class.java) { build(batch(listOf(current, current.copy(command = current.command.copy(revision = PostingRevision(0, 2)))))) }
        val otherEnvironment = current.copy(command = current.command.copy(key = PostingKey(Environment.MIGRATION_REHEARSAL, "other"), source = null, destination = null))
        assertThrows(IllegalArgumentException::class.java) { build(batch(listOf(otherEnvironment))) }
        assertThrows(IllegalArgumentException::class.java) { build(batch().copy(query = query.copy(environment = Environment.MIGRATION_REHEARSAL))) }
        assertThrows(IllegalArgumentException::class.java) { build(batch().copy(query = query.copy(currency = Currency.USD))) }
    }

    @Test fun activeEvidenceCannotBeClaimedTwice() {
        val first = posting()
        val second = posting(input("second").copy(evidenceIds = first.command.evidenceIds))
        assertThrows(IllegalArgumentException::class.java) { build(batch(listOf(first, second))) }
    }

    @Test fun staleHistoricalRegistryAndInvalidRelationshipsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { build(batch().copy(assets = listOf(account.copy(last4 = "9999")))) }
        assertThrows(IllegalArgumentException::class.java) { build(batch(emptyList(), listOf(debit))) }
        assertThrows(IllegalArgumentException::class.java) { build(batch(emptyList(), listOf(account, account))) }
        assertThrows(IllegalArgumentException::class.java) { build(batch(emptyList(), listOf(account.copy(id = account.id.copy(environment = Environment.MIGRATION_REHEARSAL))))) }
    }

    @Test fun independentlyOwnedCreditCardKeepsSpendingWithoutInventingSharedDues() {
        val independent=credit.copy(parentId=null,creditCardRole=null)
        val assets=listOf(independent)
        val purchase=posting(input("independent",source=independent,registry=assets))
        val refund=posting(input("refund",FinancialType.REFUND,"3",source=null,destination=independent,registry=assets))
        val result=build(batch(listOf(purchase,refund),assets))
        assertTrue(result.snapshot.creditFacilities.isEmpty())
        assertEquals(independent.id,result.snapshot.standaloneCreditCards.single().cardId)
        equal("7",metric(result,SummaryMetric.CREDIT_PURCHASES))
        equal("7",metric(result,SummaryMetric.SPENDING_NET))
        assertNull(result.snapshot.totals!!.facilityDue)
    }

    @Test fun explicitSalaryCalendarAndStatementWindowsStaySeparateFromSelectedQuery() {
        val assets = listOf(facility, credit)
        val before = posting(input("before", source = credit, registry = assets).copy(time = TransactionTime(query.from.minusSeconds(1), query.zone)))
        val within = posting(input("within", amount = "20", source = credit, registry = assets))
        val windows = DashboardPeriods(period(PeriodKind.SALARY, query.from.minusSeconds(86400)), period(PeriodKind.CALENDAR_MONTH),
            mapOf(facility.id to period(PeriodKind.STATEMENT, query.from.minusSeconds(86400), query.from.plusSeconds(1))))
        val result = build(batch(listOf(before, within), assets).copy(periods = windows))
        equal("20", metric(result, SummaryMetric.SPENDING_GROSS))
        assertEquals(listOf(within.command.key), result.snapshot.transactions.map { it.key })
        val card = result.snapshot.creditFacilities.single().cards.single()
        equal("30", card.salarySpending!!.net!!.amount)
        equal("20", card.calendarSpending!!.net!!.amount)
        equal("10", card.statementSpending!!.net!!.amount)
        assertThrows(IllegalArgumentException::class.java) { build(batch(listOf(before), assets)) }
        assertThrows(IllegalArgumentException::class.java) { build(batch(emptyList()).copy(periods = DashboardPeriods(salary = period(PeriodKind.STATEMENT)))) }
    }

    @Test fun halfOpenQueryAndCallerZoneArePreservedWithoutBankTimeGuessing() {
        val start = posting(input("at-start").copy(time = TransactionTime(query.from, ZoneId.of("UTC"))))
        val end = posting(input("at-end").copy(time = TransactionTime(query.until, query.zone)))
        val aux = period(PeriodKind.SALARY, query.from, query.until.plusSeconds(1))
        val result = build(batch(listOf(start, end)).copy(periods = DashboardPeriods(salary = aux)))
        assertEquals(listOf(start.command.key), result.snapshot.transactions.map { it.key })
        assertEquals(ZoneId.of("UTC"), result.snapshot.transactions.single().time.zone)
        assertEquals(query.zone, result.snapshot.query.zone)
    }

    @Test fun debitAndCreditPeriodIndicatorsPreserveTheirDifferentLegacyActivityPolicies() {
        // Read-only semantics: DebitCardSpendClassifier and CreditCardOverviewBuilder.netSpending.
        // This is a source-derived characterization, not a cross-run of those Android-facing builders.
        val assets = listOf(account, debit, facility, credit)
        val rows = listOf(debit, credit).flatMap { card -> listOf(
            input("${card.id.localId}-purchase", FinancialType.EXPENSE, "100", card, registry = assets),
            input("${card.id.localId}-withdrawal", FinancialType.CASH_WITHDRAWAL, "50", card, registry = assets),
            input("${card.id.localId}-fee", FinancialType.FEE, "5", card, registry = assets),
            input("${card.id.localId}-bill", FinancialType.BILL_PAYMENT, "10", card, registry = assets),
            input("${card.id.localId}-refund", FinancialType.REFUND, "20", null, card, assets)) }
            .map(::posting) + posting(input("credit-payment", FinancialType.CREDIT_CARD_PAYMENT, "100", account, credit, assets))
        val result = build(batch(rows, assets).copy(periods = DashboardPeriods(salary = period(PeriodKind.SALARY))))
        equal("130", result.snapshot.debitCards.single().spending!!.amount) // purchase + withdrawal - refund
        equal("85", result.snapshot.creditFacilities.single().cards.single().salarySpending!!.net!!.amount) // purchase + fee - refund
        equal("85", result.snapshot.creditFacilities.single().salarySpending!!.amount)
        equal("85", result.snapshot.totals!!.creditSalarySpending!!.amount)
        equal("130", result.snapshot.totals.debitSalarySpending!!.amount)
        equal("95", metric(result, SummaryMetric.CREDIT_PURCHASES)) // monthly definition still includes bill
        equal("230", metric(result, SummaryMetric.SPENDING_GROSS))
        equal("100", metric(result, SummaryMetric.CASH_WITHDRAWALS))
        result.snapshot.transactions.filter { it.key.transactionId.startsWith("debit-") }.forEach { fact ->
            assertEquals(setOf(debit.id), fact.cards)
            val expected = if (fact.effectiveType in setOf(FinancialType.EXPENSE, FinancialType.CASH_WITHDRAWAL, FinancialType.REFUND)) setOf(debit.id) else emptySet()
            assertEquals(expected, fact.debitSpendingCards)
        }
    }

    @Test fun directFacilityPurchaseCannotInventMemberCardPeriodAllocation() {
        val assets = listOf(facility, credit)
        val result = build(batch(listOf(posting(input(source = facility, registry = assets))), assets)
            .copy(periods = DashboardPeriods(salary = period(PeriodKind.SALARY),
                statements = mapOf(facility.id to period(PeriodKind.STATEMENT)))))
        equal("10", metric(result, SummaryMetric.CREDIT_PURCHASES))
        assertNull(result.snapshot.creditFacilities.single().salarySpending)
        assertNull(result.snapshot.creditFacilities.single().statementSpending)
        assertNull(result.snapshot.creditFacilities.single().cards.single().salarySpending!!.net)
        assertNull(result.snapshot.creditFacilities.single().cards.single().statementSpending!!.net)
        assertNull(result.snapshot.totals!!.creditSalarySpending)
    }

    @Test fun accountBackedMadaActivityWithoutCardAttributionIsUnknownRatherThanZero() {
        val unrelated = asset("unrelated-mada", AssetKind.DEBIT_CARD, other)
        val unlinked = asset("unlinked-mada", AssetKind.DEBIT_CARD)
        val assets = listOf(account, other, debit, unrelated, unlinked)
        for (type in listOf(FinancialType.EXPENSE, FinancialType.CASH_WITHDRAWAL, FinancialType.REFUND)) {
            val input = if (type == FinancialType.REFUND) input("account-refund", type, "20", null, account, assets)
                else input("account-spend", type, "20", account, registry = assets)
            val result = build(batch(listOf(posting(input)), assets).copy(periods = DashboardPeriods(salary = period(PeriodKind.SALARY))))
            assertNull(result.snapshot.debitCards.single { it.cardId == debit.id }.spending)
            assertNull(result.snapshot.debitCards.single { it.cardId == unlinked.id }.spending)
            equal("0", result.snapshot.debitCards.single { it.cardId == unrelated.id }.spending!!.amount)
            assertNull(result.snapshot.totals!!.debitSalarySpending)
        }
        val empty = build(batch(emptyList(), assets).copy(periods = DashboardPeriods(salary = period(PeriodKind.SALARY))))
        assertTrue(empty.snapshot.debitCards.all { it.spending != null && it.spending.amount.signum() == 0 })
        equal("0", empty.snapshot.totals!!.debitSalarySpending!!.amount)
    }

    @Test fun forexFeesUsePersistedProvenanceWhileTransactionEquivalentExcludesFee() {
        val foreign = posting(input(currency = Currency.USD).copy(appliedFx = AppliedFx(BigDecimal("3.756957"), "SMS")))
        val fee = LinkedInternationalFee(foreign.command.key, foreign.command.revision, foreign.command.evidenceIds.single(), money("1.99"))
        val result = build(batch(listOf(foreign)).copy(fees = listOf(fee)))
        equal("39.56", metric(result, SummaryMetric.SPENDING_GROSS))
        equal("37.57", result.snapshot.transactions.single().sarEquivalent!!.amount)
        equal("1.99", result.snapshot.transactions.single().internationalFee!!.amount)
        assertEquals(foreign.command.appliedFx, result.snapshot.transactions.single().appliedFx)
        assertEquals(Currency.USD, result.snapshot.transactions.single().amount.currency)
        assertThrows(IllegalArgumentException::class.java) { build(batch(listOf(foreign)).copy(fees = listOf(fee.copy(revision = PostingRevision(99, 1))))) }
        assertThrows(IllegalArgumentException::class.java) { build(batch(listOf(foreign)).copy(fees = listOf(fee.copy(evidenceId = "different")))) }
    }

    @Test fun missingFxKeepsConfirmedSarVisibleButNeverInventsForeignValuation() {
        val foreign = posting(input("foreign", currency = Currency.USD))
        val result = build(batch(listOf(posting(), foreign)))
        equal("10",metric(result,SummaryMetric.SPENDING_GROSS))
        equal("10",result.snapshot.fleetFlow!!.externalOut.amount)
        assertTrue(DashboardSection.VALUATION in result.unavailableSections)
        assertEquals(1, result.financialSummary!!.excludedOtherCurrencyCount)
        assertNull(result.snapshot.transactions.first { it.key == foreign.command.key }.sarEquivalent)
        val rated = posting(foreign.classification.input.copy(appliedFx = AppliedFx(BigDecimal("3.75"), "SMS")))
        val unknownFee = build(batch(listOf(rated)).copy(coverage = DashboardCoverage(true, true, false, 0)))
        assertTrue(unknownFee.financialSummary!!.metrics.values.all { it == null })
        assertNull(unknownFee.snapshot.totals!!.accountOutflow)
        equal("10", metric(build(batch().copy(coverage = DashboardCoverage(true, true, false, 0))), SummaryMetric.SPENDING_GROSS))
    }

    @Test fun observedBalancesAndSharedFacilityDuesAreNativeAndCountedOnce() {
        val assets = listOf(account, facility, credit, supplement, loan)
        val at = query.from
        val result = build(batch(emptyList(), assets).copy(assetFacts = listOf(
            DashboardAssetFacts(account.id, balance = ObservedSignedBalance(signed("-5", Currency.USD), at)),
            DashboardAssetFacts(facility.id, statement = ObservedStatement(money("100"), at, LocalDate.parse("2026-10-25"))),
            DashboardAssetFacts(loan.id, loanType = "PERSONAL", loanRemaining = ObservedMoney(money("500"), at)))))
        val snapshot = result.snapshot
        assertEquals(Currency.USD, snapshot.accounts.single().balance!!.currency)
        equal("-5", snapshot.accounts.single().balance!!.amount)
        assertEquals(2, snapshot.creditFacilities.single().cards.size)
        equal("100", snapshot.totals!!.facilityDue!!.amount)
        equal("500", snapshot.totals.loanRemaining!!.amount)
        val mixed = build(batch(emptyList(), assets).copy(assetFacts = listOf(DashboardAssetFacts(facility.id,
            statement = ObservedStatement(money("100", Currency.USD), at, null)))))
        assertNull(mixed.snapshot.totals!!.facilityDue)
    }

    @Test fun observationsAndMetadataMustBeUniqueScopedAndBoundToCurrentRevision() {
        val row = posting()
        val fact = DashboardPostingFacts(row.command.key, row.command.revision, row.command.evidenceIds.single(), "Shop")
        val result = build(batch().copy(postingFacts = listOf(fact)))
        assertEquals("Shop", result.snapshot.transactions.single().merchant)
        assertThrows(IllegalArgumentException::class.java) { build(batch().copy(postingFacts = listOf(fact.copy(revision = PostingRevision(0, 0))))) }
        assertThrows(IllegalArgumentException::class.java) { build(batch().copy(postingFacts = listOf(fact.copy(evidenceId = "foreign")))) }
        assertThrows(IllegalArgumentException::class.java) { build(batch().copy(assetFacts = listOf(DashboardAssetFacts(account.id), DashboardAssetFacts(account.id)))) }
        assertThrows(IllegalArgumentException::class.java) { build(batch().copy(assetFacts = listOf(DashboardAssetFacts(other.id)))) }
        val future = DashboardAssetFacts(account.id, balance = ObservedSignedBalance(signed("1"), query.until.plusSeconds(1)))
        assertThrows(IllegalArgumentException::class.java) { build(batch().copy(assetFacts = listOf(future))) }
    }

    @Test fun checkedSelfTransferPreservesBothLegsAndRemainsNeutralAtFleetLevel() {
        val registry = listOf(account, other)
        val out = input("out", FinancialType.SELF_TRANSFER, source = account, destination = other, registry = registry)
        val incoming = out.copy(key = PostingKey(Environment.BETA, "incoming"), evidenceIds = setOf("incoming-evidence"))
        val reference = ConfirmedBankTransferReference(bank, "trusted-bank-transfer")
        val pair = (SelfTransferMatcher.match(listOf(TransferObservation(out, TransferDirection.OUTBOUND, reference),
            TransferObservation(incoming, TransferDirection.INBOUND, reference)), TransferMatchProof.BankReference(reference)) as TransferMatchResult.Matched).transfer
        val stored = (FinancialPostingRules.prepare(pair) as PostingPreparation.Ready).posting
        val result = build(batch(listOf(stored), registry))
        val trends = result.snapshot.analysis!!.trends
        fun trendTotal(scope: TrendScope, assetId: AssetId? = null) = trends.single { it.scope == scope && it.assetId == assetId && it.category == null }
            .points.fold(BigDecimal.ZERO) { sum, point -> sum + point.net!!.amount }
        equal("0", trendTotal(TrendScope.ACCOUNTS))
        equal("10", trendTotal(TrendScope.ASSET, account.id))
        equal("0", trendTotal(TrendScope.ASSET, other.id))
        equal("0", metric(result, SummaryMetric.NET_CASH_FLOW))
        equal("0", result.snapshot.totals!!.accountInflow!!.amount)
        equal("0", result.snapshot.totals.accountOutflow!!.amount)
        equal("0", result.snapshot.fleetFlow!!.externalIn.amount)
        equal("10", result.snapshot.fleetFlow.buckets.getValue(FlowBucket.INTERNAL_IN).amount)
        equal("10", result.snapshot.fleetFlow.buckets.getValue(FlowBucket.INTERNAL_OUT).amount)
        equal("-10", result.snapshot.accounts.first { it.id == account.id }.flow!!.cashPosition.amount)
        val unproven = stored.copy(observations = emptyList(), matchIdentity = null)
        assertThrows(IllegalArgumentException::class.java) { build(batch(listOf(unproven), registry)) }
        val otherOut = out.copy(key = PostingKey(Environment.BETA, "another-out"), evidenceIds = setOf("other-out-evidence"))
        val otherIn = incoming.copy(key = PostingKey(Environment.BETA, "another-in"), evidenceIds = setOf("other-in-evidence"))
        val otherPair = (SelfTransferMatcher.match(listOf(TransferObservation(otherOut, TransferDirection.OUTBOUND, reference),
            TransferObservation(otherIn, TransferDirection.INBOUND, reference)), TransferMatchProof.BankReference(reference)) as TransferMatchResult.Matched).transfer
        val duplicateProof = (FinancialPostingRules.prepare(otherPair) as PostingPreparation.Ready).posting
        assertThrows(IllegalArgumentException::class.java) { build(batch(listOf(stored, duplicateProof), registry)) }
    }

    @Test fun retainedCallerCollectionsAndReturnedViewsCannotMutateSnapshot() = runTest {
        val rows = mutableListOf(DashboardLedgerRow(posting(), true))
        val assets = mutableListOf(account)
        val result = BetaDashboardProducer { batch().copy(rows = rows, assets = assets) }.readWithCoverage(query)
        rows.clear(); assets.clear()
        assertEquals(1, result.snapshot.transactions.size)
        assertEquals(1, result.snapshot.accounts.size)
        assertThrows(UnsupportedOperationException::class.java) { (result.snapshot.accounts as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.snapshot.transactions.single().evidenceIds as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.snapshot.accounts.single().flow!!.buckets as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.financialSummary!!.metrics as MutableMap).clear() }
    }

    @Test fun stableQualifiedOrderingDoesNotMergeSuffixesOrDependOnSourceOrder() {
        val secondBank = account.copy(id = account.id.copy(bank = BankId("D360")))
        val first = build(batch(emptyList(), listOf(account, secondBank)))
        val reversed = build(batch(emptyList(), listOf(secondBank, account)))
        assertEquals(first, reversed)
        assertEquals(2, first.snapshot.accounts.size)
    }

    @Test fun originalPinnedMonthlyCalculatorMatchesProducedComparableMetrics() {
        val inputs = listOf(input("purchase", amount = "100"),
            input("refund", FinancialType.REFUND, "120", source = null, destination = account),
            input("income", FinancialType.INCOME, "1000", source = null, destination = account).copy(incomeAttribution = IncomeAttribution.SALARY),
            input("fee", FinancialType.FEE, "5"))
        val oldRows = inputs.map { row -> com.baraa.masroof.domain.model.FinancialTransaction(
            row.key.transactionId, com.baraa.masroof.domain.model.FinancialTransactionType.valueOf(row.storedType.name),
            com.baraa.masroof.core.money.Money.of(row.amount.amount, com.baraa.masroof.core.money.Currency.SAR), row.time.instant,
            null, null, null, null, null, row.evidenceIds.toList()) }
        val old = com.baraa.masroof.application.dashboard.MonthlyFinancialSummaryCalculator.summarize(
            com.baraa.masroof.domain.period.FinancialPeriod(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-01")), oldRows, 0)
        val actual = build(batch(inputs.map(::posting)))
        val expected = mapOf(SummaryMetric.SPENDING_GROSS to old.spendingGross.amount, SummaryMetric.REFUNDS to old.refunds.amount,
            SummaryMetric.SPENDING_NET to old.spendingNet.amount, SummaryMetric.INCOME to old.income.amount,
            SummaryMetric.COMBINED_INCOMING to old.combinedIncoming.amount, SummaryMetric.NET_CASH_FLOW to old.netCashFlow.amount)
        expected.forEach { (key, value) -> assertEquals(0, value.compareTo(metric(actual, key))) }
        assertEquals(old.transactionCount, actual.financialSummary!!.transactionCount)
    }
}
