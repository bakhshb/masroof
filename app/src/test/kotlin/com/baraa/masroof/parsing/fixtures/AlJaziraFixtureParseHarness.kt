package com.baraa.masroof.parsing.fixtures

import com.baraa.masroof.testsupport.detailsOrNull
import com.baraa.masroof.testsupport.eventOrNull
import com.baraa.masroof.testsupport.EngineFixtureParser
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.time.Instant

/**
 * Parses on-disk Bank AlJazira fixtures through the production pipeline for
 * dashboard and integration characterization tests.
 */
object AlJaziraFixtureParseHarness {
    private val pipeline = EngineFixtureParser()

    fun load(id: String): AlJaziraFixture =
        AlJaziraFixtureLoader.loadAllFromClasspath().first { it.id == id }

    fun parseRecord(
        fixture: AlJaziraFixture,
        receivedAt: Instant = Instant.parse("2026-08-10T00:00:00Z"),
    ): ParsedEventRecord {
        val (event, details) = unpack(
            fixture = fixture,
            result = pipeline.parse(
                SmsParseInput(
                    rawSmsId = fixture.id,
                    sender = fixture.sender,
                    body = fixture.body,
                    receivedAt = receivedAt,
                ),
            ),
        )
        requireNotNull(event) { "fixture ${fixture.id} did not produce a ParsedEvent: $fixture" }
        return ParsedEventRecord(event = event, details = details ?: ParsedEventDetails())
    }

    fun parseRecord(
        id: String,
        receivedAt: Instant = Instant.parse("2026-08-10T00:00:00Z"),
    ): ParsedEventRecord = parseRecord(load(id), receivedAt)

    private fun unpack(fixture: AlJaziraFixture, result: ParseResult): Pair<ParsedEvent?, ParsedEventDetails?> =
        when (result) {
            is ParseResult.Invalid -> result.draft?.let {
                runCatching {
                    it.copy(parseStatus = it.parseStatus ?: ParseStatus.INVALID)
                        .toParsedEvent("evt-${fixture.id}")
                }.getOrNull()
            } to result.draft?.details
            else -> result.eventOrNull() to result.detailsOrNull()
        }
}
