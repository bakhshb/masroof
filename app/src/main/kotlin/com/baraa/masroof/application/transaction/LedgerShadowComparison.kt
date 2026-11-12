package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.domain.assembly.TransactionAssembler
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.ledger.LedgerEvidence
import com.baraa.masroof.ledger.LedgerProjection
import com.baraa.masroof.ledger.LedgerRow
import java.security.MessageDigest
import java.time.Duration

/**
 * Plan 3.2 shadow comparison: the stored ledger (what `TransactionReconciliationService` wrote) against
 * [com.baraa.masroof.ledger.LedgerProjector] on the same [LedgerInputs]. Pure; the owner accepts or rejects each
 * difference (docs/ARCHITECTURE.md, "Shadow comparison").
 */
object LedgerShadowComparison {
    enum class Kind { ONLY_OLD, ONLY_NEW, CHANGED, GROUPING }

    /**
     * A reason is given only when its condition is checked on the inputs; [description] says what was checked, not
     * the cause behind it. Anything else is [UNEXPLAINED].
     */
    enum class Reason(val description: String) {
        STORED_ROW_WITHOUT_LINKS("a stored row that links no raw SMS"),
        RAW_SMS_WITHOUT_EVIDENCE("every stored row of the group links a raw SMS with no effective parsed event"),
        ID_ONLY("only the transaction id differs (Known differences: Ids)"),
        EXCHANGE_RATE("only the exchange-rate pair differs (Known differences: Exchange rate)"),
        ZONE_FILLED("only the zone differs, and the stored zone is empty"),
        TIME_REDERIVED("only the time differs (the stored zone empty or equal): the stored time is not what the evidence gives today"),
        DECIDED_WITHOUT_POSTING_COLUMNS("a stored row whose posting outcome decision lacks its type, time or zone"),
        DECIDED_NON_FINANCIAL_BUT_STORED("a stored row whose outcome decision says not financial"),
        DECIDED_ROW_NOT_STORED("a decided row the projector posts and the stored ledger lacks; cause not determined"),
        INSTALLMENT_FEE("a stored FEE for a financing installment the projector does not post"),
        OWNERSHIP_NOT_OWNED("a stored row the projector drops, none of whose registry containers is OWNED now"),
        STALE_EVIDENCE("an undecided stored row whose evidence now differs (amount, merchant, or no longer automation-eligible)"),
        ROW_TYPE_WITHOUT_DECISION("the type differs and the SMS has no outcome decision (a row-only edit and an ownership change are not told apart)"),
        UNEXPLAINED("none of the checked conditions holds"),
    }

    data class RowDifference(
        val kind: Kind,
        val rawSmsIds: List<String>,
        val oldRows: List<LedgerRow>,
        val newRows: List<LedgerRow>,
        val changedFields: List<String>,
        val reason: Reason,
    )

    /**
     * An open review (`REQUIRED`) on one side only, or of another kind. For context, [storedReview] is the stored
     * review's status and [decision] the kind of the SMS's outcome decision (read from the store, not the review).
     */
    data class ReviewDifference(
        val rawSmsId: String,
        val old: ReviewKind?,
        val new: ReviewKind?,
        val storedReview: ReviewStatus?,
        val decision: UserDecisionKind?,
    )

    data class Result(
        val oldRowCount: Int,
        val newRowCount: Int,
        val rows: List<RowDifference>,
        val reviews: List<ReviewDifference>,
        val retryRawSmsIds: List<String>,
    )

