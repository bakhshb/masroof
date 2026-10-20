package com.baraa.masroof.lab.beta.verification

import android.content.Context
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import org.junit.Assert.*
import java.time.*

/** Same paired-notice replay used by entry, import completion and card configuration. */
class BetaStartupReplayChecks(private val context: Context) {
    fun pairedTransferSurvivesCardEditsAndRepeatedEntry(afterReopen: ()->Unit = {}) {
        BetaContainer.resetOffline(context)
        val bank=BankId("BANK_ALJAZIRA")
        val query=DashboardQuery(Environment.BETA,Instant.parse("2026-10-01T00:00:00Z"),Instant.parse("2026-11-01T00:00:00Z"),ZoneId.of("Asia/Riyadh"),Currency.SAR)
        try {
            lateinit var key: PostingKey
            var originalHistory=0
            BetaContainer(context).use {beta ->
                beta.acknowledgeStart();beta.setBankEnabled(bank,true)
                listOf("1234","5678").forEach {suffix -> beta.saveManualAsset(ManualAssetDraft(AssetId(Environment.BETA,bank,"account-$suffix"),AssetKind.ACCOUNT,suffix,ownedConfirmed=true))}
                beta.saveManualAsset(ManualAssetDraft(AssetId(Environment.BETA,bank,"unrelated-card"),AssetKind.CREDIT_CARD,"7271",AssetId(Environment.BETA,bank,"facility-one"),CreditCardRole.PRIMARY,true,createCreditFacility=true))
                val messages=listOf("Outgoing" to "12:00","Incoming" to "12:01").mapIndexed {i,(direction,time) -> EvidenceMessage("notice-$i","AlJazira",
                    "$direction transfer\nFrom account: 1234\nTo account: 5678\nAmount: SAR 100\nNetwork: intra-bank\nDate: 2026-10-18 $time",Instant.parse("2026-10-18T09:02:00Z").plusSeconds(i.toLong()),ReceiptTimeSource.PROVIDER_TIMESTAMP)}
                beta.importEvidence(EvidenceRequest("startup-pair",EvidenceOrigin.EXPLICIT_HISTORY,setOf(bank)),messages)
                beta.processAutomaticEvidence()
                val row=beta.dashboard(query).snapshot.transactions.single()
                assertEquals(FinancialType.SELF_TRANSFER,row.effectiveType)
                key=row.key
                originalHistory=beta.financialHistory(key).size
                assertEquals(2,beta.financialPosting(key)!!.command.evidenceIds.size)
            }
            repeat(3) {pass -> BetaContainer(context).use {beta ->
                val card=beta.assets().single {it.kind==AssetKind.CREDIT_CARD}
                // A metadata-only edit must not change a transfer payload, revision or ordering.
                beta.saveManualAsset(ManualAssetDraft(card.id,card.kind,card.last4.orEmpty(),card.parentId,card.creditCardRole,true,true,card,
                    metadata=BetaAssetMetadata(displayName="Card $pass",revision=beta.assetMetadata().getValue(card.id).revision)))
                beta.processAutomaticEvidence()
                assertEquals(originalHistory,beta.financialHistory(key).size)
                assertEquals(1,beta.dashboard(query).snapshot.transactions.size)
                assertTrue(beta.reviewInboxPage().rows.isEmpty())
            }}
            BetaContainer(context).use {beta ->
                val card=beta.assets().single {it.kind==AssetKind.CREDIT_CARD}
                beta.saveManualAsset(ManualAssetDraft(card.id,card.kind,card.last4.orEmpty(),AssetId(Environment.BETA,bank,"facility-two"),CreditCardRole.PRIMARY,true,true,card,true))
                beta.processAutomaticEvidence()
                // Changing an unrelated card must not invalidate either owned account's transfer.
                assertEquals(1,beta.dashboard(query).snapshot.transactions.size)
                assertEquals(2,beta.financialPosting(key)!!.command.evidenceIds.size)
                assertTrue(beta.reviewInboxPage().rows.isEmpty())
            }
            afterReopen()
        } finally {BetaContainer.resetOffline(context)}
    }
}
