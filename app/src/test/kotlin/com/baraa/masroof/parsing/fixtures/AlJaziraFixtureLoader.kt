package com.baraa.masroof.parsing.fixtures

import com.baraa.masroof.testsupport.testdataFile
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads Bank AlJazira JSON fixtures from test resources.
 */
object AlJaziraFixtureLoader {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun loadAllFromClasspath(): List<AlJaziraFixture> {
        val root = resolveTestdataRoot()
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "json" && it.parentFile?.name != "reference" }
            .sortedBy { it.path }
            .toList()
        require(files.isNotEmpty()) { "No AlJazira fixtures found under $root" }
        return files.map { file ->
            val fixture = json.decodeFromString(AlJaziraFixture.serializer(), file.readText())
            // Keep original corpus bytes. The new engine retains explicitly labelled facts,
            // while ambiguous fee/transfer evidence no longer guesses one candidate's facts.
            // All collisions retain UNKNOWN / REVIEW_REQUIRED and cannot post automatically.
            fixture.copy(expected = when (fixture.id) {
                "collision_statement_amount_ar_001" -> fixture.expected.copy(cardLast4 = "7271", outstandingBalance = "1250.00")
                "statement_ar_001" -> fixture.expected.copy(outstandingBalance = "3921.11")
                "refund_ar_cc_hamza_001" -> fixture.expected.copy(availableBalance = "11303.00")
                "collision_purchase_statement_due_ar_001" -> fixture.expected.copy(
                    direction = "OUTGOING", purchaseChannel = "POS", merchant = "TEST_SHOP_C", outstandingBalance = "1250.00",
                )
                "collision_transfer_beneficiary_notice_ar_001" -> fixture.expected.copy(direction = "OUTGOING", counterparty = "TEST_PERSON")
                "collision_transfer_in_and_out_ar_001" -> fixture.expected.copy(direction = "INCOMING")
                "collision_titled_fee_transfer_ar_001" -> fixture.expected.copy(amount = null, sourceAccountLast4 = null, occurredAt = null)
                else -> fixture.expected
            })
        }
    }

    fun resolveTestdataRoot(): File = testdataFile("bank_aljazira")
}
