package com.baraa.masroof.integration.replica

import android.content.Context
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.sms.datasource.InboxRow
import com.baraa.masroof.sms.datasource.SmsDataSource
import com.baraa.masroof.sms.model.ProviderSmsRecord
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Synthetic AlJazira history dated today. `referenceFixtureText` reads a fixture by name: from assets on a device, from testdata on the JVM. */
internal object ReferenceHistory {
    fun fixture(name: String): JsonObject = Json.parseToJsonElement(referenceFixtureText(name)).jsonObject
    val names = listOf("transfer_in_salary_ar_001","purchase_pos_ar_debit_001","purchase_online_ar_001",
        "financing_installment_ar_001","collision_transfer_fee_line_ar_001","fee_ar_001","card_payment_ar_001")
    fun period() = FinancialPeriod(LocalDate.now().minusMonths(1).withDayOfMonth(1),LocalDate.now().plusMonths(1).withDayOfMonth(1))
    val reported = (1..5).map { "reported_" + it.toString().padStart(2,'0') }
    fun source(context: Context, names: List<String> = this.names): SmsDataSource {
        // The setup journeys open the current salary period, including its first day.
        val today = LocalDate.now()
        val records = names.mapIndexed { index,name ->
            val json = fixture(name)
            var body = json.getValue("body").jsonPrimitive.content
            body = body.replace(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"),today.toString())
                .replace(Regex("[0-9]{2}-[0-9]{2}-[0-9]{4}"),today.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")))
            ProviderSmsRecord("replica-history-$index",json.getValue("sender").jsonPrimitive.content,body,today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().plusSeconds(index.toLong()))
        }
        return object : SmsDataSource {
            override suspend fun <T> withInbox(receivedAfter: Instant?, block: suspend (Sequence<InboxRow>) -> T): T = block(queryInbox(receivedAfter))
            fun queryInbox(receivedAfter: Instant?) = records.asSequence().filter { receivedAfter == null || !it.receivedAt.isBefore(receivedAfter) }.map { InboxRow.Valid(it) }
        }
    }
}
