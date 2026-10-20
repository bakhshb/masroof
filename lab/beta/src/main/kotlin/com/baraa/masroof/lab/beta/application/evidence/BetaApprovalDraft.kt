package com.baraa.masroof.lab.beta.application.evidence

import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.asset.AssetKind

fun betaAssetChoiceKey(id: com.baraa.masroof.lab.finance.asset.AssetId): String = "asset:${id.environment.name}:${id.bank.value}:${id.localId}"

/** Presentation submits stable choices; the pure classifier checks the exact preserved facts.
 * The write adapter independently rereads and validates everything inside its transaction. */
fun betaApprovalDraft(preview: BetaFinancialApprovalPreview, type: String?, source: String?, destination: String?, income: String?): BetaFinancialApproval? {
    if (preview.blockedReason != null || preview.pending != null || source == null || destination == null) return null
    fun endpoint(value: String): FinancialEndpoint? = if (value == "external") FinancialEndpoint.External
        else preview.assets.singleOrNull { betaAssetChoiceKey(it.id) == value || "asset:${it.id.localId}" == value }?.id?.let(FinancialEndpoint::Owned)
    val selectedType = type?.let { runCatching { FinancialType.valueOf(it) }.getOrNull() } ?: return null
    val attribution = if (selectedType in setOf(FinancialType.INCOME, FinancialType.EXTERNAL_TRANSFER_IN))
        income?.let { runCatching { IncomeAttribution.valueOf(it) }.getOrNull() } ?: return null else null
    val input = FinancialClassificationInput(preview.item.key, preview.item.postingRevision ?: PostingRevision(0, 0), selectedType,
        preview.amount ?: return null, preview.time ?: return null, endpoint(source) ?: return null, endpoint(destination) ?: return null,
        preview.assets.associateBy { it.id }, setOf(preview.item.evidenceId), true, CorrectionAuthority.USER_FINANCIAL_TYPE, incomeAttribution = attribution)
    if (FinancialImpactClassifier.classify(input) !is FinancialClassification.Classified) return null
    return BetaFinancialApproval(preview.item.evidenceId, preview.item.archiveRevision, preview.item.postingRevision, selectedType,
        (input.source as? FinancialEndpoint.Owned)?.id, (input.destination as? FinancialEndpoint.Owned)?.id, attribution, preview.registryRevision)
}

/** Reclassification preserves the proven owned side when reversing direction. Never picks an
 * unrelated registry entity merely because there is only one account/card/loan in the bank. */
fun betaReviewDefaultEndpoints(detail: BetaReviewDetail,type: FinancialType): Pair<String?,String?> {
    val assets=detail.preview?.assets.orEmpty().associateBy {it.id}
    fun key(id: AssetId?,kinds: Set<AssetKind>): String? =
        id?.takeIf {assets[it]?.kind in kinds}?.let(::betaAssetChoiceKey)
    val account=setOf(AssetKind.ACCOUNT)
    val credit=setOf(AssetKind.CREDIT_CARD)
    val spending=account+credit
    fun owned(kinds: Set<AssetKind>)=key(detail.source,kinds) ?: key(detail.destination,kinds)
    return when(type) {
        FinancialType.INCOME,FinancialType.EXTERNAL_TRANSFER_IN -> "external" to owned(account)
        FinancialType.REFUND -> "external" to (key(detail.destination,spending) ?: key(detail.source,spending))
        FinancialType.CREDIT_CARD_PAYMENT -> owned(account) to (key(detail.destination,credit) ?: key(detail.source,credit))
        FinancialType.LOAN_REPAYMENT -> owned(account) to key(detail.destination,setOf(AssetKind.LOAN))
        FinancialType.CASH_WITHDRAWAL,FinancialType.EXTERNAL_TRANSFER_OUT -> owned(account) to "external"
        FinancialType.EXPENSE,FinancialType.BILL_PAYMENT,FinancialType.FEE -> owned(spending) to "external"
        else -> null to null
    }
}