    fun compare(inputs: LedgerInputs, projection: LedgerProjection = inputs.project()): Result {
        val evidence = inputs.events.associateBy { it.event.rawSmsId }
        val rows = groups(inputs.storedRows, projection.rows).mapNotNull { (old, new) ->
            val rawSmsIds = (old + new).flatMap { it.rawSmsIds }.distinct().sorted()
            val kind = when {
                new.isEmpty() -> Kind.ONLY_OLD
                old.isEmpty() -> Kind.ONLY_NEW
                old.size == 1 && new.size == 1 && old[0].rawSmsIds == new[0].rawSmsIds -> Kind.CHANGED
                else -> Kind.GROUPING
            }
            val fields = if (kind == Kind.CHANGED) changedFields(old[0].transaction, new[0].transaction) else emptyList()
            if (kind == Kind.CHANGED && fields.isEmpty()) return@mapNotNull null
            RowDifference(kind, rawSmsIds, old, new, fields, reason(kind, fields, rawSmsIds, old, new, inputs, evidence))
        }.sortedBy { it.rawSmsIds.firstOrNull() ?: it.oldRows.first().transaction.id }
        val storedReviews = inputs.reviews.associateBy { it.rawSmsId }
        val newReviews = projection.reviewCandidates.associate { it.rawSmsId to it.kind }
        val reviews = evidence.keys.sorted().mapNotNull { rawSmsId ->
            val stored = storedReviews[rawSmsId]
            val old = stored?.kind?.takeIf { stored.status == ReviewStatus.REQUIRED }
            val new = newReviews[rawSmsId]
            if (old == new) return@mapNotNull null
            ReviewDifference(
                rawSmsId, old, new,
                storedReview = stored?.status,
                decision = inputs.decisions[rawSmsId]?.outcome?.kind,
            )
        }
        return Result(inputs.storedRows.size, projection.rows.size, rows, reviews, projection.retryRawSmsIds.sorted())
    }

    /** Old and new rows joined into the groups that share a raw SMS. */
    private fun groups(old: List<LedgerRow>, new: List<LedgerRow>): List<Pair<List<LedgerRow>, List<LedgerRow>>> {
        val all = old + new
        val parent = IntArray(all.size) { it }
        fun find(i: Int): Int = if (parent[i] == i) i else find(parent[i]).also { parent[i] = it }
        val firstByRawSmsId = mutableMapOf<String, Int>()
        all.forEachIndexed { index, row ->
            row.rawSmsIds.forEach { rawSmsId ->
                firstByRawSmsId[rawSmsId]?.let { parent[find(index)] = find(it) } ?: firstByRawSmsId.put(rawSmsId, index)
            }
        }
        return all.indices.groupBy(::find).values.map { members ->
            members.partition { it < old.size }.let { (o, n) -> o.map(all::get) to n.map(all::get) }
        }
    }

    private val FIELDS: List<Pair<String, (FinancialTransaction) -> Any?>> = listOf(
        "id" to { it.id },
        "type" to { it.type },
        "amount" to { it.amount },
        "occurredAt" to { it.occurredAt },
        "occurredAtZone" to { it.occurredAtZone },
        "source" to { it.sourceContainerId },
        "destination" to { it.destinationContainerId },
        "merchant" to { it.merchant },
        "counterparty" to { it.counterparty },
        "category" to { it.categoryId },
        "parsedEvents" to { it.linkedParsedEventIds.sorted() },
        "exchangeRate" to { it.appliedExchangeRate?.stripTrailingZeros() },
        "exchangeRateSource" to { it.exchangeRateSource },
    )

    private val TIME_FIELDS = setOf("occurredAt", "occurredAtZone")
    private val POSTING_OUTCOMES = setOf(
        UserDecisionKind.EXTERNAL_TRANSFER, UserDecisionKind.FINANCIAL_TYPE, UserDecisionKind.RECLASSIFY,
        UserDecisionKind.RESTORE, UserDecisionKind.SELF_TRANSFER_PAIR,
    )

    private fun changedFields(old: FinancialTransaction, new: FinancialTransaction): List<String> =
        FIELDS.filter { (_, read) -> read(old) != read(new) }.map { it.first }

