package com.baraa.masroof.lab.beta.verification

import android.content.Context
import androidx.room.Room
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.application.ManualAssetDraft
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.data.BetaDatabase
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import java.time.*

/** Reported layouts, with names/identifiers redacted. No production records or phone inbox reads. */
class BetaReportedMessagesChecks(private val context: Context) {
    private val bank=BankId("BANK_ALJAZIRA")
    fun allReportedLayoutsPostAfterSetup(upgrade: Boolean) {
        BetaContainer.resetOffline(context)
        try {
            val ids=BetaContainer(context).use {beta ->
                beta.acknowledgeStart();beta.setBankEnabled(bank,true)
                beta.saveManualAsset(ManualAssetDraft(AssetId(Environment.BETA,bank,"reported-account"),AssetKind.ACCOUNT,"3001",ownedConfirmed=true))
                beta.saveManualAsset(ManualAssetDraft(AssetId(Environment.BETA,bank,"reported-card"),AssetKind.CREDIT_CARD,"7271",
                    parentId=AssetId(Environment.BETA,bank,"reported-facility"),role=CreditCardRole.PRIMARY,ownedConfirmed=true,createCreditFacility=true))
                val bodies=listOf(
                    purchase("60.99","1.40","Microsoft*Xbox Game Pass","13:57","11691.77"),
                    purchase("57.99","1.33","Google YouTubePremium","00:29","12109.99"),
                    transfer("50.00","9466","بنك اس تي سي","14:57 08-10-2026","ONE"),
                    transfer("800.00","3673","بنك الراجحي","07:58 27-09-2026","TWO"),
                    "خصم: قسط تمويل\nمن: 3001\nالقسط: SAR 3,036.11\nالمبلغ المتبقي: SAR 30,361.14\nلـ: تمويل شخصي\nفي: 00:40 27-09-2026")
                bodies.mapIndexed {index,body ->
                    (beta.importEvidence(EvidenceRequest("reported",EvidenceOrigin.EXPLICIT_HISTORY,setOf(bank)),listOf(EvidenceMessage("reported-$index","AlJazira",body,Instant.parse("2026-10-08T12:00:00Z").plusSeconds(index.toLong()),ReceiptTimeSource.PROVIDER_TIMESTAMP))).single() as EvidenceResult.Stored).summary.id
                }
            }
            if(upgrade) {
                val db=Room.databaseBuilder(context,BetaDatabase::class.java,BetaDatabase.NAME).build()
                try {runBlocking {ids.forEach {id ->
                    val archive=JSONObject(requireNotNull(db.evidenceDao().outcome(id)).candidateJson).put("engineVersion","bank-rules-independent-v8").put("status","REVIEW_REQUIRED")
                    db.openHelper.writableDatabase.execSQL("UPDATE beta_parsed_event SET candidateJson=?,disposition='REVIEW' WHERE rawId=?",arrayOf(archive.toString(),id))
                }}} finally {db.close()}
            }
            BetaContainer(context).use {beta ->
                beta.processAutomaticEvidence()
                assertTrue(ids.mapIndexed {i,id -> val s=beta.evidenceSummary(id); "$i:${s.disposition}:${s.reasonCodes}:${s.financialReviewReason}"}.joinToString(),beta.reviewInboxPage().rows.isEmpty())
                assertEquals(0,beta.evidenceInboxCounts().needsReview)
                val expected=listOf(FinancialType.EXPENSE,FinancialType.EXPENSE,FinancialType.EXTERNAL_TRANSFER_OUT,FinancialType.EXTERNAL_TRANSFER_OUT,FinancialType.LOAN_REPAYMENT)
                val amounts=listOf("60.99","57.99","50.00","800.00","3036.11")
                ids.forEachIndexed {i,id ->
                    val row=requireNotNull(beta.financialPosting(PostingKey(Environment.BETA,"evidence:$id")))
                    assertEquals(expected[i],row.command.type)
                    assertEquals(0,row.command.amount.amount.compareTo(amounts[i].toBigDecimal()))
                }
                val query=DashboardQuery(Environment.BETA,Instant.parse("2026-09-26T21:00:00Z"),Instant.parse("2026-10-26T21:00:00Z"),ZoneId.of("Asia/Riyadh"),Currency.SAR)
                val periods=com.baraa.masroof.lab.finance.dashboard.producer.DashboardPeriods(
                    salary=DisplayPeriod(query.from,query.until,query.zone,PeriodKind.SALARY,null))
                val snapshot=beta.dashboard(query,periods).snapshot
                assertEquals(5,snapshot.transactions.size)
                assertEquals(0,requireNotNull(snapshot.creditFacilities.single().salarySpending).amount.compareTo("118.98".toBigDecimal()))
                assertNull(snapshot.creditFacilities.single().statementDue) // A purchase due observation is not an authoritative statement.
                assertEquals(listOf("1.33","1.4"),snapshot.transactions.mapNotNull {it.internationalFee?.amount?.stripTrailingZeros()?.toPlainString()}.sorted())
                assertEquals(0,requireNotNull(snapshot.accounts.single().flow).totalOut.amount.compareTo("3886.11".toBigDecimal()))
                assertEquals(0,requireNotNull(snapshot.loans.single().remaining).amount.compareTo("30361.14".toBigDecimal()))
                assertEquals(0,requireNotNull(snapshot.loans.single().latestInstallment).amount.compareTo("3036.11".toBigDecimal()))
                assertTrue(beta.assets().none {it.last4 in setOf("9466","3673")})
                // A credit purchase does not prove which bank account should receive manual income.
                assertNull(betaReviewDefaultEndpoints(beta.reviewDetail(ids.first()),FinancialType.INCOME).second)
                beta.processAutomaticEvidence()
                assertEquals(5,beta.dashboard(query).snapshot.transactions.size)
            }
        } finally {BetaContainer.resetOffline(context)}
    }
    fun ignoredMessageCanBeRestoredWithExplicitClassification() {
        BetaContainer.resetOffline(context)
        try {BetaContainer(context).use {beta ->
            beta.acknowledgeStart();beta.setBankEnabled(bank,true)
            val account=beta.saveManualAsset(ManualAssetDraft(AssetId(Environment.BETA,bank,"restore-account"),AssetKind.ACCOUNT,"3001",ownedConfirmed=true))
            beta.saveManualAsset(ManualAssetDraft(AssetId(Environment.BETA,bank,"unrelated-loan"),AssetKind.LOAN,"",ownedConfirmed=true,
                metadata=com.baraa.masroof.lab.beta.application.BetaAssetMetadata(loanType="PERSONAL")))
            val id=(beta.importEvidence(EvidenceRequest("restore",EvidenceOrigin.EXPLICIT_HISTORY,setOf(bank)),listOf(EvidenceMessage("restore","AlJazira",
                "خصم: قسط تمويل\nمن: 3001\nالقسط: SAR 20\nلـ: تمويل\nفي: 2026-10-18 01:10",Instant.parse("2026-10-17T22:11:00Z"),ReceiptTimeSource.PROVIDER_TIMESTAMP))).single() as EvidenceResult.Stored).summary.id
            beta.processAutomaticEvidence();beta.resolveReviewAsNonFinancial(id)
            assertEquals(1,beta.reviewInboxPage(ignored=true).rows.size)
            val preview=requireNotNull(beta.financialApprovalPreview(id))
            val detail=beta.reviewDetail(id)
            assertNull(betaReviewDefaultEndpoints(detail,FinancialType.LOAN_REPAYMENT).second) // A sole unrelated loan is not a proved destination.
            val endpoints=betaReviewDefaultEndpoints(detail,FinancialType.FEE)
            assertEquals(betaAssetChoiceKey(account.id),endpoints.first)
            val request=requireNotNull(betaApprovalDraft(preview,FinancialType.FEE.name,endpoints.first,endpoints.second,null))
            val faultDb=Room.databaseBuilder(context,BetaDatabase::class.java,BetaDatabase.NAME).build()
            try {
                faultDb.openHelper.writableDatabase.execSQL("CREATE TRIGGER restore_classification_failure BEFORE INSERT ON beta_financial_transaction BEGIN SELECT RAISE(ABORT,'synthetic'); END")
                assertTrue(runCatching {beta.restoreReviewAsFinancial(request)}.isFailure)
                assertEquals(request,beta.financialApprovalPreview(id)?.pending)
                assertNull(beta.financialPosting(PostingKey(Environment.BETA,"evidence:$id")))
            } finally {
                faultDb.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS restore_classification_failure");faultDb.close()
            }
            assertTrue(beta.retryFinancialApproval(id,request) is BetaFinancialAdoptionResult.Posted)
            beta.processAutomaticEvidence()
            assertTrue(beta.reviewInboxPage(ignored=true).rows.isEmpty());assertTrue(beta.reviewInboxPage().rows.isEmpty())
            assertEquals(FinancialType.FEE,beta.financialPosting(PostingKey(Environment.BETA,"evidence:$id"))?.command?.type)
        }} finally {BetaContainer.resetOffline(context)}
    }

