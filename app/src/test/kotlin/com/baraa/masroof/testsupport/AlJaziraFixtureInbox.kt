package com.baraa.masroof.testsupport

import com.baraa.masroof.bank.aljazira.AlJaziraParsingPipeline
import com.baraa.masroof.parsing.fixtures.AlJaziraFixtureLoader
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.sms.model.ProviderSmsRecord
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Every on-disk AlJazira fixture as one provider inbox. */
object AlJaziraFixtureInbox {
    private val zone: ZoneId = ZoneId.of("Asia/Riyadh")
    private val base: Instant = Instant.parse("2026-06-01T00:00:00Z")

    /**
     * Rows are ordered by their parsed local time (falling back to a fixed base) so
     * transfer legs land together like a real inbox.
     */
    fun rows(): List<ProviderSmsRecord> =
        AlJaziraFixtureLoader.loadAllFromClasspath()
            .sortedBy { it.id }
            .mapIndexed { index, fixture ->
                val local = occurredAtLocal(fixture.sender, fixture.body)
                val receivedAt = (local?.atZone(zone)?.toInstant() ?: base).plusSeconds(index.toLong())
                ProviderSmsRecord("fx-$index", fixture.sender, fixture.body, receivedAt)
            }
            .sortedBy { it.receivedAt }

    private fun occurredAtLocal(sender: String, body: String): LocalDateTime? {
        val result = AlJaziraParsingPipeline().parse(
            SmsParseInput("probe", sender, body, Instant.parse("2026-08-11T00:00:00Z")),
        )
        val details = when (result) {
            is ParseResult.Success -> result.details
            is ParseResult.Partial -> result.details
            is ParseResult.ReviewRequired -> result.details
            is ParseResult.NonFinancial -> result.details
            is ParseResult.Unsupported, is ParseResult.Invalid -> null
        }
        return details?.occurredAtLocal
    }
}
