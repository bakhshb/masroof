package com.baraa.masroof.bank.patterns

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.dashboard.GoldenLedgerCorpus
import com.baraa.masroof.application.dashboard.GoldenLedgerRunner
import com.baraa.masroof.application.dashboard.GoldenLedgerScenario
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.parsing.finalize.ParseFinalizer
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.model.ParsedEventDraft
import com.baraa.masroof.parsing.validator.DefaultParsedEventValidator
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import com.baraa.masroof.testsupport.engineFixtureAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AlJaziraFinancialParityTest {
    private val prettyJson = Json { prettyPrint = true }

    @Test fun comparatorDetectsChangedAmountsDetailsGatesAndRemovedFields() {
        val draft = ParsedEventDraft("probe", Bank.BANK_ALJAZIRA, MessageFamily.PURCHASE, MoneyDirection.OUTGOING,
            Money.of("1", Currency.SAR), merchant = "SYNTHETIC", confidence = Confidence(1.0, listOf("engine:old", "rules:old")),
            details = ParsedEventDetails(paymentDueDate = LocalDate.of(2026, 8, 1)))
        val finalizer = ParseFinalizer(DefaultParsedEventValidator())
        fun snapshot(value: ParsedEventDraft) = ParseParity.fields(finalizer.finalize(value, "evt-probe"))
        val original = snapshot(draft)
        assertTrue(ParseParity.differences(original, snapshot(draft.copy(
            confidence = Confidence(1.0, listOf("engine:new", "rules:new"))))).isEmpty())
        val changes = listOf(
            "event.amount" to draft.copy(amount = Money.of("2", Currency.SAR)),
            "details.paymentDueDate" to draft.copy(details = draft.details.copy(paymentDueDate = LocalDate.of(2026, 9, 1))),
            "event.parseStatus" to draft.copy(confidence = Confidence(0.0, emptyList())),
            "event.amount" to draft.copy(amount = null),
        )
        for ((field, changed) in changes) {
            assertTrue("Missed change to $field", field in ParseParity.differences(original, snapshot(changed)))
        }
    }

    @Test fun isolatedLedgersKeepEveryFactLinkAndReviewAcrossRepeatImport() = runBlocking {
        val reports = mutableListOf<JsonObject>()
        val frozenFields = linkedMapOf<String, JsonElement>()
        for (scenario in GoldenLedgerCorpus.loadAll()) {
            assertEquals("Unqualified oracle: ${scenario.id}", "ACTIVE", scenario.status)
            val context = ApplicationProvider.getApplicationContext<Context>()
            // Separate databases, identical inputs/ownership/clock. The candidate can change without changing the oracle.
            DashboardLedgerWorld(context, engineFixtureAdapter(profile = com.baraa.masroof.engine.bank.rules.AlJaziraRuleProfile.current())).use { reference ->
                DashboardLedgerWorld(context, bundledAlJaziraPatternAdapter()).use { candidate ->
                    GoldenLedgerRunner.prepare(reference, scenario)
                    GoldenLedgerRunner.prepare(candidate, scenario)
                    val expected = snapshot(reference, scenario)
                    val actual = snapshot(candidate, scenario)
                    assertSameFields(scenario.id, expected, actual)
                    frozenFields[scenario.id] = buildJsonObject { actual.toSortedMap().forEach { (key, value) -> put(key, value) } }
                    if (scenario.messages.isNotEmpty()) {
                        for (world in listOf(reference, candidate)) {
                            val identities = registryIds(world)
                            GoldenLedgerRunner.requireSuccessfulImport(scenario.id,
                                world.importProviderRows(GoldenLedgerRunner.providerRows(scenario)), "repeat-import")
                            GoldenLedgerRunner.requireSuccessfulReprocess(scenario.id, world.reprocessStoredEvidence())
                            assertSameFields("${scenario.id}:repeat", expected, snapshot(world, scenario))
                            assertEquals("${scenario.id}:repeat changed registry identities", identities, registryIds(world))
                        }
                    }
                    reports += buildJsonObject {
                        put("scenario", scenario.id); put("comparedFields", expected.size)
                        put("reference", ParseParity.digest(expected.toSortedMap()))
                        put("candidate", ParseParity.digest(actual.toSortedMap()))
                        put("repeatImportChecked", scenario.messages.isNotEmpty())
                    }
                }
            }
        }
        File("build/reports/pattern-parity/current-financial.json").apply {
            parentFile!!.mkdirs()
            writeText(prettyJson.encodeToString(JsonElement.serializer(), JsonArray(reports)))
        }
        File("build/reports/pattern-parity/current-financial-fields.json").writeText(
            prettyJson.encodeToString(JsonElement.serializer(), JsonObject(frozenFields)))
        val baseline = File("src/test/resources/testdata/pattern-parity/aljazira-financial-v1.json")
        assertTrue("Frozen financial reference missing; current fields exported under build/reports/pattern-parity", baseline.isFile)
        val frozen = Json.parseToJsonElement(baseline.readText()).jsonObject
        assertEquals("Financial corpus changed", frozen.keys, frozenFields.keys)
        frozenFields.forEach { (id, fields) ->
            assertSameFields(id, frozen.getValue(id).jsonObject.mapValues { it.value.jsonPrimitive.content },
                fields.jsonObject.mapValues { it.value.jsonPrimitive.content })
        }
        Unit
    }

    private fun assertSameFields(id: String, expected: Map<String, String>, actual: Map<String, String>) {
        val changed = ParseParity.differences(expected, actual)
        assertTrue("$id changed fields (values masked): ${changed.joinToString()}", changed.isEmpty())
    }

    private suspend fun snapshot(world: DashboardLedgerWorld, scenario: GoldenLedgerScenario): Map<String, String> = buildMap {
        val projection = GoldenLedgerRunner.read(world, scenario)
        assertTrue("Independent financial oracle failed: ${scenario.id}", projection.matches(scenario.expected))
        put("dashboard", ParseParity.digest(projection.fingerprint()))
        for (id in world.rawRepo.listIdsByReceivedAt()) {
            putAll(ParseParity.fields("raw.$id", world.rawRepo.getById(id)))
        }
        for (row in world.parsedRepo.listAll()) {
            val prefix = "parsed.${row.event.rawSmsId}"
            putAll(ParseParity.fields("$prefix.event", row.event))
            putAll(ParseParity.fields("$prefix.details", row.details))
            put("$prefix.corrected", row.userCorrected.toString())
            put("$prefix.automationConfirmed", row.automationConfirmed.toString())
        }
        for (row in world.ftRepo.listAll()) {
            putAll(ParseParity.fields("transaction.${row.id}", row))
            put("transaction.${row.id}.links", ParseParity.digest(world.ftRepo.listRawSmsIds(row.id).sorted()))
        }
        for (row in world.reviewRepo.listAll()) putAll(ParseParity.fields("review.${row.id}", row))
        // Fresh registries allocate random UUIDs. Compare their bank-qualified facts across worlds,
        // and assert the exact allocated IDs remain unchanged within each world on repeat import.
        put("accounts", ParseParity.digest(world.accounts.listAll().map { it.copy(id = "") }.sortedBy { it.toString() }))
        put("cards", ParseParity.digest(world.cards.listAll().map { it.copy(id = "") }.sortedBy { it.toString() }))
        put("loans", ParseParity.digest(world.loans.listAll().sortedBy { it.toString() }))
    }

    private suspend fun registryIds(world: DashboardLedgerWorld): List<String> =
        (world.accounts.listAll().map { it.id } + world.cards.listAll().map { it.id } + world.loans.listAll().map { it.id }).sorted()
}
