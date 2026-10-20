package com.baraa.masroof.lab.bank.interpretation

import com.baraa.masroof.lab.bank.*
import com.baraa.masroof.lab.bank.rules.RuleLibrary
import com.baraa.masroof.lab.finance.value.Money
import java.security.MessageDigest
import java.time.LocalDateTime

/** Independent rules interpreter. Receipt fallback is explicit durable SMS metadata, never a device clock read. */
class RuleInterpreter(schema: BankRuleSchema) : BankInterpreter {
    private val snapshot = schema.copy(rules = schema.rules.map { it.copy(
        phrases = it.phrases.toList(), excludes = it.excludes.toList(),
        labels = it.labels.mapValues { entry -> entry.value.toList() },
        detailLabels = it.detailLabels.mapValues { entry -> entry.value.toList() },
        observations = it.observations.mapValues { entry -> entry.value.toList() }) },
        network = schema.network.copy(bankAliases = schema.network.bankAliases.mapValues { it.value.toList() },
            sourceBankLabels = schema.network.sourceBankLabels.toList(),
            destinationBankLabels = schema.network.destinationBankLabels.toList(),
            networkLabels = schema.network.networkLabels.toList(),
            unidentifiedExternalBankMarkers = schema.network.unidentifiedExternalBankMarkers.toList()))
    // Content identity prevents changing literals under an unchanged declared revision.
    val version = InterpretationVersion(1, VERSION, "literal-v${snapshot.schema}-r${snapshot.revision}-" +
        MessageDigest.getInstance("SHA-256").digest(snapshot.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) })

    override fun interpret(input: MessageInput, catalog: BankCatalog): Interpretation {
        val banks = catalog.candidates(input.sender)
        val blockers = linkedSetOf<Blocker>()
        val diagnostics = mutableListOf<InterpretationDiagnostic>()
        val evidence = mutableListOf<Evidence>()
        fun problem(code: String, blocker: Blocker = Blocker.VALIDATION_ERROR) {
            blockers += blocker
            diagnostics += InterpretationDiagnostic(code, code, DiagnosticSeverity.WARNING)
        }
        fun result(facts: Facts = Facts(MessageFamily.UNKNOWN), status: ParseStatus = ParseStatus.REVIEW_REQUIRED): Interpretation {
            if (facts.family == MessageFamily.UNKNOWN) blockers += Blocker.UNKNOWN_FAMILY
            return Interpretation(input.evidenceId, version, banks.singleOrNull(), facts, status,
                null, evidence.toList(), blockers.toSet(), diagnostics.toList())
        }
        if (banks.size != 1) {
            problem("SENDER_NOT_UNIQUE", if (banks.isEmpty()) Blocker.UNKNOWN_BANK else Blocker.AMBIGUOUS_BANK)
            return result()
        }
        if (input.body.length > MAX_BODY) { problem("MESSAGE_TOO_LONG"); return result() }
        val source = SourceText(input.body)
        fun contains(term: String) = source.phrase(term).containsMatchIn(source.text)
        // Security/non-execution gates are built in; a custom bank rule cannot exclude them.
        val security = SECURITY.flatMap { term -> source.phrase(term).findAll(source.text).toList() }
        if (security.isNotEmpty()) {
            security.forEach { evidence += source.evidence("security.otp", "family", it.range) }
            return result(Facts(MessageFamily.OTP), ParseStatus.NON_FINANCIAL)
        }
        source.segmentInlinePurchase()
        val validSchema = runCatching { snapshot.validate() }.isSuccess
        // Validate every configured literal before executing the closed grammar.
        val networkTerms = snapshot.network.bankAliases.values.flatten() + snapshot.network.sourceBankLabels +
            snapshot.network.destinationBankLabels + snapshot.network.networkLabels + snapshot.network.unidentifiedExternalBankMarkers
        if (!validSchema || "UNKNOWN" in snapshot.network.bankAliases || networkTerms.any { SourceText(it).text.none(Char::isLetterOrDigit) } || snapshot.rules.any {
                    (it.phrases + it.excludes + it.labels.values.flatten() + it.detailLabels.values.flatten() + it.observations.values.flatten()).any { term -> SourceText(term).text.none(Char::isLetterOrDigit) }
            }) {
            problem("UNSUPPORTED_RULE_CAPABILITY", Blocker.UNSUPPORTED_RULE_SCHEMA); return result()
        }
        // The first meaningful line supplies the family cue; party/reference fields cannot do so.
        val first = source.text.indexOfFirst(Char::isLetterOrDigit)
        val lineStart = if (first < 0) 0 else source.text.lastIndexOf('\n', first).let { it + 1 }
        val lineEnd = source.text.indexOf('\n', lineStart).let { if (it < 0) source.text.length else it }
        val rawTitle = source.text.substring(lineStart, lineEnd)
        val leading = rawTitle.length - rawTitle.trimStart().length
        val title = rawTitle.trimStart()
        val roleLabels = (snapshot.rules.flatMap { it.labels.values.flatten() + it.detailLabels.values.flatten() } +
            snapshot.network.sourceBankLabels + snapshot.network.destinationBankLabels + snapshot.network.networkLabels + ObservationExtractor.roleHeaders().values.flatten() + SECONDARY).distinct()
        val rolePrefixes = roleLabels.map { Regex("^" + source.phrase(it).pattern) }
        fun roleEnd(text: String) = rolePrefixes.mapNotNull { it.find(text)?.range?.last?.plus(1) }.maxOrNull() ?: 0
        val roleEnd = roleEnd(title)
        fun titleMatches(phrase: String) = source.phrase(phrase).findAll(title).filter { roleEnd == 0 || (it.range.first == 0 && it.range.last + 1 > roleEnd) }.toList()
        if (Regex("^ *اسم المستفيد *: *[^\\n]+\\n+ *الاسم المختصر *: *[^\\n]+\\n+ *حالة *: *(?:غير نشط|نشط) *(?:\\n+ *(?:حساب|بنك|في) *: *[^\\n]+)*$").matches(source.text.trim())) {
            evidence += source.evidence("notice.beneficiary.directory", "family", 0..(source.text.length - 1))
            return result(Facts(MessageFamily.NON_FINANCIAL), ParseStatus.NON_FINANCIAL)
        }
        val candidates = snapshot.rules.filter { rule -> rule.phrases.any { titleMatches(it).isNotEmpty() } && rule.excludes.none(::contains) }
        // A configured compound title (e.g. refund of a purchase) consumes its nested literal,
        // but independent movement cues on another span still conflict.
        val matching = candidates.filter { rule ->
            val spans = rule.phrases.flatMap(::titleMatches).map { it.range }
            spans.any { span -> candidates.filter { it.family == MessageFamily.REFUND && rule.family == MessageFamily.PURCHASE }.none { other ->
                other.phrases.flatMap(::titleMatches).any { it.range.first <= span.first && it.range.last >= span.last && it.range != span }
            } }
        }
        matching.forEach { rule -> rule.phrases.forEach { phrase -> titleMatches(phrase).forEach { match ->
            evidence += source.evidence(rule.id, "family", (lineStart + leading + match.range.first)..(lineStart + leading + match.range.last))
        } } }
        if (matching.isEmpty()) return result()
        if (matching.map { it.family to it.direction }.distinct().size != 1) {
            problem("FAMILY_OR_DIRECTION_CONFLICT", Blocker.CONFLICTING_FACTS); return result()
        }
        val family = matching.first().family
        val direction = matching.first().direction
        // A second bare movement cue still conflicts; moving it into a party field does not.
        snapshot.rules.filter { it.family != family && it.excludes.none(::contains) }.forEach { rule ->
            rule.phrases.forEach { phrase -> source.phrase(phrase).findAll(source.text).forEach { match ->
                val start = source.text.lastIndexOf('\n', match.range.first).let { it + 1 }
                val end = source.text.indexOf('\n', match.range.last).let { if (it < 0) source.text.length else it }
                val line = source.text.substring(start, end)
                val indent = line.length - line.trimStart().length
                val bound = roleEnd(line.trimStart())
                val cueAtStart = match.range.first == start + indent
                val consumedTitle = family == MessageFamily.REFUND && rule.family == MessageFamily.PURCHASE && start == lineStart && matching.flatMap { it.phrases }.flatMap(::titleMatches).any {
                    match.range.first >= lineStart + leading + it.range.first && match.range.last <= lineStart + leading + it.range.last
                }
                if (!consumedTitle && (bound == 0 || (cueAtStart && match.value.length > bound))) {
                    problem("FAMILY_CONTEXT_CONFLICT", Blocker.CONFLICTING_FACTS)
                    evidence += source.evidence(rule.id, "family", match.range)
                }
            } }
        }
        if (source.text.lineSequence().any { it.trimStart().let { line -> line.startsWith(">") || line.startsWith("```") || line.startsWith('"') || line.startsWith('«') || line.startsWith('“') } })
            problem("QUOTED_MESSAGE_CONTEXT")
        if (family !in INFORMATIONAL && BLOCKED.any(::contains)) problem("EXECUTION_NOT_PROVEN")
        if (CONTEXT.any(::contains) && family !in setOf(MessageFamily.NON_FINANCIAL, MessageFamily.BALANCE_NOTICE))
            problem("MOVEMENT_NOTICE_CONFLICT", Blocker.CONFLICTING_FACTS)
        if ((family == MessageFamily.TRANSFER_IN && OUTGOING.any(::contains)) ||
            (family == MessageFamily.TRANSFER_OUT && INCOMING.any(::contains)) ||
            (family !in setOf(MessageFamily.TRANSFER_IN, MessageFamily.TRANSFER_OUT) && TRANSFER_CONTEXT.any(::contains)))
            problem("TRANSFER_CONTEXT_CONFLICT", Blocker.CONFLICTING_FACTS)
        val supported = family in SUPPORTED
        if (!supported) problem("FAMILY_NOT_IMPLEMENTED", Blocker.UNSUPPORTED_RULE_SCHEMA)
        val expected = if (family in setOf(MessageFamily.REFUND, MessageFamily.TRANSFER_IN)) MoneyDirection.INCOMING else MoneyDirection.OUTGOING
        if (family !in INFORMATIONAL && direction != expected) problem("UNSAFE_DIRECTION", Blocker.CONFLICTING_FACTS)

        fun labels(field: ExtractionField) = matching.flatMap { it.labels[field].orEmpty() }.distinct()
        data class Hit<T>(val value: T, val range: IntRange, val field: ExtractionField)
        val details = DetailExtractor(source, matching.flatMap { it.detailLabels.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.flatten().distinct() }, evidence, ::problem, roleLabels, matching.flatMap {it.observations["fee.sar_unlabeled"].orEmpty()}.distinct()).read()
        val secondaryRanges = details.reserved.toMutableList()
        fun overlaps(range: IntRange) = secondaryRanges.any { range.first <= it.last && it.first <= range.last }
        // Reserve all known secondary roles even when the selected rule does not expose them.
        SECONDARY.forEach { label ->
            Regex("(?m)^ *" + source.phrase(label).pattern + "[ \\t]*:?[ \\t]*" + MoneyGrammar.VALUE).findAll(source.text).forEach { secondaryRanges += it.range }
        }
        fun money(field: ExtractionField): Money? {
            val hits = labels(field).flatMap { label ->
                val pattern = Regex("(?m)^ *" + source.phrase(label).pattern + "[ \\t]*:?[ \\t]*" + MoneyGrammar.VALUE)
                val matches = pattern.findAll(source.text).toList()
                Regex("(?m)^ *" + source.phrase(label).pattern).findAll(source.text).forEach { mention ->
                    val longerRole = labels(field).any { other -> SourceText(other).text.length > SourceText(label).text.length &&
                        Regex("(?m)^ *" + source.phrase(other).pattern).find(source.text, mention.range.first)?.range?.first == mention.range.first }
                    if (!longerRole && (field != ExtractionField.AMOUNT || !overlaps(mention.range)) && matches.none { it.range.first == mention.range.first })
                        problem("MALFORMED_${field.name}")
                }
                matches.mapNotNull { match ->
                    if (field == ExtractionField.AMOUNT && overlaps(match.range)) return@mapNotNull null
                    val value = MoneyGrammar.read(match)
                    if (value == null) { problem("INVALID_MONEY"); null }
                    else Hit(value, match.range, field)
                }
            }
            hits.forEach { evidence += source.evidence("extract.money", field.name, it.range) }
            if (hits.map { it.value }.distinct().size > 1) { problem("CONFLICTING_${field.name}", Blocker.CONFLICTING_FACTS); return null }
            return hits.firstOrNull()?.value
        }
        fun identifier(field: ExtractionField): String? {
            val hits = labels(field).flatMap { label ->
                Regex("(?m)^ *" + source.phrase(label).pattern + "[ \\t]*:?[ \\t]*([*x]*[0-9]{4})(?![\\p{L}\\p{N}_])")
                    .findAll(source.text).map { Hit(it.groupValues[1].takeLast(4), it.groups[1]!!.range, field) }.toList()
            }
            hits.forEach { evidence += source.evidence("extract.identifier", field.name, it.range) }
            if (hits.map { it.value }.distinct().size > 1) { problem("CONFLICTING_${field.name}", Blocker.CONFLICTING_FACTS); return null }
            return hits.firstOrNull()?.value
        }
        val balance = money(ExtractionField.AVAILABLE_BALANCE)
        val amount = if (family in INFORMATIONAL) null else money(ExtractionField.AMOUNT)
        val labeledCard = identifier(ExtractionField.CARD)
        val card = run {
            val pattern = Regex("(?m)^ *(?:بطاقة|card) *: *(?:credit|debit|ائتمانية|مدى)(?: +| *\\n+ *)(?:رقم|number) *: *([*x]*[0-9]{4})(?![\\p{L}\\p{N}_])")
            val hits = pattern.findAll(source.text).toList()
            val values = (listOfNotNull(labeledCard) + hits.map { it.groupValues[1].takeLast(4) }).distinct()
            if (values.size > 1) problem("CONFLICTING_CARD", Blocker.CONFLICTING_FACTS)
            hits.forEach { evidence += source.evidence("extract.card.number-role", "CARD", it.groups[1]!!.range) }
            values.singleOrNull()
        }
        val account = identifier(ExtractionField.SOURCE_ACCOUNT)
        val destination = identifier(ExtractionField.DESTINATION_ACCOUNT)
        val merchants = labels(ExtractionField.MERCHANT).flatMap { label ->
            Regex("(?m)^ *" + source.phrase(label).pattern + "(?:[ \\t]*:[ \\t]*|[ \\t]+)([^\\n\\r]+)").findAll(source.text).map { match ->
                val range = match.groups[1]!!.range
                evidence += source.evidence("extract.merchant", "MERCHANT", range)
                source.original(range).trim()
            }.toList()
        }.distinct()
        if (merchants.size > 1) problem("CONFLICTING_MERCHANT", Blocker.CONFLICTING_FACTS)
        val times = DateGrammar.timestamp.findAll(source.text).mapNotNull { match ->
            val lineStart = source.text.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
            val prefix = source.text.substring(lineStart, match.range.first).trim().removeSuffix(":").trim()
            if (prefix !in TIME_LABELS) {
                problem("UNBOUND_TIME_ROLE"); return@mapNotNull null
            }
            evidence += source.evidence("extract.time", "occurredAtLocal", match.range)
            DateGrammar.time(match.value).also { if (it == null) problem("INVALID_TIME") }
        }.distinct().toList()
        if (times.size > 1) problem("CONFLICTING_TIME", Blocker.CONFLICTING_FACTS)
        val receiptTime = times.isEmpty() && input.durableSmsReceipt && diagnostics.none {
            it.code in setOf("INVALID_TIME", "UNBOUND_TIME_ROLE", "CONFLICTING_TIME") }
        if(receiptTime && family !in INFORMATIONAL) diagnostics += InterpretationDiagnostic(
            "DURABLE_RECEIPT_TIME", "Transaction time uses the persisted SMS receipt", DiagnosticSeverity.INFO)
        if (family !in INFORMATIONAL) {
            if (amount == null || amount.amount.signum() <= 0) problem("MISSING_POSITIVE_PRINCIPAL")
            if (times.size != 1 && !receiptTime) problem("MISSING_TRANSACTION_TIME")
            if (family in setOf(MessageFamily.PURCHASE, MessageFamily.REFUND) && card == null && account == null && destination == null) problem("MISSING_CARD_ROLE")
            if (family in setOf(MessageFamily.WITHDRAWAL, MessageFamily.CARD_PAYMENT, MessageFamily.BILL_PAYMENT, MessageFamily.FINANCING_INSTALLMENT) && account == null) problem("MISSING_SOURCE_ROLE")
        }
        if (family == MessageFamily.CARD_PAYMENT && card == null) problem("MISSING_CARD_ROLE")
        if (family == MessageFamily.BILL_PAYMENT && details.biller == null) problem("MISSING_BILLER_ROLE")
        if (family == MessageFamily.FINANCING_INSTALLMENT && details.counterparty == null) problem("MISSING_FINANCING_ROLE")
        if (details.foreign != null && amount != null && details.foreign.currency == amount.currency && details.foreign != amount)
            problem("CONFLICTING_FOREIGN_PRINCIPAL", Blocker.CONFLICTING_FACTS)
        val observations = matching.flatMap { it.observations.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.flatten().distinct() }
        val network = TransferNetworkResolver(source, snapshot.network, observations, evidence, ::problem)
            .resolve(family, banks.single(), matching.flatMap { it.phrases }, details.counterparty != null && (if (family == MessageFamily.TRANSFER_IN) destination != null else account != null))
        val observed = ObservationExtractor(source, observations, family, matching.flatMap { it.phrases }, evidence, ::problem).read()
        if (observed.card == CardChannel.STATEMENT && family !in INFORMATIONAL) problem("STATEMENT_CHANNEL_IN_MOVEMENT", Blocker.CONFLICTING_FACTS)
        if (observed.balanceMarker && balance == null) problem("MISSING_OBSERVED_BALANCE")
        val transfer = family in setOf(MessageFamily.TRANSFER_IN, MessageFamily.TRANSFER_OUT)
        if (transfer && account == null && (family == MessageFamily.TRANSFER_OUT || details.counterparty == null)) problem("MISSING_TRANSFER_SOURCE")
        if (transfer && destination == null && (family == MessageFamily.TRANSFER_IN || details.counterparty == null)) problem("MISSING_TRANSFER_DESTINATION")
        val sourceBank = if (family == MessageFamily.TRANSFER_IN) {
            if (network.kind == BankNetwork.INTRA_BANK) banks.single() else network.source
        } else network.source ?: banks.single()
        val destinationBank = if (family == MessageFamily.TRANSFER_IN) network.destination ?: banks.single()
            else if (network.kind == BankNetwork.INTRA_BANK) banks.single() else network.destination
        // One original source range cannot authorize two different field roles.
        val roleEvidence = evidence.filter { it.field != "family" && !it.field.startsWith("observation.") }
        if (roleEvidence.any { a -> roleEvidence.any { b -> a.field != b.field && a.start < b.end && b.start < a.end } })
            problem("FIELD_ROLE_CONFLICT", Blocker.CONFLICTING_FACTS)
        val facts = Facts(family, amount, if (family in INFORMATIONAL) null else direction,
            sourceAccount = account?.let { AccountReference(sourceBank, it) },
            destinationAccount = destination?.let { AccountReference(destinationBank, it) },
            card = card?.let { CardReference(banks.single(), it) }, merchant = merchants.singleOrNull(),
            availableBalance = balance, occurredAtLocal = times.singleOrNull(),
            occurredAt = input.receivedAt.takeIf {receiptTime && family !in INFORMATIONAL}, outstandingBalance = details.due,
            labeledForeignAmount = details.foreign, internationalFee = details.fee, exchangeRate = details.rate,
            paymentDueDate = details.dueDate, transactionReference = details.reference, counterparty = details.counterparty,
            biller = details.biller, billerCode = details.billerCode, bankNetwork = network.kind,
            cardChannel = observed.card, purchaseChannel = observed.purchase, loanType = observed.loan,
            salaryIncomeWording = observed.salary,
            debitSourceAccountLast4 = account?.takeIf { observed.card == CardChannel.DEBIT && family in setOf(MessageFamily.PURCHASE, MessageFamily.REFUND) },
            executionState = if (family in INFORMATIONAL || blockers.isNotEmpty()) null else "EXECUTED")
        return result(facts, when {
            blockers.isNotEmpty() -> ParseStatus.REVIEW_REQUIRED
            family in INFORMATIONAL -> ParseStatus.NON_FINANCIAL
            else -> ParseStatus.SUCCESS
        })
    }

    companion object {
        const val VERSION = "bank-rules-independent-v9"
        private const val MAX_BODY = 16000
        private val TIME = Regex("(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}[t ][0-9]{2}:[0-9]{2}(?::[0-9]{2})?(?![0-9:])")
        private val TIME_LABELS = setOf("", "في", "on", "date", "time", "transaction time", "تاريخ العملية")
        private val SECURITY = listOf("otp", "one time password", "one-time password", "password", "passcode", "pin", "verification code", "security code", "authentication code", "رمز التحقق", "رمز تحقق", "رمز التوثيق", "رمز تاكيد", "رمز لمرة واحدة", "كلمة المرور", "كلمة مرور", "رمز التفعيل", "رمز سري")
        private val BLOCKED = listOf("declined", "rejected", "unsuccessful", "failed", "pending", "request", "cancelled", "canceled", "not", "never", "لم", "لن", "لا", "غير", "رفض", "مرفوض", "مرفوضة", "طلب", "قيد التنفيذ", "تعذر", "الغاء")
        private val CONTEXT = listOf("statement", "كشف حساب", "beneficiary added", "اضافة مستفيد", "advertisement", "offer", "عرض", "minimum payment", "payment due date", "تاريخ الاستحقاق")
        private val INCOMING = RuleLibrary.incomingTransferPhrases()
        private val OUTGOING = RuleLibrary.outgoingTransferPhrases()
        private val TRANSFER_CONTEXT = INCOMING + OUTGOING + listOf("fund transfer", "حوالة", "تحويل")
        private val SECONDARY = listOf("available balance", "balance", "الرصيد المتاح", "الرصيد", "due amount", "outstanding balance", "المبلغ المستحق", "fee amount", "مبلغ الرسوم", "tax amount", "مبلغ الضريبة", "minimum amount", "minimum payment", "الحد الادني للسداد", "foreign amount", "المبلغ الاجنبي", "international fee", "رسوم العمليات الدولية")
        private val INFORMATIONAL = setOf(MessageFamily.NON_FINANCIAL, MessageFamily.BALANCE_NOTICE)
        private val SUPPORTED = INFORMATIONAL + setOf(MessageFamily.PURCHASE, MessageFamily.REFUND, MessageFamily.WITHDRAWAL, MessageFamily.FEE, MessageFamily.CARD_PAYMENT, MessageFamily.BILL_PAYMENT, MessageFamily.FINANCING_INSTALLMENT, MessageFamily.TRANSFER_IN, MessageFamily.TRANSFER_OUT)
    }
}
