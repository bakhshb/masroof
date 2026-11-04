package com.baraa.masroof.bank.contract

import com.baraa.masroof.testsupport.bundledPatterns
import com.baraa.masroof.testsupport.engineFixtureAdapter
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.parsing.fixtures.AlJaziraFixture
import com.baraa.masroof.parsing.fixtures.AlJaziraFixtureLoader
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import com.baraa.masroof.bank.MessagePatternSmsAdapter
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.engine.bank.patterns.PatternFactContract
import com.baraa.masroof.engine.bank.MoneyDirection
import kotlinx.serialization.json.*
import java.io.File

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
            BankSmsAdapterContractCase(engineFixtureAdapter(), alJaziraContractSamples),
            BankSmsAdapterContractCase(StubBankSmsAdapter(), StubBankSmsAdapter.contractSamples),
            stcPatternContractCase(),
        ) + FiveBankSmsTest.cases()

        private fun stcPatternContractCase(): BankSmsAdapterContractCase {
            val configuration = bundledPatterns("BANK_STC")
            val bank = configuration.banks.single()
            val approved = bank.copy(patterns = bank.patterns.map {
                if (it.id == "stc-qattah") it.copy(factContract = PatternFactContract(com.baraa.masroof.engine.bank.MessageFamily.TRANSFER_OUT, MoneyDirection.OUTGOING)) else it
            })
            val adapter = MessagePatternSmsAdapter(approved, PatternFactInterpreter(configuration.copy(banks = listOf(approved))))
            val rows = Json.parseToJsonElement(File("src/test/resources/testdata/patterns/stc-seed-v1.json").readText()).jsonObject.getValue("rows").jsonArray.map { it.jsonObject }
            val financial = rows.filter { it.getValue("id").jsonPrimitive.content == "stc-qattah" }.map {
                ContractSms("stc-qattah-${it.getValue("teach").jsonPrimitive.boolean}", "STC Bank", it.getValue("body").jsonPrimitive.content)
            }
            val otp = Json.parseToJsonElement(File("src/test/resources/testdata/patterns/stc-otp-redacted.json").readText()).jsonObject
            return BankSmsAdapterContractCase(adapter, BankSmsAdapterContractSamples(listOf("STC Bank"),
                listOf("AlJazira", "STC BankX", "stc bank", "PERSON"), financial,
                listOf(ContractSms("stc-real-otp-redacted", otp.getValue("sender").jsonPrimitive.content, otp.getValue("body").jsonPrimitive.content)),
                listOf(ContractSms("stc-unmatched", "STC Bank", "Unrecognized message"),
                    ContractSms("stc-preview-only", "STC Bank", rows.first().getValue("body").jsonPrimitive.content))))
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun adapters(): List<Array<Any>> = cases().map { arrayOf(it) }

        private fun toContractSms(fixture: AlJaziraFixture) =
            ContractSms(label = fixture.id, sender = fixture.sender, body = fixture.body)
    }
}
