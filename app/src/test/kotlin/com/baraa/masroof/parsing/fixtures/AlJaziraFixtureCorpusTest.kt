package com.baraa.masroof.parsing.fixtures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlJaziraFixtureCorpusTest {

    private val fixtures by lazy { AlJaziraFixtureLoader.loadAllFromClasspath() }

    @Test
    fun corpus_loadsSuccessfully() {
        assertTrue("expected fixtures", fixtures.size >= 20)
    }

    @Test
    fun everyFixture_hasUniqueId() {
        val ids = fixtures.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    fun everyFixture_hasValidStructuredMetadata() {
        val allowedFamilies = setOf(
            "PURCHASE", "TRANSFER_IN", "TRANSFER_OUT", "CARD_PAYMENT", "BILL_PAYMENT",
            "FINANCING_INSTALLMENT", "WITHDRAWAL", "REFUND", "FEE", "BALANCE_NOTICE", "OTP",
            "NON_FINANCIAL", "UNKNOWN",
        )
        val allowedStatuses = setOf(
            "SUCCESS", "PARTIAL", "REVIEW_REQUIRED", "NON_FINANCIAL", "UNSUPPORTED", "INVALID",
        )
        val allowedChannels = setOf("POS", "ONLINE", "UNKNOWN")
        val allowedNetworks = setOf("INTRA_BANK", "INTER_BANK", "UNKNOWN")
        val allowedCardSmsChannels = setOf("DEBIT", "CREDIT", "STATEMENT")
        val allowedLoanTypes = setOf("PERSONAL", "AUTO", "MORTGAGE")

        fixtures.forEach { fixture ->
            assertTrue(fixture.id.isNotBlank())
            assertTrue(fixture.sender.isNotBlank())
            assertTrue(fixture.body.isNotBlank())
            assertEquals("BANK_ALJAZIRA", fixture.expected.bank)
            assertTrue(fixture.expected.messageFamily in allowedFamilies)
            assertTrue(fixture.expected.parseStatus in allowedStatuses)
            fixture.expected.purchaseChannel?.let { assertTrue(it in allowedChannels) }
            fixture.expected.bankNetworkType?.let { assertTrue(it in allowedNetworks) }
            fixture.expected.cardSmsChannel?.let { assertTrue(it in allowedCardSmsChannels) }
            fixture.expected.loanType?.let { assertTrue(it in allowedLoanTypes) }
        }
    }

    @Test
    fun noFixture_encodesOwnershipOrSelfTransferExpectations() {
        fixtures.forEach { fixture ->
            val expectedKeys = fixture.expected.toString().lowercase()
            assertFalse(expectedKeys.contains("self_transfer"))
            assertFalse(expectedKeys.contains("ownership"))
            assertFalse(expectedKeys.contains("external_transfer_in"))
            assertFalse(expectedKeys.contains("financialtransactiontype"))
        }
    }

    @Test
    fun criticalRegressionShapes_arePresent() {
        val ids = fixtures.map { it.id }.toSet()
        assertTrue(ids.contains("purchase_pos_ar_cc_001"))
        assertTrue(ids.contains("purchase_online_ar_001"))
        assertTrue(ids.contains("transfer_out_inter_ar_001"))
        assertTrue(ids.contains("transfer_in_intra_ar_001"))
        assertTrue(ids.contains("purchase_pos_ar_debit_001"))
        assertTrue(ids.contains("transfer_in_salary_ar_001"))
        assertTrue(ids.contains("financing_installment_ar_001"))
        assertTrue(ids.contains("statement_ar_001"))
        assertTrue(ids.contains("otp_ar_001"))

        val ccPurchase = fixtures.first { it.id == "purchase_pos_ar_cc_001" }
        assertEquals("178.02", ccPurchase.expected.amount)
        assertEquals("7271", ccPurchase.expected.cardLast4)
        assertEquals("POS", ccPurchase.expected.purchaseChannel)
        assertEquals("18346.84", ccPurchase.expected.availableBalance)
        assertEquals("802.62", ccPurchase.expected.outstandingBalance)

        val onlineAr = fixtures.first { it.id == "purchase_online_ar_001" }
        assertEquals("51.99", onlineAr.expected.amount)
        assertEquals("7271", onlineAr.expected.cardLast4)
        assertEquals("17230.03", onlineAr.expected.availableBalance)

        val outgoing = fixtures.first { it.id == "transfer_out_inter_ar_001" }
        assertEquals("3002", outgoing.expected.sourceAccountLast4)
        assertEquals("0593", outgoing.expected.destinationAccountLast4)
        assertEquals("TEST_REFERENCE_1", outgoing.expected.transactionReference)
        assertTrue(outgoing.expected.sourceAccountLast4 != outgoing.expected.destinationAccountLast4)

        val incomingIntra = fixtures.first { it.id == "transfer_in_intra_ar_001" }
        assertEquals("TRANSFER_IN", incomingIntra.expected.messageFamily)
        assertEquals("INTRA_BANK", incomingIntra.expected.bankNetworkType)

        val bill = fixtures.first { it.id == "bill_payment_ar_001" }
        assertEquals("TEST_BILLER", bill.expected.biller)
        assertEquals(null, bill.expected.merchant)

        val otp = fixtures.first { it.id == "otp_ar_001" }
        assertEquals("OTP", otp.expected.messageFamily)
        assertEquals("NON_FINANCIAL", otp.expected.parseStatus)
    }

    @Test
    fun classificationCollisionFixtures_arePresent() {
        val byId = fixtures.associateBy { it.id }
        val required = mapOf(
            "collision_transfer_fee_line_ar_001" to "TRANSFER_OUT",
            "collision_titled_fee_transfer_ar_001" to "UNKNOWN",
            "collision_transfer_in_and_out_ar_001" to "UNKNOWN",
            "collision_otp_online_purchase_en_001" to "OTP",
            "collision_otp_online_purchase_ar_001" to "OTP",
            "collision_statement_amount_ar_001" to "NON_FINANCIAL",
            "collision_purchase_statement_due_ar_001" to "UNKNOWN",
            "collision_transfer_beneficiary_notice_ar_001" to "UNKNOWN",
            "collision_refund_pos_purchase_ar_001" to "REFUND",
            "collision_intra_generic_labels_ar_001" to "TRANSFER_OUT",
            "collision_intra_generic_labels_ar_002" to "TRANSFER_OUT",
        )
        required.forEach { (id, family) ->
            val fixture = byId[id]
            assertNotNull(id, fixture)
            assertEquals(id, family, fixture!!.expected.messageFamily)
        }
        required.filterValues { it == "UNKNOWN" }.keys.forEach { id ->
            assertEquals(id, "REVIEW_REQUIRED", byId.getValue(id).expected.parseStatus)
        }
    }

    @Test
    fun fixtureSchema_canExpressDashboardParseFacts() {
        val salary = fixtures.first { it.id == "transfer_in_salary_ar_001" }
        assertEquals(true, salary.expected.salaryIncomeWording)

        val financing = fixtures.first { it.id == "financing_installment_ar_001" }
        assertEquals("PERSONAL", financing.expected.loanType)

        val debitPos = fixtures.first { it.id == "purchase_pos_ar_debit_001" }
        assertEquals("DEBIT", debitPos.expected.cardSmsChannel)
        assertEquals("3001", debitPos.expected.debitSourceAccountLast4)

        val statement = fixtures.first { it.id == "statement_ar_001" }
        assertEquals("STATEMENT", statement.expected.cardSmsChannel)
        assertEquals("2026-08-15", statement.expected.paymentDueDate)
    }

    @Test
    fun fixtureSchema_canExpressExtendedExtractedFields() {
        assertNotNull(fixtures.first { it.id == "transfer_out_inter_ar_002" }.expected.transactionReference)
        assertNotNull(fixtures.first { it.id == "purchase_online_en_cc_001" }.expected.availableBalance)
        assertNotNull(fixtures.first { it.id == "purchase_online_en_cc_001" }.expected.outstandingBalance)
        assertNotNull(fixtures.first { it.id == "bill_payment_ar_001" }.expected.biller)
    }

    @Test
    fun fixturePaths_matchMessageFamilyCategory() {
        val root = AlJaziraFixtureLoader.resolveTestdataRoot()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "json" && "reference" !in it.path }
            .forEach { file ->
                val fixture = kotlinx.serialization.json.Json.decodeFromString(
                    AlJaziraFixture.serializer(),
                    file.readText(),
                )
                val relative = file.relativeTo(root).invariantSeparatorsPath
                when (fixture.expected.messageFamily) {
                    "PURCHASE" -> assertTrue(relative.startsWith("purchase/"))
                    "TRANSFER_IN" -> assertTrue(relative.startsWith("transfer_in/"))
                    "TRANSFER_OUT" -> assertTrue(relative.startsWith("transfer_out/"))
                    "CARD_PAYMENT" -> assertTrue(relative.startsWith("card_payment/"))
                    "BILL_PAYMENT" -> assertTrue(relative.startsWith("bill_payment/"))
                    "FINANCING_INSTALLMENT" -> assertTrue(relative.startsWith("financing_installment/"))
                    "REFUND" -> assertTrue(relative.startsWith("refund/"))
                    "WITHDRAWAL" -> assertTrue(relative.startsWith("withdrawal/"))
                    "FEE" -> assertTrue(relative.startsWith("fee/"))
                    "OTP" -> assertTrue(relative.startsWith("otp/"))
                    "BALANCE_NOTICE" -> assertTrue(relative.startsWith("balance_notice/"))
                    "NON_FINANCIAL" -> assertTrue(relative.startsWith("non_financial/"))
                    "UNKNOWN" -> assertTrue(relative.startsWith("unknown/"))
                }
            }
    }
}