    @Suppress("CyclomaticComplexMethod")
    private fun reason(
        kind: Kind,
        fields: List<String>,
        rawSmsIds: List<String>,
        old: List<LedgerRow>,
        new: List<LedgerRow>,
        inputs: LedgerInputs,
        evidence: Map<String, LedgerEvidence>,
    ): Reason {
        val outcomes = rawSmsIds.mapNotNull { inputs.decisions[it]?.outcome }
        val oldRow = old.singleOrNull()?.transaction
        val installment = rawSmsIds.any { evidence[it]?.event?.messageFamily == MessageFamily.FINANCING_INSTALLMENT }
        return when {
            old.any { it.rawSmsIds.isEmpty() } -> Reason.STORED_ROW_WITHOUT_LINKS
            new.isEmpty() && old.all { row -> row.rawSmsIds.any { it !in evidence } } -> Reason.RAW_SMS_WITHOUT_EVIDENCE
            kind == Kind.CHANGED && fields == listOf("id") -> Reason.ID_ONLY
            kind == Kind.CHANGED && fields.all { it.startsWith("exchangeRate") } -> Reason.EXCHANGE_RATE
            kind == Kind.CHANGED && fields == listOf("occurredAtZone") && oldRow?.occurredAtZone == null -> Reason.ZONE_FILLED
            kind == Kind.CHANGED && "occurredAt" in fields && TIME_FIELDS.containsAll(fields) &&
                ("occurredAtZone" !in fields || oldRow?.occurredAtZone == null) -> Reason.TIME_REDERIVED
            outcomes.isNotEmpty() -> when {
                old.isNotEmpty() && outcomes.any { it.kind in NON_FINANCIAL_OUTCOMES } -> Reason.DECIDED_NON_FINANCIAL_BUT_STORED
                old.isNotEmpty() && outcomes.any { it.kind in POSTING_OUTCOMES && !it.hasPostingColumns() } ->
                    Reason.DECIDED_WITHOUT_POSTING_COLUMNS
                kind == Kind.ONLY_NEW -> Reason.DECIDED_ROW_NOT_STORED
                else -> Reason.UNEXPLAINED
            }
            installment && kind == Kind.ONLY_OLD && oldRow?.type == FinancialTransactionType.FEE -> Reason.INSTALLMENT_FEE
            kind == Kind.ONLY_OLD && old.all { noOwnedContainer(it.transaction, inputs) } -> Reason.OWNERSHIP_NOT_OWNED
            old.any { !matchesEvidence(it, evidence) } -> Reason.STALE_EVIDENCE
            kind == Kind.CHANGED && "type" in fields -> Reason.ROW_TYPE_WITHOUT_DECISION
            else -> Reason.UNEXPLAINED
        }
    }

    private val NON_FINANCIAL_OUTCOMES =
        setOf(UserDecisionKind.NON_FINANCIAL, UserDecisionKind.IGNORE, UserDecisionKind.RESTORE_REVERTED)

    private fun UserDecision.hasPostingColumns() = transactionType != null && occurredAt != null && occurredAtZone != null

    /** True when at least one of the row's containers is in the registry inputs and none of them is OWNED. */
    private fun noOwnedContainer(transaction: FinancialTransaction, inputs: LedgerInputs): Boolean {
        val registry = inputs.registry
        val statuses = registry.accounts.mapKeys { FinancialContainerIdFactory.accountId(it.key) } +
            registry.cards.mapKeys { FinancialContainerIdFactory.cardId(it.key) }
        val found = listOfNotNull(transaction.sourceContainerId, transaction.destinationContainerId).mapNotNull(statuses::get)
        return found.isNotEmpty() && OwnershipStatus.OWNED !in found
    }

    /** True when [row]'s evidence is still automation-eligible and a lone leg's amount and merchant are as posted. */
    private fun matchesEvidence(row: LedgerRow, evidence: Map<String, LedgerEvidence>): Boolean {
        val legs = row.rawSmsIds.mapNotNull(evidence::get)
        if (legs.any { !TransactionAssembler.isAutomationEligible(it.event, it.automationConfirmed) }) return false
        val single = legs.singleOrNull()?.event ?: return true
        return single.amount == row.transaction.amount && single.merchant == row.transaction.merchant
    }

    fun report(inputs: LedgerInputs): String = report(compare(inputs))

