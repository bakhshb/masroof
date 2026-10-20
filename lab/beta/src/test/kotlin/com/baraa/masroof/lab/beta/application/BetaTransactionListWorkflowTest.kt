package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.beta.application.evidence.betaAssetChoiceKey
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.*

class BetaTransactionListWorkflowTest {
    private val bank=BankId("BANK_ALJAZIRA")
    private fun asset(bank: BankId,local: String)=AssetId(Environment.BETA,bank,local)
    private val account=asset(bank,"account")
    private val card=asset(bank,"card-7271")
    private fun row(id: String,type: FinancialType=FinancialType.EXPENSE,account: AssetId=this.account,card: AssetId=this.card)=TransactionFacts(
        PostingKey(Environment.BETA,id),PostingRevision(0,1),TransactionTime(Instant.parse("2026-10-01T12:00:00Z"),ZoneId.of("Asia/Riyadh")),
        type,type,Money.of(BigDecimal("1200.50"),Currency.SAR),"Test Merchant",null,"7271",setOf(account),setOf(card),emptySet(),emptySet(),emptySet(),null,null,null,emptySet(),account)
    @Test fun sameSuffixCardsAndLocalIdsAtDifferentBanksRemainIndependent() {
        val otherBank=BankId("BANK_OTHER")
        val a=row("a");val b=row("b",account=asset(otherBank,"account"),card=asset(otherBank,"card-7271"))
        assertEquals(listOf(a),BetaTransactionListWorkflow.filter(listOf(a,b),BetaTransactionFilter(cards=setOf(betaAssetChoiceKey(card)))))
        assertEquals(listOf(b),BetaTransactionListWorkflow.filter(listOf(a,b),BetaTransactionFilter(accounts=setOf(betaAssetChoiceKey(asset(otherBank,"account"))))))
        assertEquals(2,BetaTransactionListWorkflow.filter(listOf(a,b),BetaTransactionFilter(cards=setOf(betaAssetChoiceKey(card),betaAssetChoiceKey(asset(otherBank,"card-7271"))))).size)
    }
    @Test fun selectionsAreOrWithinEachSectionAndAndAcrossSections() {
        val expense=row("a");val refund=row("b",FinancialType.REFUND);val unrelated=row("c",FinancialType.INCOME,asset(bank,"other"))
        val filter=BetaTransactionFilter(types=setOf("EXPENSE","REFUND"),accounts=setOf(betaAssetChoiceKey(account)),cards=setOf(betaAssetChoiceKey(card)))
        assertEquals(listOf(refund,expense),BetaTransactionListWorkflow.filter(listOf(unrelated,refund,expense),filter))
        assertEquals(3,BetaTransactionListWorkflow.filter(listOf(unrelated,refund,expense),BetaTransactionFilter()).size)
    }
    @Test fun amountSearchAcceptsGroupingSeparatorsAndTextIsCaseInsensitive() {
        val row=row("a")
        for(search in listOf("1,200.50","1200.5","١٢٠٠.٥٠","۱۲۰۰.۵۰","merchant"," TEST "))
            assertEquals(listOf(row),BetaTransactionListWorkflow.filter(listOf(row),BetaTransactionFilter(search=search)))
        assertTrue(BetaTransactionListWorkflow.filter(listOf(row),BetaTransactionFilter(search="absent")).isEmpty())
    }
    @Test fun searchUsesTheSameWesternDigitsThatImportedNamesDisplay() {
        val row=row("a").copy(merchant="متجر ١٢")
        assertEquals(listOf(row),BetaTransactionListWorkflow.filter(listOf(row),BetaTransactionFilter(search="متجر 12")))
        assertEquals("متجر ١٢",row.merchant)
    }
    @Test fun shareTotalMatchesSourceOriginalCurrencyScopeAndNeverMixesCurrencies() {
        val sar=row("sar")
        val usd=row("usd").copy(amount=Money.of(BigDecimal("10"),Currency.USD))
        assertEquals(Money.of(BigDecimal("1200.50"),Currency.SAR),BetaTransactionListWorkflow.shareTotal(listOf(sar,usd),Currency.SAR))
        assertNull(BetaTransactionListWorkflow.shareTotal(listOf(usd),Currency.SAR))
        assertNull(BetaTransactionListWorkflow.shareTotal(emptyList(),Currency.SAR))
    }
    @Test fun sourceTypeOrderAndFactsArePreserved() {
        val a=row("a",FinancialType.SELF_TRANSFER);val b=row("b",FinancialType.INCOME);val c=row("c")
        val original=listOf(a,b,c)
        assertEquals(listOf(FinancialType.EXPENSE,FinancialType.INCOME,FinancialType.SELF_TRANSFER),BetaTransactionListWorkflow.types(original))
        BetaTransactionListWorkflow.filter(original,BetaTransactionFilter(types=setOf("EXPENSE")))
        assertEquals(listOf(a,b,c),original)
    }
}
