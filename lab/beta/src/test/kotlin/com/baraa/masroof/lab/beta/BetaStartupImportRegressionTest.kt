package com.baraa.masroof.lab.beta

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*
import org.json.JSONObject
import org.junit.*
import java.io.File
import java.time.Instant

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[28])
class BetaStartupImportRegressionTest {
    @Test fun importedSupportedCorpusAndCardEditsRemainReopenable() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val bank=BankId("BANK_ALJAZIRA")
        BetaContainer.resetOffline(context)
        try {
            BetaContainer(context).use {beta ->
                beta.acknowledgeStart();beta.setBankEnabled(bank,true)
                val files=File("../../app/src/test/resources/testdata/bank_aljazira").walkTopDown().filter {it.extension=="json" && it.parentFile?.name!="reference"}.sortedBy {it.name}.toList()
                Assert.assertEquals(40,files.size)
                val messages=files.mapIndexed {i,file -> val json=JSONObject(file.readText());EvidenceMessage(json.getString("id"),json.getString("sender"),json.getString("body"),Instant.parse("2026-10-18T12:00:00Z").plusSeconds(i.toLong()),ReceiptTimeSource.PROVIDER_TIMESTAMP)}
                beta.importEvidence(EvidenceRequest("startup-corpus",EvidenceOrigin.EXPLICIT_HISTORY,setOf(bank)),messages)
                beta.ownershipSnapshot().candidates.forEach {candidate ->
                    if(candidate.conflictingKinds) beta.deferOwnership(candidate) else beta.decideOwnership(candidate,true)
                }
                beta.assets().filter {it.kind==AssetKind.CREDIT_CARD}.forEach {card ->
                    beta.saveManualAsset(ManualAssetDraft(card.id,card.kind,card.last4.orEmpty(),AssetId(Environment.BETA,bank,"facility-${card.id.localId}"),CreditCardRole.PRIMARY,true,true,card,true))
                }
                beta.processAutomaticEvidence()
            }
            BetaContainer(context).use {beta ->
                beta.processAutomaticEvidence()
                beta.assets().filter {it.kind==AssetKind.CREDIT_CARD}.forEach {card ->
                    beta.saveManualAsset(ManualAssetDraft(card.id,card.kind,card.last4.orEmpty(),card.parentId,card.creditCardRole,true,true,card,metadata=BetaAssetMetadata(displayName="Card name")))
                    beta.processAutomaticEvidence()
                }
            }
            BetaContainer(context).use {beta -> beta.processAutomaticEvidence();Assert.assertEquals(BetaStartState.READY,beta.startState())}
        } finally {BetaContainer.resetOffline(context)}
    }
}
