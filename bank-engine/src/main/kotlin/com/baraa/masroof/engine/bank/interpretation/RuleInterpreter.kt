package com.baraa.masroof.engine.bank.interpretation

import com.baraa.masroof.engine.bank.*
import java.security.MessageDigest

/** Independent rules interpreter. Receipt fallback is explicit durable SMS metadata, never a device clock read. */
class RuleInterpreter(schema: BankRuleSchema, private val familyAssist: FamilyAssist? = null) : BankInterpreter {
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
            .toHexString())

    // The snapshot is immutable. Validate its shape and normalized literals once.
    private val supportedSchema = run {
        val networkTerms = snapshot.network.bankAliases.values.flatten() + snapshot.network.sourceBankLabels +
            snapshot.network.destinationBankLabels + snapshot.network.networkLabels + snapshot.network.unidentifiedExternalBankMarkers
        runCatching { snapshot.validate() }.isSuccess && "UNKNOWN" !in snapshot.network.bankAliases &&
            networkTerms.none { SourceText(it).text.none(Char::isLetterOrDigit) } && snapshot.rules.none {
                (it.phrases + it.excludes + it.labels.values.flatten() + it.detailLabels.values.flatten() + it.observations.values.flatten())
                    .any { term -> SourceText(term).text.none(Char::isLetterOrDigit) }
            }
    }

    override fun interpret(input: MessageInput, catalog: BankCatalog): Interpretation =
        interpretWithBanks(input, catalog.candidates(input.sender))

    /** Router-selected bank: never re-detect a sender after an explicit/stored bank choice. */
    fun interpretRouted(input: MessageInput, bank: com.baraa.masroof.finance.value.BankId): Interpretation =
        interpretWithBanks(input, setOf(bank))

    /** Same as [interpretRouted], with the assistance for this call, so one interpreter serves every caller. */
    fun interpretRouted(input: MessageInput, bank: com.baraa.masroof.finance.value.BankId, assist: FamilyAssist?): Interpretation =
        interpretWithBanks(input, setOf(bank), assist)

    private fun interpretWithBanks(input: MessageInput, banks: Set<com.baraa.masroof.finance.value.BankId>,
        familyAssist: FamilyAssist? = this.familyAssist): Interpretation {
        val blockers = linkedSetOf<Blocker>()
        val diagnostics = mutableListOf<InterpretationDiagnostic>()
        val evidence = mutableListOf<Evidence>()
        var inference: FamilySuggestion? = null
        fun problem(code: String, blocker: Blocker = Blocker.VALIDATION_ERROR) {
            blockers += blocker
            diagnostics += InterpretationDiagnostic(code, code, DiagnosticSeverity.WARNING)
        }
        fun result(facts: Facts = Facts(MessageFamily.UNKNOWN), status: ParseStatus = ParseStatus.REVIEW_REQUIRED): Interpretation {
            if (facts.family == MessageFamily.UNKNOWN) blockers += Blocker.UNKNOWN_FAMILY
            return Interpretation(input.evidenceId, version, banks.singleOrNull(), facts, status,
                inference?.let { Confidence(it.score,listOf("family_origin:${it.origin}","model:${it.modelVersion}")) }, evidence.toList(), blockers.toSet(), diagnostics.toList(), inference)
        }
        if (banks.size != 1) {
            problem("SENDER_NOT_UNIQUE", if (banks.isEmpty()) Blocker.UNKNOWN_BANK else Blocker.AMBIGUOUS_BANK)
            return result()
        }
        if (input.body.length > MAX_BODY) { problem("MESSAGE_TOO_LONG"); return result() }
        val source = SourceText(input.body)
        fun contains(term: String) = source.phrase(term).containsMatchIn(source.text)
        // Security/non-execution gates are built in; a custom bank rule cannot exclude them.
        val security = otpEvidence(source)
        if (security.isNotEmpty()) {
            evidence += security
            return result(Facts(MessageFamily.OTP), ParseStatus.NON_FINANCIAL)
        }
        source.segmentInlinePurchase()
        if (!supportedSchema) {
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
        val rolePrefixes = roleLabels.map { CompiledPatterns.of("^" + source.phrase(it).pattern) }
        fun roleEnd(text: String) = rolePrefixes.mapNotNull { it.find(text)?.range?.last?.plus(1) }.maxOrNull() ?: 0
        val roleEnd = roleEnd(title)
        // Arabic conjunctions may join two independent family cues; never hide that collision.
        fun titleMatches(phrase: String) = (if (phrase.firstOrNull() in '\u0600'..'\u06ff') listOf(phrase, "و$phrase") else listOf(phrase))
            .flatMap { source.phrase(it).findAll(title).toList() }
            .filter { roleEnd == 0 || (it.range.first == 0 && it.range.last + 1 > roleEnd) }
        if (CompiledPatterns.of("^ *اسم المستفيد *: *[^\\n]+\\n+ *الاسم المختصر *: *[^\\n]+\\n+ *حالة *: *(?:غير نشط|نشط) *(?:\\n+ *(?:حساب|بنك|في) *: *[^\\n]+)*$").matches(source.text.trim())) {
            evidence += source.evidence("notice.beneficiary.directory", "family", 0..(source.text.length - 1))
            return result(Facts(MessageFamily.NON_FINANCIAL), ParseStatus.NON_FINANCIAL)
        }
        val spansByRule = snapshot.rules.associateWith { rule -> rule.phrases.flatMap(::titleMatches).map { it.range } }
        val candidates = snapshot.rules.filter { rule -> spansByRule.getValue(rule).isNotEmpty() && rule.excludes.none(::contains) }
        // A configured compound title (e.g. refund of a purchase) consumes its nested literal,
        // but independent movement cues on another span still conflict.
        val refundSpans = candidates.filter { it.family == MessageFamily.REFUND }.flatMap { spansByRule.getValue(it) }
        var matching = candidates.filter { rule ->
            rule.family != MessageFamily.PURCHASE || spansByRule.getValue(rule).any { span ->
                refundSpans.none { it.first <= span.first && it.last >= span.last && it != span }
            }
        }
        matching.forEach { rule -> spansByRule.getValue(rule).forEach { span ->
            evidence += source.evidence(rule.id, "family", (lineStart + leading + span.first)..(lineStart + leading + span.last))
        } }
        if (matching.isNotEmpty() && matching.map { it.family to it.direction }.distinct().size != 1) {
            problem("FAMILY_OR_DIRECTION_CONFLICT", Blocker.CONFLICTING_FACTS); return result()
        }
        if (matching.isEmpty()) {
            val suggestion = familyAssist?.suggest(input,title)
            val supported = suggestion?.let { candidate -> snapshot.rules.filter {it.family==candidate.family} }.orEmpty()
            if (suggestion!=null && supported.isNotEmpty() && suggestion.family in assistFinancialFamilies) {
                inference = suggestion
                matching = supported
                if (lineEnd>lineStart+leading) evidence += source.evidence("family.${suggestion.origin.name.lowercase()}","family",(lineStart+leading)..(lineEnd-1))
            }
        }
        val family = matching.firstOrNull()?.family ?: MessageFamily.UNKNOWN
        val direction = matching.firstOrNull()?.direction ?: MoneyDirection.UNKNOWN
        if (family==MessageFamily.UNKNOWN) {
            problem("UNKNOWN_FAMILY",Blocker.UNKNOWN_FAMILY)
            matching = snapshot.rules // Review-only extraction of labelled facts; never automatic authority.
        }
        val familyPhrases = matching.flatMap {it.phrases} + listOfNotNull(title.takeIf {inference!=null})
        val matchingTitleSpans = matching.flatMap { spansByRule.getValue(it) }
        // A second bare movement cue still conflicts; moving it into a party field does not.
        snapshot.rules.filter { it.family != family && it.excludes.none(::contains) }.forEach { rule ->
            rule.phrases.forEach { phrase -> source.phrase(phrase).findAll(source.text).forEach { match ->
                val start = source.text.lastIndexOf('\n', match.range.first).let { it + 1 }
                val end = source.text.indexOf('\n', match.range.last).let { if (it < 0) source.text.length else it }
                val line = source.text.substring(start, end)
                val indent = line.length - line.trimStart().length
                val bound = roleEnd(line.trimStart())
                val cueAtStart = match.range.first == start + indent
                val consumedTitle = family == MessageFamily.REFUND && rule.family == MessageFamily.PURCHASE && start == lineStart && matchingTitleSpans.any {
                    match.range.first >= lineStart + leading + it.first && match.range.last <= lineStart + leading + it.last
                }
                if (!consumedTitle && (bound == 0 || (cueAtStart && match.value.length > bound))) {
                    problem("FAMILY_CONTEXT_CONFLICT", Blocker.CONFLICTING_FACTS)
                    evidence += source.evidence(rule.id, "family", match.range)
                }
            } }
        }
        if (hasQuotedContext(source)) problem("QUOTED_MESSAGE_CONTEXT")
        if (family !in INFORMATIONAL && hasUnprovenExecution(source)) problem("EXECUTION_NOT_PROVEN")
        if (hasNoticeContext(source) && family !in setOf(MessageFamily.NON_FINANCIAL, MessageFamily.BALANCE_NOTICE))
            problem("MOVEMENT_NOTICE_CONFLICT", Blocker.CONFLICTING_FACTS)
        if ((family == MessageFamily.TRANSFER_IN && OUTGOING.any(::contains)) ||
            (family == MessageFamily.TRANSFER_OUT && INCOMING.any(::contains)) ||
            (family !in setOf(MessageFamily.TRANSFER_IN, MessageFamily.TRANSFER_OUT, MessageFamily.UNKNOWN) && TRANSFER_CONTEXT.any(::contains)))
            problem("TRANSFER_CONTEXT_CONFLICT", Blocker.CONFLICTING_FACTS)
        val supported = family in SUPPORTED
        if (!supported) problem("FAMILY_NOT_IMPLEMENTED", Blocker.UNSUPPORTED_RULE_SCHEMA)
        val expected = if (family in setOf(MessageFamily.REFUND, MessageFamily.TRANSFER_IN)) MoneyDirection.INCOMING else MoneyDirection.OUTGOING
        // UNKNOWN is already blocked; reporting it (or transfer wording above) as a conflict would also veto user-taught patterns.
        if (family !in INFORMATIONAL && family != MessageFamily.UNKNOWN && direction != expected) problem("UNSAFE_DIRECTION", Blocker.CONFLICTING_FACTS)

        fun labels(field: ExtractionField): List<String> {
            val sets=matching.map {it.labels[field].orEmpty()}
            // Unknown messages retain only unambiguous merchant labels. A loan/account "from"
            // role must not become a merchant merely because a refund profile also uses it.
            return if (family==MessageFamily.UNKNOWN && field==ExtractionField.MERCHANT)
                sets.drop(1).fold(sets.firstOrNull().orEmpty().toSet()) {common,next->common.intersect(next.toSet())}.toList()
            // Built in like TIME_LABELS: stored bank definitions are never rewritten, so common Arabic
            // amount roles (ب١٠٠ريال, بقيمة 100 ريال) must reach existing installs through the engine.
            else (sets.flatten() + if (field == ExtractionField.AMOUNT) AMOUNT_ROLES else emptyList()).distinct()
        }
        // Opt-in rule capabilities apply only when the message title carries one of the rule's listed phrases.
        fun titleOptIn(key: String) = matching.flatMap { it.observations[key].orEmpty() }.filter { source.phrase(it).containsMatchIn(title) }
        val suffixLabels = titleOptIn("label.suffix").isNotEmpty()
        val currencies = RoleLineReader(source, mapOf("principalCurrency" to listOf("العملة", "currency")), evidence, roleLabels, suffixLabels)
            .read("principalCurrency")
        val currency = currencies.map { it.normalized }.distinct().singleOrNull()
        if (currencies.isNotEmpty() && (currency == null || MoneyGrammar.withCurrency("1", currency) == null))
            problem("INVALID_OR_CONFLICTING_PRINCIPAL_CURRENCY", Blocker.CONFLICTING_FACTS)
        val details = DetailExtractor(source, matching.flatMap { it.detailLabels.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.flatten().distinct() }, evidence, ::problem, roleLabels, matching.flatMap {it.observations["fee.sar_unlabeled"].orEmpty()}.distinct(), suffixLabels).read()
        val secondaryRanges = details.reserved.toMutableList()
        // Reserve all known secondary roles even when the selected rule does not expose them.
        SECONDARY.forEach { label ->
            CompiledPatterns.of("(?m)^ *" + source.role(label).pattern + "[ \\t]*:?[ \\t]*" + MoneyGrammar.VALUE).findAll(source.text).forEach { secondaryRanges += it.range }
        }
        val primary = PrimaryFieldExtractor(source, ::labels, currency, secondaryRanges, evidence, ::problem, suffixLabels, AMOUNT_ROLES)
        val balance = primary.money(ExtractionField.AVAILABLE_BALANCE)
        val amount = if (family in INFORMATIONAL) null else primary.money(ExtractionField.AMOUNT,
            standalone = titleOptIn("amount.standalone").isNotEmpty(), titlePrefixes = titleOptIn("amount.title"))
        val card = primary.card()
        val account = primary.identifier(ExtractionField.SOURCE_ACCOUNT)
        val destination = primary.identifier(ExtractionField.DESTINATION_ACCOUNT)
        val merchant = primary.merchant()
        val times = primary.transactionTimes(ignoreUnbound = family in INFORMATIONAL)
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
            .resolve(family, banks.single(), familyPhrases, details.counterparty != null && (if (family == MessageFamily.TRANSFER_IN) destination != null else account != null))
        val observed = ObservationExtractor(source, observations, family, familyPhrases, evidence, ::problem).read()
        if (observed.card == CardChannel.STATEMENT && family !in INFORMATIONAL) problem("STATEMENT_CHANNEL_IN_MOVEMENT", Blocker.CONFLICTING_FACTS)
        if (observed.balanceMarker && balance == null) problem("MISSING_OBSERVED_BALANCE")
        val transfer = family in setOf(MessageFamily.TRANSFER_IN, MessageFamily.TRANSFER_OUT)
        if (transfer && account == null && (family == MessageFamily.TRANSFER_OUT || details.counterparty == null)) problem("MISSING_TRANSFER_SOURCE")
        if (transfer && destination == null && (family == MessageFamily.TRANSFER_IN || details.counterparty == null)) problem("MISSING_TRANSFER_DESTINATION")
        val sourceBank = if (family == MessageFamily.TRANSFER_IN) {
            if (network.kind == BankNetwork.INTRA_BANK) banks.single() else network.source
        } else network.source ?: banks.single()
        val destinationBank = if (family in setOf(MessageFamily.TRANSFER_IN, MessageFamily.REFUND)) network.destination ?: banks.single()
            else if (network.kind == BankNetwork.INTRA_BANK) banks.single() else network.destination
        // One original source range cannot authorize two different field roles.
        val roleEvidence = evidence.filter { it.field != "family" && !it.field.startsWith("observation.") }
        if (roleEvidence.any { a -> roleEvidence.any { b -> a.field != b.field && a.start < b.end && b.start < a.end } })
            problem("FIELD_ROLE_CONFLICT", Blocker.CONFLICTING_FACTS)
        val familyCollision = diagnostics.any { it.code in setOf("FAMILY_CONTEXT_CONFLICT", "MOVEMENT_NOTICE_CONFLICT", "TRANSFER_CONTEXT_CONFLICT", "STATEMENT_CHANNEL_IN_MOVEMENT") }
        val facts = Facts(if (familyCollision) MessageFamily.UNKNOWN else family, amount, if (family in INFORMATIONAL || family==MessageFamily.UNKNOWN) null else direction,
            sourceAccount = account?.let { AccountReference(sourceBank, it) },
            destinationAccount = destination?.let { AccountReference(destinationBank, it) },
            card = card?.let { CardReference(banks.single(), it) }, merchant = merchant,
            availableBalance = balance, occurredAtLocal = times.singleOrNull(),
            occurredAt = input.receivedAt.takeIf {receiptTime && family !in INFORMATIONAL}, outstandingBalance = details.due,
            labeledForeignAmount = details.foreign ?: amount?.takeIf { it.currency != com.baraa.masroof.finance.value.Currency.SAR }, internationalFee = details.fee?.takeIf { family in setOf(MessageFamily.PURCHASE,MessageFamily.REFUND) }, exchangeRate = details.rate,
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
        const val VERSION = "bank-rules-independent-v15"
        private const val MAX_BODY = 16000
        private val INCOMING = listOf("حوالة واردة", "حوالة مالية واردة", "حوالة فورية واردة", "تحويل وارد", "incoming transfer", "fund transfer credited", "salary credited")
        private val OUTGOING = listOf("حوالة صادرة", "حوالة مالية صادرة", "حوالة فورية صادرة", "تحويل صادر", "outgoing transfer", "fund transfer debited", "transfer sent")
        private val TRANSFER_CONTEXT = INCOMING + OUTGOING + listOf("fund transfer", "حوالة", "تحويل")
        private val SECONDARY = listOf("رسوم:", "available balance", "balance", "الرصيد المتاح", "الرصيد", "due amount", "outstanding balance", "المبلغ المستحق", "fee amount", "مبلغ الرسوم", "tax amount", "مبلغ الضريبة", "minimum amount", "minimum payment", "الحد الادني للسداد", "foreign amount", "المبلغ الاجنبي", "international fee", "رسوم العمليات الدولية")
        private val AMOUNT_ROLES = listOf("ب", "بقيمة", "بمبلغ")
        private val INFORMATIONAL = setOf(MessageFamily.NON_FINANCIAL, MessageFamily.BALANCE_NOTICE)
        private val SUPPORTED = INFORMATIONAL + setOf(MessageFamily.PURCHASE, MessageFamily.REFUND, MessageFamily.WITHDRAWAL, MessageFamily.FEE, MessageFamily.CARD_PAYMENT, MessageFamily.BILL_PAYMENT, MessageFamily.FINANCING_INSTALLMENT, MessageFamily.TRANSFER_IN, MessageFamily.TRANSFER_OUT)
    }
}
