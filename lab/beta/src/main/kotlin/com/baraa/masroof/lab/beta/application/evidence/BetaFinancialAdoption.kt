package com.baraa.masroof.lab.beta.application.evidence

import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.classification.IncomeAttribution
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.Environment

/** Explicit financial approval, separate from comparison acknowledgement. No parser success is
 * asserted by this request. Amount and time come from the current bank-engine archive, or from a
 * preserved historical baseline when that is the only completed archive. */
data class BetaFinancialApproval(
    val evidenceId: String,
    val archiveRevision: String,
    val expectedPostingRevision: PostingRevision?,
    val type: FinancialType,
    val source: AssetId?,
    val destination: AssetId?,
    val incomeAttribution: IncomeAttribution? = null,
    val expectedRegistryRevision: String? = null,
) {
    init {
        require(evidenceId.isNotBlank() && archiveRevision.isNotBlank())
        require(listOfNotNull(source, destination).all { it.environment == Environment.BETA })
    }
}

/** Safe preserved facts, never experimental candidates or reconstructed financial authority. */
data class BetaFinancialApprovalPreview(
    val item: BetaFinancialReviewItem,
    val amount: com.baraa.masroof.lab.finance.value.Money?,
    val time: TransactionTime?,
    val assets: List<com.baraa.masroof.lab.finance.asset.OwnedAsset>,
    val registryRevision: String,
    val pending: BetaFinancialApproval?,
    val blockedReason: String?,
    val correctionOwned: Boolean,
    val merchant: String? = null,
    val exchangeRate: String? = null,
    val internationalFee: com.baraa.masroof.lab.finance.value.Money? = null,
)

data class BetaFinancialReviewItem(val evidenceId: String, val key: PostingKey,
    val archiveRevision: String, val status: String, val reason: String,
    val postingRevision: PostingRevision?)

sealed interface BetaFinancialAdoptionResult {
    data class Posted(val result: FinancialPostingResult) : BetaFinancialAdoptionResult
    data class Review(val reason: String) : BetaFinancialAdoptionResult
}
