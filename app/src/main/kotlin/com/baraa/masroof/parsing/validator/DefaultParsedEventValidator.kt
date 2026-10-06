package com.baraa.masroof.parsing.validator

import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.parsing.model.AmountSourceKind
import com.baraa.masroof.parsing.model.ParsedEventDraft
import java.time.Clock
import java.time.LocalDateTime

/**
 * Bank-agnostic validator: the final firewall between extracted facts and automatic
 * financial use. Every ERROR finding blocks SUCCESS in [com.baraa.masroof.parsing.finalize.ParseFinalizer].
 *
 * Structural rules:
 * - V-009 financial family requires an amount; V-010 the amount must be positive.
 * - V-011 financial family requires a direction consistent with the family.
 * - V-012 financial classification confidence must meet [AutomaticUsePolicy.minFinancialConfidence].
 * - V-013 / V-014 card and account suffixes, when present, must be exactly four ASCII digits.
 * - V-015 local occurrence time, when present, must be plausible under [AutomaticUsePolicy].
 * - V-016 / V-017 conflicting strong facts (same account on both sides, purchase channel
 *   on a non-purchase family).
 * - V-008 missing purchase merchant (warning only).
 *
 * Provenance-aware amount safety: V-001…V-007 (enabled when extractors populate
 * [ParsedEventDraft.selectedAmount] / [ParsedEventDraft.amountCandidates]).
 * Numeric coincidence between a legitimate amount and a last4 is **not** an error.
 * V-007 fires only for multiple **distinct** transaction Money values.
 *
 * Validates parse facts only: never resolves ownership or decides transaction type.
 */