    /** The plain-text report: a summary by reason, then one block per difference. Ids masked; no SMS text. */
    fun report(result: Result): String = buildString {
        appendLine("# Ledger shadow comparison (plan 3.2)")
        appendLine("old rows: ${result.oldRowCount}  new rows: ${result.newRowCount}")
        appendLine("row differences: ${result.rows.size}  review differences: ${result.reviews.size}  projection retries: ${result.retryRawSmsIds.size}")
        appendLine()
        appendLine("## Summary by reason")
        val byReason = result.rows.groupBy { it.reason }
        Reason.entries.filter { it in byReason }.forEach { reason ->
            val differences = byReason.getValue(reason)
            appendLine("${reason.name}: ${differences.size}  old ${totals(differences.flatMap { it.oldRows })}" +
                "  new ${totals(differences.flatMap { it.newRows })}  (${reason.description})")
        }
        appendLine()
        appendLine("## Row differences")
        result.rows.forEachIndexed { index, difference ->
            appendLine()
            appendLine("### ${index + 1} ${difference.kind} reason=${difference.reason}")
            appendLine("raw sms: ${difference.rawSmsIds.joinToString(", ", transform = ::mask)}")
            if (difference.changedFields.isNotEmpty()) appendLine("changed: ${difference.changedFields.joinToString(", ")}")
            if ("occurredAt" in difference.changedFields) {
                val shift = Duration.between(difference.oldRows[0].transaction.occurredAt, difference.newRows[0].transaction.occurredAt)
                appendLine("time shift: ${shift.seconds} s")
            }
            difference.oldRows.forEach { appendLine("old: ${describe(it)}") }
            difference.newRows.forEach { appendLine("new: ${describe(it)}") }
        }
        appendLine()
        appendLine("## Review differences (open review kind, old -> new)")
        result.reviews.forEach {
            appendLine("${mask(it.rawSmsId)}: ${it.old ?: "none"} -> ${it.new ?: "none"}  stored=${it.storedReview ?: "-"} decision=${it.decision ?: "-"}")
        }
        appendLine()
        appendLine("## Projection retries")
        result.retryRawSmsIds.forEach { appendLine(mask(it)) }
    }

    /** Summed amounts per currency, e.g. `120.00 SAR + 10.00 USD`; `-` for none. */
    private fun totals(rows: List<LedgerRow>): String =
        rows.groupBy { it.transaction.amount.currency }.entries.sortedBy { it.key.name }
            .joinToString(" + ") { (currency, group) ->
                "${group.sumOf { it.transaction.amount.amount }.toPlainString()} $currency"
            }.ifEmpty { "-" }

    private fun describe(row: LedgerRow): String {
        val t = row.transaction
        return listOf(
            "id=${mask(t.id)}", "type=${t.type}", "amount=${t.amount.amount.toPlainString()} ${t.amount.currency}",
            "at=${t.occurredAt}", "zone=${t.occurredAtZone ?: "-"}",
            "source=${maskDigits(t.sourceContainerId)}", "destination=${maskDigits(t.destinationContainerId)}",
            "merchant=${maskDigits(t.merchant)}", "counterparty=${maskDigits(t.counterparty)}",
            "category=${t.categoryId ?: "-"}",
            "fx=${t.appliedExchangeRate?.toPlainString() ?: "-"}/${t.exchangeRateSource ?: "-"}",
            "raw=${row.rawSmsIds.joinToString(";", transform = ::mask)}",
            "events=${t.linkedParsedEventIds.sorted().joinToString(";", transform = ::mask)}",
        ).joinToString(" ")
    }

    /** The log mask plus a short digest, so two ids with the same last four stay apart in one report. */
    private fun mask(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return AppLogFormatting.maskId(id) + "#" + digest.toHexString(endIndex = 3)
    }

    // Five or more digits, possibly grouped by single spaces or dashes, could be a full account or card number.
    private val LONG_DIGITS = Regex("\\d(?:[ -]?\\d){4,}")

    private fun maskDigits(text: String?): String =
        text?.replace(LONG_DIGITS) { "…" + it.value.filter(Char::isDigit).takeLast(4) } ?: "-"
}
