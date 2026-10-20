package com.baraa.masroof.lab.beta.verification

import android.content.Context
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import org.junit.Assert.*
import java.time.*

class BetaAssetTrackingChecks(private val context: Context) {
    private val bank=BankId("BANK_ALJAZIRA")
    private val query=DashboardQuery(Environment.BETA,Instant.parse("2026-10-01T00:00:00Z"),Instant.parse("2026-11-01T00:00:00Z"),ZoneId.of("Asia/Riyadh"),Currency.SAR)
    private fun id(value: String)=AssetId(Environment.BETA,bank,value)
    private fun capture(beta: BetaContainer,name: String,body: String)=
        (beta.importEvidence(EvidenceRequest(name,EvidenceOrigin.EXPLICIT_SAMPLE),listOf(EvidenceMessage(name,"AlJazira",body,
            Instant.parse("2026-10-18T09:02:00Z"),ReceiptTimeSource.USER_SUPPLIED))).single() as EvidenceResult.Stored).summary.id
    private fun tracked(beta: BetaContainer,id: AssetId)=beta.assetTrackingSnapshot().single {it.asset.id==id}
    private inline fun isolated(block: (BetaContainer)->Unit) {
        BetaContainer.resetOffline(context)
        try {BetaContainer(context).use {it.acknowledgeStart();it.setBankEnabled(bank,true);block(it)}} finally {BetaContainer.resetOffline(context)}
    }
    fun stoppedCardPurchasesStayExcludedAcrossImportReopenAndResume()=isolated {beta ->
        val card=id("card")
        beta.saveManualAsset(ManualAssetDraft(card,AssetKind.CREDIT_CARD,"7271",id("facility"),CreditCardRole.PRIMARY,true,createCreditFacility=true))
        val body="POS Purchase\nCard: 7271\nAt: TEST STORE\nAmount: 10 SAR\nAvailable balance: 200 SAR\nDate: 2026-10-18 12:00"
        val raw=capture(beta,"one",body);beta.processAutomaticEvidence()
        assertEquals(1,beta.dashboard(query).snapshot.transactions.size)
        val history=beta.financialHistory(PostingKey(Environment.BETA,"evidence:$raw")).size
        beta.setAssetTracked(tracked(beta,card),false);beta.processAutomaticEvidence()
        assertTrue(beta.dashboard(query).snapshot.transactions.isEmpty())
        assertTrue(beta.dashboard(query).snapshot.creditFacilities.isEmpty())
        assertEquals(0,beta.evidenceInboxCounts().needsReview)
        capture(beta,"later",body.replace("10 SAR","11 SAR").replace("12:00","12:01"));beta.processAutomaticEvidence()
        assertEquals(0,beta.evidenceInboxCounts().needsReview)
        BetaContainer(context).use {again -> again.processAutomaticEvidence();assertEquals(BetaOwnershipStatus.EXTERNAL,tracked(again,card).status);assertTrue(again.dashboard(query).snapshot.transactions.isEmpty())}
        beta.setAssetTracked(tracked(beta,card),true);beta.processAutomaticEvidence()
        assertEquals(2,beta.dashboard(query).snapshot.transactions.size)
        assertEquals(history,beta.financialHistory(PostingKey(Environment.BETA,"evidence:$raw")).size)
    }
    fun identityCollisionAndStaleActionsNeverChangeAnotherAsset()=isolated {beta ->
        val a=OwnedAsset(id("a"),AssetKind.ACCOUNT,"1234");val b=a.copy(id=id("b"));beta.registerAsset(a);beta.registerAsset(b)
        val raw=capture(beta,"ambiguous","Cash withdrawal\nFrom account: 1234\nAmount: 42 SAR\nDate: 2026-10-18 12:00")
        beta.processAutomaticEvidence();assertNull(beta.financialPosting(PostingKey(Environment.BETA,"evidence:$raw")))
        val expected=tracked(beta,a.id);beta.setAssetTracked(expected,false)
        assertThrows(IllegalStateException::class.java) {beta.setAssetTracked(expected,true)}
        beta.processAutomaticEvidence()
        assertEquals(BetaOwnershipStatus.OWNED,tracked(beta,b.id).status)
        assertNull(beta.financialPosting(PostingKey(Environment.BETA,"evidence:$raw")))
        assertEquals("AMBIGUOUS_OWNER",beta.evidenceSummary(raw).financialReviewReason)
        assertEquals(2,beta.assetTrackingSnapshot().size)
        beta.backup();beta.setAssetTracked(tracked(beta,a.id),true);assertTrue(beta.restore())
        assertEquals(BetaOwnershipStatus.EXTERNAL,tracked(beta,a.id).status)
        assertEquals(BetaOwnershipStatus.OWNED,tracked(beta,b.id).status)
    }
    fun stoppedLoanDoesNotGetRediscoveredAndResumeRetainsItsIdentity()=isolated {beta ->
        beta.registerAsset(OwnedAsset(id("account"),AssetKind.ACCOUNT,"3001"))
        capture(beta,"loan","خصم: قسط تمويل\nمن: 3001\nالقسط: SAR 3,036.11\nالمبلغ المتبقي: SAR 30,361.14\nلـ: تمويل شخصي\nفي: 00:40 27-09-2026")
        beta.processAutomaticEvidence()
        val loan=beta.assets().single {it.kind==AssetKind.LOAN}
        beta.setAssetTracked(tracked(beta,loan.id),false);beta.processAutomaticEvidence()
        assertTrue(beta.assets().none {it.kind==AssetKind.LOAN})
        assertEquals(1,beta.assetTrackingSnapshot().count {it.asset.kind==AssetKind.LOAN})
        // An owned-account repayment cannot be guessed as a different financial type.
        assertTrue(beta.dashboard(query).snapshot.loans.isEmpty())
        beta.setAssetTracked(tracked(beta,loan.id),true);beta.processAutomaticEvidence()
        assertEquals(loan.id,beta.assets().single {it.kind==AssetKind.LOAN}.id)
        assertTrue(beta.reviewInboxPage().rows.isEmpty())
    }
    fun stoppingOneAccountReconsidersPairedTransferWithoutDoubleCounting()=isolated {beta ->
        listOf("1234","5678").forEach {beta.registerAsset(OwnedAsset(id(it),AssetKind.ACCOUNT,it))}
        listOf("Outgoing" to "12:00","Incoming" to "12:01").forEachIndexed {i,(direction,time) ->
            capture(beta,"pair-$i","$direction transfer\nFrom account: 1234\nTo account: 5678\nAmount: SAR 100\nNetwork: intra-bank\nDate: 2026-10-18 $time")}
        beta.processAutomaticEvidence();assertEquals(1,beta.dashboard(query).snapshot.transactions.size)
        beta.setAssetTracked(tracked(beta,id("5678")),false);beta.processAutomaticEvidence()
        val read=beta.dashboard(query)
        assertTrue(read.snapshot.transactions.isEmpty())
        assertTrue(com.baraa.masroof.lab.finance.dashboard.producer.DashboardSection.CURRENT_LEDGER in read.unavailableSections)
        assertEquals(1,beta.reviewInboxPage().rows.size) // One linked transfer, not one review per notice.
        beta.setAssetTracked(tracked(beta,id("5678")),true);beta.processAutomaticEvidence()
        assertEquals(1,beta.dashboard(query).snapshot.transactions.size)
        assertEquals(FinancialType.SELF_TRANSFER,beta.dashboard(query).snapshot.transactions.single().effectiveType)
        assertTrue(beta.reviewInboxPage().rows.isEmpty())
    }
}
