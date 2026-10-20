package com.baraa.masroof.lab.bank

import com.baraa.masroof.lab.bank.interpretation.RuleInterpreter
import com.baraa.masroof.lab.bank.rules.RuleLibrary
import com.baraa.masroof.lab.finance.value.BankId
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TransferNetworkTest {
    private val local = BankId("LOCAL")
    private val remote = BankId("REMOTE")
    private val catalog = BankCatalog(listOf(BankDefinition(local, setOf("SyntheticSender"))))
    private val outgoing = "Outgoing transfer\nAmount: SAR 100\nFrom account: 1234\nTo account: 5678\n2026-10-18 13:00"
    private val incoming = outgoing.replace("Outgoing", "Incoming")
    private val schema get() = RuleLibrary.current().let { it.copy(network = it.network.copy(bankAliases = mapOf(
        "LOCAL" to listOf("Local Bank", "البنك المحلي"), "REMOTE" to listOf("Remote Bank", "البنك الآخر")))) }
    private fun input(body: String) = MessageInput("transfer-fixture", "SyntheticSender", body, Instant.parse("2026-10-18T10:00:00Z"))
    private fun parse(body: String, config: BankRuleSchema = schema) = RuleInterpreter(config).interpret(input(body), catalog)

    @Test fun explicitUnknownExternalBankDoesNotInventBankIdentityOrOwnership() {
        val config=schema.copy(network=schema.network.copy(standaloneDestinationBrackets=true,unidentifiedExternalBankMarkers=listOf("bank","بنك")))
        val body=outgoing.replace("Outgoing transfer","Outgoing transfer\n[Future Bank]")
        val r=parse(body,config)
        assertEquals(ParseStatus.SUCCESS,r.status)
        assertEquals(BankNetwork.INTER_BANK,r.facts.bankNetwork)
        assertEquals(local,r.facts.sourceAccount!!.bank)
        assertNull(r.facts.destinationAccount!!.bank)
        for(bad in listOf(body.replace("Future Bank","Bank"),body.replace("Future Bank","Unknown"),body.replace("Future Bank","Local Bank / Future Bank"),"$body\nDestination bank: Other Bank",body.replace("Outgoing transfer","Outgoing inter-bank transfer\nBank network: intra_bank"),"$outgoing\n[Future Bank]")) {
            assertEquals(ParseStatus.REVIEW_REQUIRED,parse(bad,config).status)
        }
        val collision=config.copy(network=config.network.copy(bankAliases=config.network.bankAliases+mapOf("SECOND" to listOf("Future Bank"),"THIRD" to listOf("Future Bank"))))
        assertEquals(ParseStatus.REVIEW_REQUIRED,parse(body,collision).status)
        assertEquals(ParseStatus.REVIEW_REQUIRED,parse("$outgoing\nSource bank: Future Bank\nDestination bank: Remote Bank",config).status)
    }

    @Test fun outgoingAndIncomingBindBankIdentityToTheCorrectAccountRole() {
        val out = parse("$outgoing\nDestination bank: Remote Bank")
        assertEquals(ParseStatus.SUCCESS, out.status); assertEquals(BankNetwork.INTER_BANK, out.facts.bankNetwork)
        assertEquals(local, out.facts.sourceAccount!!.bank); assertEquals(remote, out.facts.destinationAccount!!.bank)
        assertEquals(MoneyDirection.OUTGOING, out.facts.direction)
        val inc = parse("$incoming\nSource bank: Remote Bank")
        assertEquals(ParseStatus.SUCCESS, inc.status); assertEquals(BankNetwork.INTER_BANK, inc.facts.bankNetwork)
        assertEquals(remote, inc.facts.sourceAccount!!.bank); assertEquals(local, inc.facts.destinationAccount!!.bank)
        assertEquals(MoneyDirection.INCOMING, inc.facts.direction)
        assertEquals(MessageFamily.TRANSFER_IN, inc.facts.family)
    }

    @Test fun intraBankAndExplicitMarkerNeverInventRemoteIdentityOrOwnership() {
        val same = parse("$outgoing\nDestination bank: Local Bank")
        assertEquals(ParseStatus.SUCCESS, same.status); assertEquals(BankNetwork.INTRA_BANK, same.facts.bankNetwork)
        assertEquals(local, same.facts.destinationAccount!!.bank)
        val marker = parse("$outgoing\nNetwork: inter-bank", RuleLibrary.current())
        assertEquals(ParseStatus.SUCCESS, marker.status); assertEquals(BankNetwork.INTER_BANK, marker.facts.bankNetwork)
        assertNull(marker.facts.destinationAccount!!.bank)
        val inc = parse("$incoming\nNetwork: INTER_BANK", RuleLibrary.current())
        assertEquals(ParseStatus.SUCCESS, inc.status); assertNull(inc.facts.sourceAccount!!.bank)
        assertEquals(local, inc.facts.destinationAccount!!.bank)
        assertEquals(ParseStatus.SUCCESS, parse("$outgoing\nNetwork: INTRA_BANK").status)
    }

    @Test fun collisionsContradictionsUnknownNamesAndMissingRolesStayReview() {
        val cases = listOf(outgoing, "$outgoing\nDestination bank: Unconfigured Bank",
            "$outgoing\nSource bank: Remote Bank\nDestination bank: Local Bank",
            "$incoming\nSource bank: Remote Bank\nDestination bank: Remote Bank",
            "$outgoing\nDestination bank: Remote Bank\nNetwork: intra-bank",
            "$incoming\nSource bank: Local Bank\nNetwork: inter-bank",
            "$outgoing\nDestination bank: Remote Bank\nDestination bank: Local Bank",
            "$outgoing\nNetwork: inter-bank\nNetwork: intra-bank", "$outgoing\nNetwork: UNKNOWN",
            "$outgoing\nNetwork=inter-bank", "$outgoing\nDestination bank:",
            outgoing.replace("From account: 1234", "") + "\nNetwork: inter-bank",
            outgoing.replace("To account: 5678", "") + "\nNetwork: inter-bank",
            "$outgoing\nIncoming transfer\nNetwork: inter-bank")
        cases.forEach { body -> assertEquals(body, ParseStatus.REVIEW_REQUIRED, parse(body).status) }
        val ambiguous = schema.copy(network = schema.network.copy(bankAliases = mapOf("LOCAL" to listOf("Same"), "REMOTE" to listOf("Same"))))
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$outgoing\nDestination bank: Same", ambiguous).status)
        val conflictingMarkers = schema.copy(rules = schema.rules.map { it.copy(observations = it.observations +
            ("network.inter" to listOf("intra-bank"))) })
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$outgoing\nNetwork: intra-bank", conflictingMarkers).status)
    }

    @Test fun merchantReferencesQuotesAndUnscopedNamesCannotResolveTheNetwork() {
        listOf("Merchant: Destination bank: Remote Bank", "Counterparty: Remote Bank", "Transaction reference: inter-bank",
            "Note: Network: inter-bank", "[Remote Bank]").forEach { text ->
            val r = parse("$outgoing\n$text")
            assertEquals(text, ParseStatus.REVIEW_REQUIRED, r.status)
            assertNull(r.facts.bankNetwork)
        }
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("```\n$outgoing\nNetwork: inter-bank\n```").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("Declined\n$outgoing\nNetwork: inter-bank").status)
        assertEquals(ParseStatus.NON_FINANCIAL, parse("OTP 123456\n$outgoing\nNetwork: inter-bank").status)
    }

    @Test fun optionalStandaloneDestinationRequiresOutgoingTitleAdjacencyAndUniqueAlias() {
        val config = schema.copy(network = schema.network.copy(standaloneDestinationBrackets = true))
        val body = outgoing.replace("Outgoing transfer", "Outgoing transfer\n[Remote Bank]")
        val r = parse(body, config)
        assertEquals(ParseStatus.SUCCESS, r.status); assertEquals(remote, r.facts.destinationAccount!!.bank)
        assertTrue(r.evidence.any { it.field == "destinationBank" && it.slice(input(body)) == "[Remote Bank]" })
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(body).status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(body.replace("Outgoing", "Incoming"), config).status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$outgoing\n[Remote Bank]", config).status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(body.replace("Remote Bank", "Unknown"), config).status)
    }

    @Test fun originalArabicEvidenceAndEqualLast4AcrossBanksStayDistinct() {
        val body = "😀\r\nحوالة صادرة\r\nبمبلغ: ١٠٠ SAR\r\nمن حساب: ١٢٣٤\r\nإلى حساب: ١٢٣٤\r\nبنك المستفيد: البنك الآخر\r\n٢٠٢٦-١٠-١٨ ١٣:٠٠"
        val r = parse(body)
        assertEquals(ParseStatus.SUCCESS, r.status)
        assertEquals(local, r.facts.sourceAccount!!.bank); assertEquals(remote, r.facts.destinationAccount!!.bank)
        assertEquals("1234", r.facts.sourceAccount!!.maskedNumber); assertEquals("1234", r.facts.destinationAccount!!.maskedNumber)
        assertTrue(r.evidence.any { it.field == "destinationBank" && it.slice(input(body)) == "بنك المستفيد: البنك الآخر" })
        assertTrue(r.evidence.all { it.end <= body.length })
    }

    @Test fun aliasesAreImmutableLiteralAndCannotOverrideCatalogRouting() {
        val aliases = mutableListOf("Remote Bank")
        val config = schema.copy(network = schema.network.copy(bankAliases = mapOf("REMOTE" to aliases)))
        val engine = RuleInterpreter(config)
        aliases[0] = "Other"
        val body = "$outgoing\nDestination bank: Remote Bank"
        assertEquals(ParseStatus.SUCCESS, engine.interpret(input(body), catalog).status)
        assertNotEquals(engine.version, RuleInterpreter(config).version)
        val collision = BankCatalog(listOf(BankDefinition(local, setOf("SyntheticSender")), BankDefinition(remote, setOf("SyntheticSender"))))
        assertTrue(Blocker.AMBIGUOUS_BANK in engine.interpret(input(body), collision).blockers)
        val invalid = schema.copy(network = schema.network.copy(bankAliases = mapOf("UNKNOWN" to listOf("Remote Bank"))))
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(body, invalid).status)
        val foldedEmpty = schema.copy(network = schema.network.copy(bankAliases = mapOf("REMOTE" to listOf("ـ"))))
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$outgoing\nDestination bank:", foldedEmpty).status)
        val literal = schema.copy(network = schema.network.copy(bankAliases = mapOf("REMOTE" to listOf("(a+)+$"))))
        assertEquals(ParseStatus.SUCCESS, parse("$outgoing\nDestination bank: (a+)+$", literal).status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$outgoing\nDestination bank: aaaaaa", literal).status)
    }

    @Test fun familyAndDirectionCannotBeSuppliedByPartyFieldsOrEditedAway() {
        val fields = outgoing.substringAfter('\n')
        listOf("Merchant: Incoming transfer", "Counterparty Incoming transfer", "Transaction reference: Outgoing transfer",
            "From bank: Incoming transfer", "Income type: salary credited").forEach { title ->
            val r = parse("$title\n$fields\nNetwork: inter-bank")
            assertEquals(title, ParseStatus.REVIEW_REQUIRED, r.status)
            assertEquals(MessageFamily.UNKNOWN, r.facts.family)
        }
        val configured = schema.copy(rules = schema.rules.filter { it.family == MessageFamily.TRANSFER_OUT })
        val r = parse("$outgoing\nIncoming transfer\nNetwork: inter-bank", configured)
        assertEquals(ParseStatus.REVIEW_REQUIRED, r.status)
        assertTrue(Blocker.CONFLICTING_FACTS in r.blockers)
        assertEquals(ParseStatus.SUCCESS, parse(outgoing.replace("Outgoing transfer", "Fund transfer debited") + "\nNetwork: inter-bank").status)
    }
    @Test fun incomingBracketNeedsExplicitLocalDestinationAndPartyAndNeverUsesDebitLineAsSource() {
        val config = schema.copy(network = schema.network.copy(standaloneSourceBrackets = true))
        val body = "Incoming transfer\nDeposited to account: 1234\nValue: 100 SAR\nFrom: Synthetic Person\n[Remote Bank]\nخصمت من حساب: 9999\nTime: 13:00 18-10-2026"
        val result = parse(body, config)
        assertEquals(result.diagnostics.toString(), ParseStatus.SUCCESS, result.status)
        assertNull(result.facts.sourceAccount)
        assertEquals(local, result.facts.destinationAccount!!.bank)
        assertEquals(BankNetwork.INTER_BANK, result.facts.bankNetwork)
        assertTrue(result.evidence.any { it.field == "sourceBank" && it.slice(input(body)) == "[Remote Bank]" })
        listOf(body.replace("Deposited to account: 1234", ""), body.replace("From: Synthetic Person", ""),
            body.replace("Remote Bank", "Unknown"), "$body\nSource bank: Local Bank").forEach {
            assertEquals(it, ParseStatus.REVIEW_REQUIRED, parse(it, config).status)
        }
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(body).status)
    }

}