class DefaultParsedEventValidator(
    private val policy: AutomaticUsePolicy = AutomaticUsePolicy(),
    private val clock: Clock = Clock.systemUTC(),
) : ParsedEventValidator {
    override fun validate(draft: ParsedEventDraft): ValidationResult {
        val findings = mutableListOf<ValidationFinding>()
        val family = draft.messageFamily

        if (family != null && family.isFinancial) {
            findings += validateFinancialStructure(draft, family)
        }

        if (family == MessageFamily.PURCHASE && draft.merchant.isNullOrBlank()) {
            findings += ValidationFinding(
                code = "V-008",
                message = "Merchant is missing on purchase; optional but noted",
                severity = ValidationSeverity.WARNING,
            )
        }

        findings += validateReferenceShapes(draft)
        findings += validateOccurredAt(draft)
        findings += validateConflictingFacts(draft, family)
        findings += validateAmountProvenance(draft)

        if (draft.parseStatus == ParseStatus.SUCCESS && findings.any { it.severity == ValidationSeverity.ERROR }) {
            findings += ValidationFinding(
                code = "STATUS_CONFLICT",
                message = "ParseStatus.SUCCESS is incompatible with validation errors",
                severity = ValidationSeverity.ERROR,
            )
        }

        return ValidationResult(findings)
    }

    private fun validateFinancialStructure(draft: ParsedEventDraft, family: MessageFamily): List<ValidationFinding> {
        val findings = mutableListOf<ValidationFinding>()
        val amount = draft.amount
        if (amount == null) {
            findings += finding("V-009", "Required amount is missing for financial family $family")
        } else if (amount.amount.signum() <= 0) {
            findings += finding("V-010", "Financial amount must be positive, was ${amount.amount.toPlainString()}")
        }

        val allowed = ALLOWED_DIRECTIONS.getValue(family)
        val direction = draft.direction
        if (direction == null || direction !in allowed) {
            findings += finding("V-011", "Direction $direction is inconsistent with family $family (expected $allowed)")
        }

        val score = draft.confidence?.score
        if (score != null && score < policy.minFinancialConfidence) {
            findings += finding(
                "V-012",
                "Classification confidence $score is below the automatic-use minimum ${policy.minFinancialConfidence}",
            )
        }
        return findings
    }

    private fun validateReferenceShapes(draft: ParsedEventDraft): List<ValidationFinding> {
        val findings = mutableListOf<ValidationFinding>()
        draft.cardRef?.last4?.let { last4 ->
            if (!SUFFIX_SHAPE.matches(last4)) {
                findings += finding("V-013", "Card suffix '$last4' is not four digits")
            }
        }
        listOfNotNull(draft.sourceAccountRef?.maskedNumber, draft.destinationAccountRef?.maskedNumber)
            .filterNot(SUFFIX_SHAPE::matches)
            .forEach { masked -> findings += finding("V-014", "Account suffix '$masked' is not four digits") }
        return findings
    }

    private fun validateOccurredAt(draft: ParsedEventDraft): List<ValidationFinding> {
        val occurredAt = draft.details.occurredAtLocal ?: return emptyList()
        val latest = LocalDateTime.now(clock).plus(policy.maxFutureSkew)
        return if (occurredAt.isBefore(policy.earliestOccurredAtLocal) || occurredAt.isAfter(latest)) {
            listOf(finding("V-015", "Occurrence time $occurredAt is outside the plausible window"))
        } else {
            emptyList()
        }
    }

    private fun validateConflictingFacts(draft: ParsedEventDraft, family: MessageFamily?): List<ValidationFinding> {
        val findings = mutableListOf<ValidationFinding>()
        val source = draft.sourceAccountRef
        if (source?.maskedNumber != null && source == draft.destinationAccountRef) {
            findings += finding("V-016", "Source and destination are the same account reference")
        }
        if (draft.purchaseChannel != null && family != null && family != MessageFamily.PURCHASE) {
            findings += finding("V-017", "Purchase channel ${draft.purchaseChannel} conflicts with family $family")
        }
        return findings
    }

    private fun validateAmountProvenance(draft: ParsedEventDraft): List<ValidationFinding> {
        val findings = mutableListOf<ValidationFinding>()
        val hasProvenanceContext =
            draft.selectedAmount != null || draft.amountCandidates.isNotEmpty()
        if (!hasProvenanceContext) {
            return findings
        }

        val txnCandidates = draft.amountCandidates.filter {
            it.sourceKind == AmountSourceKind.TRANSACTION_AMOUNT
        }
        val distinctTxnValues = txnCandidates.map { it.value }.distinct()
        if (distinctTxnValues.size > 1) {
            findings += ValidationFinding(
                code = "V-007",
                message = "Multiple distinct plausible transaction amounts; cannot disambiguate safely",
                severity = ValidationSeverity.ERROR,
            )
        }

        val selected = draft.selectedAmount
        if (draft.amount != null) {
            if (selected == null) {
                findings += ValidationFinding(
                    code = "V-006",
                    message = "Transaction amount lacks extraction provenance",
                    severity = ValidationSeverity.ERROR,
                )
            } else {
                if (selected.value != draft.amount) {
                    findings += ValidationFinding(
                        code = "AMOUNT_VALUE_MISMATCH",
                        message = "selectedAmount.value ${selected.value} does not equal draft.amount ${draft.amount}",
                        severity = ValidationSeverity.ERROR,
                    )
                }
                if (draft.amountCandidates.none { it == selected }) {
                    findings += ValidationFinding(
                        code = "AMOUNT_PROVENANCE_ORPHAN",
                        message = "selectedAmount is not present in amountCandidates",
                        severity = ValidationSeverity.ERROR,
                    )
                }
                when (selected.sourceKind) {
                    AmountSourceKind.CARD_LAST4 -> findings += finding(
                        "V-001",
                        "Amount provenance is card last4 label '${selected.evidenceLabel}'",
                    )
                    AmountSourceKind.ACCOUNT_LAST4 -> findings += finding(
                        "V-002",
                        "Amount provenance is account last4 label '${selected.evidenceLabel}'",
                    )
                    AmountSourceKind.AVAILABLE_BALANCE,
                    AmountSourceKind.OUTSTANDING_BALANCE,
                    -> findings += finding(
                        "V-003",
                        "Amount provenance is balance label '${selected.evidenceLabel}'",
                    )
                    AmountSourceKind.REFERENCE -> findings += finding(
                        "V-004",
                        "Amount provenance is reference label '${selected.evidenceLabel}'",
                    )
                    AmountSourceKind.DATE_TIME -> findings += finding(
                        "V-005",
                        "Amount provenance is date/time digits",
                    )
                    AmountSourceKind.OTHER -> findings += finding(
                        "V-006",
                        "Amount not associated with a strong amount label",
                    )
                    AmountSourceKind.TRANSACTION_AMOUNT -> Unit
                }
            }
        }

        return findings
    }

    private fun finding(code: String, message: String) = ValidationFinding(
        code = code,
        message = message,
        severity = ValidationSeverity.ERROR,
    )

    private val MessageFamily.isFinancial: Boolean
        get() = this in ALLOWED_DIRECTIONS

    private companion object {
        private val SUFFIX_SHAPE = Regex("""[0-9]{4}""")

        private val OUTGOING = setOf(MoneyDirection.OUTGOING)
        private val INCOMING = setOf(MoneyDirection.INCOMING)

        /**
         * Financial families and the directions their parse facts may carry. Direction is
         * relative to the referenced account or card, so a card payment may be reported
         * from either side.
         */
        private val ALLOWED_DIRECTIONS: Map<MessageFamily, Set<MoneyDirection>> = mapOf(
            MessageFamily.PURCHASE to OUTGOING,
            MessageFamily.TRANSFER_IN to INCOMING,
            MessageFamily.TRANSFER_OUT to OUTGOING,
            MessageFamily.CARD_PAYMENT to setOf(MoneyDirection.OUTGOING, MoneyDirection.INCOMING),
            MessageFamily.BILL_PAYMENT to OUTGOING,
            MessageFamily.FINANCING_INSTALLMENT to OUTGOING,
            MessageFamily.WITHDRAWAL to OUTGOING,
            MessageFamily.REFUND to INCOMING,
            MessageFamily.FEE to OUTGOING,
        )
    }
}
