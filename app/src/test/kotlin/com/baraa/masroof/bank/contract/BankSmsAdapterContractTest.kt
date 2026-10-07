package com.baraa.masroof.bank.contract

import com.baraa.masroof.bank.aljazira.AlJaziraSmsAdapter
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.parsing.fixtures.AlJaziraFixture
import com.baraa.masroof.parsing.fixtures.AlJaziraFixtureLoader
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BankSmsAdapterContractTest(
    private val case: BankSmsAdapterContractCase,
) {
    @Test
    fun adapter_satisfiesSharedContract() {
        BankSmsAdapterContract.verify(case)
    }

    companion object {
        private val NON_FINANCIAL_FAMILIES = setOf(
            MessageFamily.OTP.name,
            MessageFamily.NON_FINANCIAL.name,
            MessageFamily.BALANCE_NOTICE.name,
            MessageFamily.UNKNOWN.name,
        )
        private val NO_AUTOMATIC_STATUSES = setOf(
            ParseStatus.REVIEW_REQUIRED.name,
            ParseStatus.PARTIAL.name,
            ParseStatus.INVALID.name,
            ParseStatus.UNSUPPORTED.name,
        )

        val alJaziraContractSamples: BankSmsAdapterContractSamples by lazy {
            val fixtures = AlJaziraFixtureLoader.loadAllFromClasspath()
            BankSmsAdapterContractSamples(
                positiveSenders = listOf(
                    "AlJazira",
                    "BankAlJazira",
                    "JaziraBank",
                    "AlJaziraBank",
                    "AlJazira-AD",
                    "بنك الجزيرة",
                ),
                negativeSenders = listOf(
                    "AlRajhiBank",
                    "SNB-AlAhli",
                    "D360",
                    "AlJaziraX",
                    StubBankSmsAdapter.SENDER,
                    "not-a-bank-sender",
                ),
                financial = fixtures.filter {
                    it.expected.parseStatus == ParseStatus.SUCCESS.name &&
                        it.expected.messageFamily !in NON_FINANCIAL_FAMILIES &&
                        it.expected.amount != null
                }.map(::toContractSms),
                nonFinancial = fixtures
                    .filter { it.expected.parseStatus == ParseStatus.NON_FINANCIAL.name }
                    .map(::toContractSms),
                noAutomaticFinancialOutput = fixtures
                    .filter { it.expected.parseStatus in NO_AUTOMATIC_STATUSES }
                    .map(::toContractSms),
            )
        }

        fun cases(): List<BankSmsAdapterContractCase> = listOf(
            BankSmsAdapterContractCase(AlJaziraSmsAdapter(), alJaziraContractSamples),
            BankSmsAdapterContractCase(StubBankSmsAdapter(), StubBankSmsAdapter.contractSamples),
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun adapters(): List<Array<Any>> = cases().map { arrayOf(it) }

        private fun toContractSms(fixture: AlJaziraFixture) =
            ContractSms(label = fixture.id, sender = fixture.sender, body = fixture.body)
    }
}