    fun previousEngineKeepsManualClassificationAndSuppression(failFirst: Boolean = false) {
        BetaContainer.resetOffline(context)
        try {
            val ids=BetaContainer(context).use {beta ->
                beta.acknowledgeStart();beta.setBankEnabled(bank,true)
                val account=beta.saveManualAsset(ManualAssetDraft(AssetId(Environment.BETA,bank,"manual-upgrade-account"),AssetKind.ACCOUNT,"3001",ownedConfirmed=true))
                val ids=(0..2).map {i -> (beta.importEvidence(EvidenceRequest("manual-upgrade",EvidenceOrigin.EXPLICIT_HISTORY,setOf(bank)),listOf(EvidenceMessage("manual-$i","AlJazira",
                    "Cash withdrawal\nFrom account: 3001\nAmount: ${20+i} SAR\nDate: 2026-10-18 12:0$i",Instant.parse("2026-10-18T09:03:00Z").plusSeconds(i.toLong()),ReceiptTimeSource.PROVIDER_TIMESTAMP))).single() as EvidenceResult.Stored).summary.id}
                val preview=requireNotNull(beta.financialApprovalPreview(ids[0]))
                assertTrue(beta.approveEvidenceFinancial(requireNotNull(betaApprovalDraft(preview,FinancialType.FEE.name,betaAssetChoiceKey(account.id),"external",null))) is BetaFinancialAdoptionResult.Posted)
                beta.processAutomaticEvidence()
                val second=requireNotNull(beta.transactionActionDetail("evidence:${ids[1]}"))
                assertTrue(beta.reclassifyTransaction(second.token,"manual-refund",FinancialType.REFUND))
                val third=requireNotNull(beta.transactionActionDetail("evidence:${ids[2]}"))
                assertTrue(beta.saveReview(third.token,"manual-ignore",com.baraa.masroof.lab.finance.corrections.FinancialDecision.SuppressAsNonFinancial) is com.baraa.masroof.lab.beta.application.corrections.BetaCorrectionResult.Applied)
                ids
            }
            val db=Room.databaseBuilder(context,BetaDatabase::class.java,BetaDatabase.NAME).build()
            try {runBlocking {ids.forEach {id ->
                val archive=JSONObject(requireNotNull(db.evidenceDao().outcome(id)).candidateJson).put("engineVersion","bank-rules-independent-v8")
                db.openHelper.writableDatabase.execSQL("UPDATE beta_parsed_event SET candidateJson=? WHERE rawId=?",arrayOf(archive.toString(),id))
            }}} finally {db.close()}
            assertTrue(context.getSharedPreferences(BetaEnvironment.PREFS_NAME,Context.MODE_PRIVATE).edit().putString("beta_auto_complete_v1","previous-engine").putString("beta_reinterpret_complete","previous-engine").commit())
            if(failFirst) {
                val faultDb=Room.databaseBuilder(context,BetaDatabase::class.java,BetaDatabase.NAME).build()
                try {
                    faultDb.openHelper.writableDatabase.execSQL("CREATE TRIGGER manual_upgrade_failure BEFORE UPDATE ON beta_financial_transaction BEGIN SELECT RAISE(ABORT,'synthetic'); END")
                    BetaContainer(context).use {beta ->
                        assertTrue(runCatching {beta.processAutomaticEvidence()}.isFailure)
                        assertNull(beta.financialPosting(PostingKey(Environment.BETA,"evidence:${ids[0]}")))
                    }
                } finally {faultDb.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS manual_upgrade_failure");faultDb.close()}
            }
            BetaContainer(context).use {beta ->
                beta.processAutomaticEvidence()
                assertEquals(FinancialType.FEE,beta.financialPosting(PostingKey(Environment.BETA,"evidence:${ids[0]}"))?.command?.type)
                assertEquals(FinancialType.REFUND,beta.financialPosting(PostingKey(Environment.BETA,"evidence:${ids[1]}"))?.command?.type)
                assertNull(beta.financialPosting(PostingKey(Environment.BETA,"evidence:${ids[2]}")))
                assertTrue(beta.reviewInboxPage().rows.isEmpty())
                assertEquals(ids[2],beta.reviewInboxPage(ignored=true).rows.single().id)
                val sizes=ids.map {beta.financialHistory(PostingKey(Environment.BETA,"evidence:$it")).size}
                beta.processAutomaticEvidence()
                assertEquals(sizes,ids.map {beta.financialHistory(PostingKey(Environment.BETA,"evidence:$it")).size})
            }
        } finally {BetaContainer.resetOffline(context)}
    }

    fun correctionReviewCanBeIgnoredAndResolved() {
        BetaContainer.resetOffline(context)
        try {BetaContainer(context).use {beta ->
            beta.acknowledgeStart();beta.setBankEnabled(bank,true)
            beta.saveManualAsset(ManualAssetDraft(AssetId(Environment.BETA,bank,"correction-review-account"),AssetKind.ACCOUNT,"3001",ownedConfirmed=true))
            val id=(beta.importEvidence(EvidenceRequest("correction-review",EvidenceOrigin.EXPLICIT_HISTORY,setOf(bank)),listOf(EvidenceMessage("correction-review","AlJazira",
                "Cash withdrawal\nFrom account: 3001\nAmount: 20 SAR\nDate: 2026-10-18 12:00",Instant.parse("2026-10-18T09:00:00Z"),ReceiptTimeSource.PROVIDER_TIMESTAMP))).single() as EvidenceResult.Stored).summary.id
            beta.processAutomaticEvidence()
            val key=PostingKey(Environment.BETA,"evidence:$id")
            val detail=requireNotNull(beta.transactionActionDetail(key.transactionId))
            val correction=beta.saveReview(detail.token,"incomplete-payment",com.baraa.masroof.lab.finance.corrections.FinancialDecision.ResolveFinancialType(FinancialType.CREDIT_CARD_PAYMENT))
            assertTrue(correction is com.baraa.masroof.lab.beta.application.corrections.BetaCorrectionResult.Applied)
            assertNull(beta.financialPosting(key))
            assertEquals(id,beta.reviewInboxPage().rows.single().id)
            assertEquals(1,beta.evidenceInboxCounts().needsReview)
            assertTrue(FinancialType.CASH_WITHDRAWAL in requireNotNull(beta.reviewDetail(id).correctionAction).allowedTypes)
            beta.resolveReviewAsNonFinancial(id)
            assertTrue(beta.reviewInboxPage().rows.isEmpty())
            assertEquals(0,beta.evidenceInboxCounts().needsReview)
            assertTrue(beta.reviewInboxPage(ignored=true).rows.single().financiallySuppressed)
            beta.resolveReviewAsNonFinancial(id,true,FinancialType.CASH_WITHDRAWAL)
            assertEquals(FinancialType.CASH_WITHDRAWAL,beta.financialPosting(key)?.command?.type)
            val again=requireNotNull(beta.transactionActionDetail(key.transactionId))
            beta.saveReview(again.token,"incomplete-payment-again",com.baraa.masroof.lab.finance.corrections.FinancialDecision.ResolveFinancialType(FinancialType.CREDIT_CARD_PAYMENT))
            val pending=requireNotNull(beta.transactionActionDetail(key.transactionId))
            assertTrue(beta.reclassifyTransaction(pending.token,"keep-original-type",FinancialType.CASH_WITHDRAWAL))
            assertEquals(FinancialType.CASH_WITHDRAWAL,beta.financialPosting(key)?.command?.type)
            assertTrue(beta.reviewInboxPage().rows.isEmpty())
        }} finally {BetaContainer.resetOffline(context)}
    }

    private fun purchase(amount: String,fee: String,merchant: String,time: String,balance: String)="شراء عبر الانترنت\nبطاقة ائتمانية: 7271\nمبلغ: SAR $amount\nلدى: $merchant\nفي: 2026-10-06 $time\nالدولة: USA\nرسوم العمليات الدولية: $fee\nسعر الصرف: 1.0\nالرصيد المتاح: SAR $balance\nإجمالي المستحق: SAR 6705.18"
    private fun transfer(amount: String,suffix: String,bank: String,time: String,reference: String)="عملية حوالة مالية صادرة مقبولة\nخصمت من حساب: 3001\nإلى: TEST BENEFICIARY\nمبلغ العملية: $amount SAR\nالمعرف البديل \\الايبان: $suffix\n[$bank]\nفي: $time\nرقم المعاملة: TEST_REFERENCE_$reference"
}
