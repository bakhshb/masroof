package com.baraa.masroof.lab.beta.verification

import android.content.Context
import androidx.room.Room
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.data.*
import com.baraa.masroof.lab.bank.interpretation.RuleInterpreter
import com.baraa.masroof.lab.finance.value.BankId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*

/** Test-source only, on isolated Robolectric/emulator fixtures; never user/device inbox data. */
object IndependentEngineChecks {
    private val at = Instant.parse("2026-10-18T00:00:00Z")
    private val request = EvidenceRequest("m6-independent", EvidenceOrigin.EXPLICIT_SAMPLE)
    private val body = "Credit card purchase\nCard: 1234\nAmount: 42 SAR\nMerchant: Example\n2026-10-17 12:00"
    private fun message(id: String, text: String = body) = EvidenceMessage(id, "AlJazira", text, at, ReceiptTimeSource.USER_SUPPLIED)

    fun currentOnlyAndDurable(context: Context) {
        BetaContainer.resetOffline(context)
        lateinit var id: String
        BetaContainer(context).use { beta ->
            beta.setBankEnabled(BankId("BANK_ALJAZIRA"), true)
            val result = beta.importEvidence(request, listOf(message("one"))).single() as EvidenceResult.Stored
            id = result.summary.id
            assertEquals(EvidenceProcessingState.INTERPRETED, result.summary.processingState)
            beta.decideEvidence(id, 0, EvidenceReviewDecision.KEEP_FOR_REVIEW)
            assertEquals(1, (beta.importEvidence(request, listOf(message("one"))).single() as EvidenceResult.Stored).summary.attempts)
            // A complete current archive can be previewed. It still does not post without an explicit approval.
            assertNull(beta.financialApprovalPreview(id)!!.blockedReason)
            assertNull(beta.financialPosting(RoomBetaFinancialAdoption.key(id)))
            beta.backup()
        }
        BetaContainer(context).use { beta ->
            assertEquals(1, beta.evidenceSummary(id).manualRevision)
            val repeated = beta.reprocessEvidence(id)
            assertEquals(2, repeated.attempts)
            assertEquals(1, repeated.manualRevision)
            assertTrue(beta.restore())
            assertEquals(1, beta.evidenceSummary(id).attempts)
        }
        val db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
        try { runBlocking {
            val row = db.evidenceDao().outcome(id)!!
            assertEquals("{}", row.baselineJson)
            val archive = JSONObject(row.candidateJson)
            assertEquals(RuleInterpreter.VERSION, archive.getString("engineVersion"))
            assertEquals("BANK_ENGINE", archive.getString("authority"))
            assertEquals("SUCCESS", archive.getString("status"))
            val spans = archive.getJSONArray("evidence")
            assertTrue(spans.length() > 0)
            for (i in 0 until spans.length()) {
                val span = spans.getJSONObject(i)
                assertTrue(span.getInt("start") >= 0 && span.getInt("end") <= body.length)
            }
            assertNull(db.financialAdoptionDao().evidence(id))
        } } finally { db.close(); BetaContainer.resetOffline(context) }
    }

    fun incompleteAndSecurityNeverPost(context: Context) {
        BetaContainer.resetOffline(context)
        BetaContainer(context).use { beta ->
            beta.setBankEnabled(BankId("BANK_ALJAZIRA"), true)
            val results = beta.importEvidence(request, listOf(message("incomplete", "Purchase\nCard: 1234\nAmount: 42 SAR"),
                message("otp", "OTP code 123456 Purchase Amount: 42 SAR"),
                message("transfer", "Outgoing transfer\nFrom account: 1234\nTo account: 5678\nDestination bank: Bank AlJazira\nAmount: 42 SAR\n2026-10-17 12:00")))
            val summaries = results.map { (it as EvidenceResult.Stored).summary }
            assertEquals(EvidenceProcessingState.REVIEW, summaries[0].processingState)
            assertEquals(EvidenceProcessingState.INTERPRETED, summaries[2].processingState)
            summaries.forEach { assertNull(beta.financialPosting(RoomBetaFinancialAdoption.key(it.id))) }
            assertTrue(beta.assets().isEmpty())
        }
        BetaContainer.resetOffline(context)
    }

    fun historicalRowsRemainInert(context: Context) {
        BetaContainer.resetOffline(context)
        val db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
        val oldId = "historical-before-m6"
        try { runBlocking {
            val store = RoomBetaEvidenceStore(db)
            val route = EvidenceRoute(RouteKind.MATCHED, setOf(BankId("BANK_ALJAZIRA")), "historical-router")
            store.capture(CapturedEvidence(oldId, request, message("old", "historical immutable raw"), at, route, emptyList()))
            store.begin(oldId, at)
            store.complete(oldId, route, EvidenceComparison(EvidenceDisposition.COMPARISON_ONLY, emptySet(), "{\"historical\":1}", "{\"historical\":2}"), at)
            store.decide(oldId, 0, EvidenceReviewDecision.KEEP_FOR_REVIEW, at)
            val oldRaw = db.evidenceDao().raw(oldId)
            val oldOutcome = db.evidenceDao().outcome(oldId)
            val oldDecision = db.evidenceDao().decision(oldId)
            BetaContainer(context).use { beta ->
                beta.putMarker("m6-preserve", "kept")
                beta.putPreferenceProbe("kept")
                beta.setBankEnabled(BankId("BANK_ALJAZIRA"), true)
                beta.importEvidence(request, listOf(message("new")))
            }
            BetaContainer(context).use { beta ->
                assertEquals(1, beta.markerCount())
                assertEquals("kept", beta.preferenceProbe())
                assertEquals(1, beta.evidenceSummary(oldId).manualRevision)
            }
            assertEquals(oldRaw, db.evidenceDao().raw(oldId))
            assertEquals(oldOutcome, db.evidenceDao().outcome(oldId))
            assertEquals(oldDecision, db.evidenceDao().decision(oldId))
            assertNull(db.financialAdoptionDao().evidence(oldId))
        } } finally { db.close(); BetaContainer.resetOffline(context) }
    }
}
