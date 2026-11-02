package com.baraa.masroof.bank.contract

import org.junit.Assume.assumeTrue
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.bank.BankRoutingResult
import com.baraa.masroof.bank.MessagePatternSmsAdapter
import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.engine.bank.MessageInput
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.bank.InterpretationDraftMapper
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Observed bank layouts with entirely fictional values, names and timestamps. */
class FiveBankSmsTest {
    @Test fun excludedCapabilities_stayInReviewInsteadOfPosting() {
        val snb = adapters.getValue("BANK_SNB")
        val at = Instant.parse("2026-09-15T09:30:00Z")
        // Signed balances are not adopted: a negative balance is never stored as a positive one.
        val negative = snb.parse(SmsParseInput("negative-balance", "SNB-AlAhli",
            "شراء إنترنت\nمبلغ 25 SAR\nبطاقة ائتمانية *4321\nمن TEST SHOP\nفي 2026-09-15 12:30\nالصرف المتبقي -50 SAR", at))
        assertFalse(negative.toString(), negative is ParseResult.Success)
        // A card-funded bill keeps needing a source account.
        val bill = snb.parse(SmsParseInput("card-bill", "SNB-AlAhli",
            "سداد فاتورة\nبطاقة ائتمانية **4321\nمبلغ 60 SAR\nمفوتر 100\nفي 2026-09-15 12:30", at))
        assertFalse(bill.toString(), bill is ParseResult.Success)
        // No configured default account: a title amount is read, but the missing local account stays reviewable.
        val compact = snb.parse(SmsParseInput("compact", "SNB-AlAhli",
            "حوالة صادرة داخلية ب125.50 SAR\nل*5678 TEST PERSON\n2026-09-15 12:30", at))
        assertFalse(compact.toString(), compact is ParseResult.Success)
        assertEquals("125.50", BankSmsAdapterContract.parsedEvent(compact)?.amount?.amount?.toPlainString())
        // A full IBAN is text, never shortened into an owned account suffix.
        val iban = snb.parse(SmsParseInput("iban", "SNB-AlAhli",
            "حوالة صادرة داخلية\nمن حساب SA0380000000608010167519\nمبلغ 100 SAR\nفي 2026-09-15 12:30", at))
        assertNull(BankSmsAdapterContract.parsedEvent(iban)?.sourceAccountRef)
    }

    @Test fun fixtures_preserveFinancialFactsAndSecurityGates() {
        rows.forEach { row ->
            val label = row.text("id")
            val result = BankSmsAdapterContract.parse(adapters.getValue(row.text("bankId")), sms(row))
            val event = BankSmsAdapterContract.parsedEvent(result)
            assertNotNull(label, event)
            assertEquals("$label: status, $result", row.text("status"), event!!.parseStatus.name)
            assertEquals(label, row.text("family"), event.messageFamily.name)
            row["amount"]?.jsonPrimitive?.contentOrNull?.let {
                assertEquals(label, 0, it.toBigDecimal().compareTo(event.amount!!.amount))
            }
            row["cardChannel"]?.jsonPrimitive?.contentOrNull?.let {
                val details = when (result) {
                    is ParseResult.Success -> result.details
                    else -> error("$label expected success")
                }
                assertEquals(label, it, details.cardSmsChannel?.name)
            }
        }
    }

    @Test fun exactSenders_routeOnceAndDoNotOverlapOtherBanks() {
        val registry = BankSmsRegistry(BankSmsAdapterContractTest.cases().map { it.adapter })
        rows.forEach { row ->
            val route = registry.route(row.text("sender"), row.text("body"))
            assertTrue(row.text("id"), route is BankRoutingResult.Matched)
            assertEquals(row.text("id"), Bank(row.text("bankId")), (route as BankRoutingResult.Matched).adapter.bank)
        }
        assertTrue(registry.route("Barg app", "شراء") is BankRoutingResult.NotMatched)
    }

    @Test fun privateCorpus_reportsCountsOnlyWhenExplicitlyProvided() {
        val path: String? = System.getenv("MASROOF_PRIVATE_CORPUS")
        assumeTrue("MASROOF_PRIVATE_CORPUS is not set", path != null)
        val senders = rows.associate { it.text("sender") to it.text("bankId") }
        val counts = linkedMapOf<String, Int>()
        val findings = linkedMapOf<String, Int>()
        val engines = senders.values.distinct().associateWith { bank ->
            PatternFactInterpreter(MessagePatternConfigurationCodec.decode(File("src/main/assets/bank-patterns/$bank.patterns.json").readText()))
        }
        val diagnosticRows = mutableListOf<JsonElement>()
        Json.parseToJsonElement(File(path!!).readText(Charsets.UTF_8).removePrefix("\uFEFF")).jsonArray.forEachIndexed { index, element ->
            val row = element.jsonObject
            val bank = senders.getValue(row.text("sender"))
            val input = MessageInput("private-$index", row.text("sender"), row.text("body"), Instant.now(), true)
            val interpretation = engines.getValue(bank).interpretRouted(input, BankId(bank))
            val result = InterpretationDraftMapper().map(input, interpretation, Bank(bank))
            val status = BankSmsAdapterContract.parsedEvent(result)?.parseStatus?.name ?: result.javaClass.simpleName
            counts.merge("$bank:$status", 1, Int::plus)
            if (result is ParseResult.ReviewRequired) interpretation.diagnostics.forEach {
                findings.merge("$bank:${it.code}", 1, Int::plus)
            }
            if (result is ParseResult.ReviewRequired) diagnosticRows += buildJsonObject {
                put("index", index); put("bank", bank); put("family", interpretation.facts.family.name)
                put("codes", JsonArray(interpretation.diagnostics.map { JsonPrimitive(it.code) }))
            }
        }
        println("Private corpus outcomes (no message contents): $counts")
        println("Private corpus validation codes: $findings")
        System.getenv("MASROOF_PRIVATE_DIAGNOSTICS")?.let { File(it).writeText(JsonArray(diagnosticRows).toString()) }
    }

    companion object {
        val rows: List<JsonObject> = Json.parseToJsonElement(File("src/test/resources/testdata/multibank/messages.json").readText()).jsonArray.map { it.jsonObject }
        val adapters = rows.map { it.text("bankId") }.distinct().associateWith { bank ->
            val config = MessagePatternConfigurationCodec.decode(File("src/main/assets/bank-patterns/$bank.patterns.json").readText())
            MessagePatternSmsAdapter(config.banks.single(), PatternFactInterpreter(config))
        }
        fun cases(): List<BankSmsAdapterContractCase> = adapters.map { (bank, adapter) ->
            val fixtures = rows.filter { it.text("bankId") == bank }
            val sender = fixtures.first().text("sender")
            BankSmsAdapterContractCase(adapter, BankSmsAdapterContractSamples(listOf(sender),
                listOf("AlJazira", "$sender-fake", "PERSON"),
                fixtures.filter { it.text("status") == "SUCCESS" }.map(::sms),
                fixtures.filter { it.text("status") == "NON_FINANCIAL" }.map(::sms),
                fixtures.filter { it.text("status") == "REVIEW_REQUIRED" }.map(::sms)))
        }
        private fun sms(row: JsonObject) = ContractSms(row.text("id"), row.text("sender"), row.text("body"))
        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    }
}
