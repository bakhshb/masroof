package com.baraa.masroof.engine.bank.patterns

import com.baraa.masroof.engine.bank.*
import com.baraa.masroof.engine.bank.interpretation.MoneyGrammar
import com.baraa.masroof.engine.bank.interpretation.SourceText
import com.baraa.masroof.engine.bank.interpretation.otpEvidence
import com.baraa.masroof.engine.bank.interpretation.hasUnprovenExecution
import com.baraa.masroof.engine.bank.interpretation.hasNoticeContext
import com.baraa.masroof.engine.bank.interpretation.hasQuotedContext
import com.baraa.masroof.engine.bank.interpretation.FamilyAssist
import com.baraa.masroof.engine.bank.interpretation.RuleInterpreter
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.finance.value.Currency
import com.baraa.masroof.finance.value.Money
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Explicit observations, independently versioned from the preview label. Never ownership or ledger type. */
data class PatternFactContract(
    val family: MessageFamily,
    val direction: MoneyDirection,
    val currency: Currency? = null,
    val sourceAccountBank: BankId? = null,
    val destinationAccountBank: BankId? = null,
    val cardBank: BankId? = null,
    val version: Int = 1,
    val accountBindings: List<PatternAccountBinding> = emptyList(),
    val contextReferences: Set<String> = emptySet(),
) {
    init {
        require(accountBindings.size <= 2 && accountBindings.map { it.role }.distinct().size == accountBindings.size)
        require(contextReferences.all { it in PATTERN_REFERENCE_FIELDS })
        require(accountBindings.mapNotNull { it.field }.distinct().size == accountBindings.mapNotNull { it.field }.size)
        require(accountBindings.none { it.field in contextReferences })
    }
}

enum class PatternAccountRole { SOURCE, DESTINATION, CARD }

/** Exact user link to an existing canonical account or card. It declares no ownership. Null field is a definition constant. */
data class PatternAccountBinding(val role: PatternAccountRole, val bank: BankId, val last4: String,
    val field: String? = null, val observedValue: String? = null) {
    init {
        require(last4.matches(Regex("[0-9]{4}")))
        require((field == null) == (observedValue == null))
        require(field == null || field in setOf("source_account", "destination_account", "account_reference"))
        require(field == null || role != PatternAccountRole.CARD)
        require(field != "source_account" || role == PatternAccountRole.SOURCE)
        require(field != "destination_account" || role == PatternAccountRole.DESTINATION)
        require(observedValue == null || observedValue.matches(Regex("[0-9]{1,4}")))
        require(observedValue == null || observedValue.length < 4 || observedValue == last4)
    }
}

/** Fields that may name an account or bank; only these can be context references. */
val PATTERN_REFERENCE_FIELDS: Set<String> = setOf("source_account", "destination_account", "account_reference", "funding_reference", "counterparty_bank")

/** Values stay in Facts / original message spans; durable reasons contain only metadata and offsets. */
data class PatternProvenance(val configurationFingerprint: String, val patternId: String, val revision: Int,
    val definitionFacts: Set<String>, val usesReceiptTime: Boolean, val roleProfile: Boolean = false)

/** A message's interpretation and the enabled patterns it matched on the way. */
data class RoutedPreview(val interpretation: Interpretation, val matches: List<Pair<SavedMessagePattern, Preview>>)

/** Immutable configuration snapshot. Routing has already selected the bank; sender detection is not repeated. */
class PatternFactInterpreter(private val source: MessagePatternConfiguration) {
    private val snapshot = source.copy(banks = source.banks.map {
        it.copy(senders = it.senders.toList(), patterns = it.patterns.toList(), deletedPatternIds = it.deletedPatternIds.toSet())
    })
    val fingerprint: String = fingerprint(snapshot)

    // The snapshot is immutable, so each bank's merged role profile is built and validated once.
    private val roleInterpreters = java.util.concurrent.ConcurrentHashMap<String, RuleInterpreter>()

    fun interpretRouted(input: MessageInput, bank: BankId, familyAssist: FamilyAssist? = null): Interpretation =
        interpret(input, bank, familyAssist, null)

    /** One pass for previews: the interpretation plus every enabled pattern, taught or role, the message matches. */
    fun previewRouted(input: MessageInput, bank: BankId): RoutedPreview {
        var matched = emptyList<Pair<SavedMessagePattern, Preview>>()
        val interpretation = interpret(input, bank, null) { matched = it }
        return RoutedPreview(interpretation, matched)
    }

    private fun interpret(input: MessageInput, bank: BankId, familyAssist: FamilyAssist?,
        observe: ((List<Pair<SavedMessagePattern, Preview>>) -> Unit)?): Interpretation {
        val version = InterpretationVersion(1, "message-pattern-facts-v1", fingerprint)
        fun unresolved(code: String, blocker: Blocker = Blocker.UNKNOWN_FAMILY) = Interpretation(input.evidenceId,
            version, bank, Facts(MessageFamily.UNKNOWN), ParseStatus.REVIEW_REQUIRED, null, emptyList(),
            setOf(blocker, Blocker.UNKNOWN_FAMILY), listOf(diagnostic(code)))
        val definition = snapshot.banks.singleOrNull { it.bankId == bank.value }
            ?: return unresolved("PATTERN_BANK_MISSING", Blocker.UNKNOWN_BANK)
        val bodyText = SourceText(input.body)
        val security = otpEvidence(bodyText)
        if (security.isNotEmpty()) return Interpretation(input.evidenceId, version, bank, Facts(MessageFamily.OTP),
            ParseStatus.NON_FINANCIAL, null, security, emptySet())
        val contextBlocks by lazy { listOfNotNull("PATTERN_EXECUTION_NOT_PROVEN".takeIf { hasUnprovenExecution(bodyText) },
            "PATTERN_NOTICE_CONTEXT".takeIf { hasNoticeContext(bodyText) }, "PATTERN_QUOTED_MESSAGE_CONTEXT".takeIf { hasQuotedContext(bodyText) }) }
        fun readTaught(pattern: SavedMessagePattern, preview: Preview): Interpretation {
            val contract = checkNotNull(pattern.factContract)
            if (contract.version !in 1..2 || (contract.version == 1 && (contract.accountBindings.isNotEmpty() || contract.contextReferences.isNotEmpty())))
                return unresolved("PATTERN_CONTRACT_UNSUPPORTED", Blocker.UNSUPPORTED_RULE_SCHEMA)

            val evidence = preview.fields.map { (field, span) -> Evidence(pattern.id, field.uppercase(Locale.ROOT), span.start, span.end) }.toMutableList()
            val diagnostics = mutableListOf<InterpretationDiagnostic>()
            val definitionFacts = mutableSetOf("FAMILY", "DIRECTION")
            fun block(code: String) { diagnostics += diagnostic(code) }
            contextBlocks.forEach(::block)
            fun value(field: String) = preview.fields[field]?.raw
            fun normalized(field: String) = value(field)?.let { SourceText(it).text.trim() }
            val boundFields = mutableSetOf<String>()
            fun boundAccount(role: PatternAccountRole): AccountReference? {
                val binding = contract.accountBindings.singleOrNull { it.role == role } ?: return null
                if (binding.field != null) {
                    if (normalized(binding.field)?.let(::unmaskedReference) != binding.observedValue) { block("PATTERN_ACCOUNT_BINDING_MISMATCH"); return null }
                    boundFields += binding.field
                }
                definitionFacts += "${role.name}_ACCOUNT_IDENTITY"
                definitionFacts += "${role.name}_ACCOUNT_BANK"
                return AccountReference(binding.bank, binding.last4)
            }
            val sourceBinding = boundAccount(PatternAccountRole.SOURCE)
            val destinationBinding = boundAccount(PatternAccountRole.DESTINATION)
            fun money(field: String): Money? {
                val raw = normalized(field) ?: return null
                val match = MONEY.matchEntire(raw)
                val number = match?.groups?.get(1)?.value ?: match?.groups?.get(4)?.value
                val currencyText = match?.groups?.get(2)?.value ?: match?.groups?.get(3)?.value
                val observedCurrency = currencyText?.let { if (it == "sr" || it.startsWith("ر")) Currency.SAR else Currency.valueOf(it.uppercase(Locale.ROOT)) }
                val declaredCurrency = contract.currency.takeIf { field == "amount" }
                if (observedCurrency != null && declaredCurrency != null && observedCurrency != declaredCurrency) {
                    block("PATTERN_CURRENCY_CONFLICT"); return null
                }
                val currency = observedCurrency ?: declaredCurrency
                val magnitude = number ?: raw.takeIf { DECIMAL.matches(it) }
                if (currency == null || magnitude == null) { block("PATTERN_MONEY_INCOMPLETE"); return null }
                if (observedCurrency == null) definitionFacts += "${field.uppercase(Locale.ROOT)}_CURRENCY"
                else evidence += evidence.first { it.field == field.uppercase(Locale.ROOT) }.copy(field = "${field.uppercase(Locale.ROOT)}_CURRENCY")
                return Money.of(magnitude.replace(",", ""), currency)
            }
            fun account(field: String, referenceBank: BankId?): AccountReference? {
                if (field in contract.contextReferences || field in boundFields) return null
                val raw = normalized(field)?.let(::unmaskedReference) ?: return null
                if (!SUFFIX.matches(raw) || referenceBank == null) { block("PATTERN_ACCOUNT_REFERENCE_UNRESOLVED"); return null }
                definitionFacts += "${field.uppercase(Locale.ROOT)}_BANK"
                return AccountReference(referenceBank, raw)
            }
            val time = normalized("occurred_at")?.let { raw ->
                val kind = pattern.parts.filterIsInstance<FieldSlot>().single { it.field == "occurred_at" }.kind
                val parsed = kind.dateFormat?.let { format -> runCatching {
                    LocalDateTime.parse(raw, DateTimeFormatter.ofPattern(format).withResolverStyle(ResolverStyle.STRICT))
                }.getOrNull() }
                if (parsed == null) block("PATTERN_TIME_INCOMPLETE")
                parsed
            }
            val observedCard = normalized("card")?.let(::unmaskedReference)?.let {
                if (!SUFFIX.matches(it) || contract.cardBank == null) { block("PATTERN_CARD_REFERENCE_UNRESOLVED"); null }
                else { definitionFacts += "CARD_BANK"; CardReference(contract.cardBank, it) }
            }
            val cardLink = contract.accountBindings.singleOrNull { it.role == PatternAccountRole.CARD }
                ?.takeIf { contract.family in CARD_LINKED }?.let { CardReference(it.bank, it.last4) }
            if (cardLink != null && observedCard == null) definitionFacts += setOf("CARD_IDENTITY", "CARD_BANK")
            val card = observedCard ?: cardLink
            preview.fields.keys.filter { it !in SUPPORTED_FIELDS }.forEach { block("PATTERN_ROLE_UNSUPPORTED") }
            // Neutral / counterparty references retain original evidence, without padding, ownership or a guessed bank.
            if (preview.fields.keys.any { it in NEUTRAL_REFERENCES && it !in boundFields && it !in contract.contextReferences }) block("PATTERN_REFERENCE_ROLE_UNRESOLVED")
            if (contract.contextReferences.any { it !in preview.fields && it !in pattern.optionalTextFields }) block("PATTERN_REFERENCE_CONTEXT_MISSING")
            val receiptTime = "occurred_at" !in preview.fields
            if (receiptTime && !input.durableSmsReceipt) block("PATTERN_RECEIPT_NOT_DURABLE")
            if (contract.family == MessageFamily.UNKNOWN) block("PATTERN_FAMILY_UNKNOWN")
            val sourceObserved = account("source_account", contract.sourceAccountBank)
            val destinationObserved = account("destination_account", contract.destinationAccountBank)
            if (sourceBinding != null && sourceObserved != null && sourceBinding != sourceObserved) block("PATTERN_ACCOUNT_BINDING_CONFLICT")
            if (destinationBinding != null && destinationObserved != null && destinationBinding != destinationObserved) block("PATTERN_ACCOUNT_BINDING_CONFLICT")
            val facts = Facts(contract.family, amount = money("amount"), direction = contract.direction,
                sourceAccount = sourceBinding ?: sourceObserved,
                destinationAccount = destinationBinding ?: destinationObserved, card = card,
                merchant = value("merchant"), counterparty = value("counterparty"), biller = value("biller"),
                billerCode = value("biller_code"), transactionReference = value("reference"), occurredAtLocal = time,
                occurredAt = input.receivedAt.takeIf { receiptTime && input.durableSmsReceipt },
                availableBalance = money("available_balance"), outstandingBalance = money("outstanding_balance"),
                internationalFee = money("international_fee"), labeledForeignAmount = money("foreign_amount"))
            if (facts.amount == null) block("PATTERN_AMOUNT_MISSING")
            val blockers = buildSet {
                if (diagnostics.isNotEmpty()) add(Blocker.VALIDATION_ERROR)
                if (facts.family == MessageFamily.UNKNOWN) add(Blocker.UNKNOWN_FAMILY)
            }
            return Interpretation(input.evidenceId, version, bank, facts,
                if (blockers.isEmpty()) ParseStatus.SUCCESS else ParseStatus.REVIEW_REQUIRED,
                Confidence(1.0, emptyList()), evidence, blockers, diagnostics,
                patternProvenance = PatternProvenance(fingerprint, pattern.id, pattern.revision, definitionFacts.toSet(), receiptTime))
        }
        val taught = definition.currentPatterns.filter { it.roleProfile == null && it.sender in definition.senders }.mapNotNull { pattern ->
            // Use the pattern's declared sender only to invoke its body compiler, after routing.
            pattern.preview(bank.value, pattern.sender, input.body)?.let { pattern to it }
        }
        // An unapproved (preview-only) pattern is shown in previews but never competes with approved or bundled ones.
        val matches = taught.filter { it.first.factContract != null }
        val roles = definition.currentPatterns.filter { it.roleProfile != null && it.sender in definition.senders }
        val roleResult = if (roles.isEmpty()) null else {
            val profiles = roles.map { requireNotNull(it.roleProfile) }
            val profile = profiles.first()
            if (profiles.any { it.network != profile.network || it.schema != profile.schema }) {
                observe?.invoke(taught)
                return unresolved("PATTERN_PROFILE_CONFLICT", Blocker.CONFLICTING_FACTS)
            }
            roleInterpreters.getOrPut(bank.value) { RuleInterpreter(profile.copy(rules = profiles.flatMap { it.rules })) }
                .interpretRouted(input, bank, familyAssist)
        }
        val roleMatches = roles.filter { pattern -> roleResult?.evidence?.any { it.field == "family" && it.ruleId == pattern.id } == true }
        val activeRoles = roleMatches.filter { it.enabled }
        if (observe != null) {
            val roleFields = roleResult?.evidence.orEmpty().filter { it.field != "family" }
                .associate { span -> span.field.lowercase(Locale.ROOT) to TextSpan(span.slice(input), span.start, span.end) }
            observe(taught + activeRoles.map { it to Preview(it.label, roleFields) })
        }
        // A taught pattern may complete an incomplete recognized role, never contradict what it read.
        val completion = matches.singleOrNull()?.takeIf { (pattern, _) ->
            roleResult != null && roleResult.status != ParseStatus.SUCCESS && roleResult.facts.family != MessageFamily.UNKNOWN &&
                pattern.factContract?.family == roleResult.facts.family
        }?.let { (pattern, preview) -> readTaught(pattern, preview) }
        // An unreadable contract (family UNKNOWN) keeps its own diagnostic rather than reading as a conflict.
        val completesRole = completion != null &&
            (completion.facts.family == MessageFamily.UNKNOWN || consistent(completion, requireNotNull(roleResult)))
        if (roleResult != null && !completesRole && (matches.isEmpty() || activeRoles.isNotEmpty())) {
            // A complete role reading that every approved taught reading repeats exactly is not a conflict.
            if (matches.isNotEmpty() && (roleResult.status != ParseStatus.SUCCESS || !matches.all { (pattern, preview) ->
                    readTaught(pattern, preview).let { it.status == ParseStatus.SUCCESS && consistent(it, roleResult) } }))
                return unresolved("PATTERN_CONFLICT", Blocker.CONFLICTING_FACTS)
            val selected = activeRoles.firstOrNull() ?: roles.firstOrNull {
                it.enabled && roleResult.familyInference != null && it.factContract?.family == roleResult.facts.family
            }
            if ((selected == null && (roleMatches.isNotEmpty() || roleResult.status == ParseStatus.SUCCESS)) || roleMatches.any { !it.enabled })
                return unresolved("PATTERN_DISABLED")
            val stamp = selected ?: roles.first()
            val linked = selected?.factContract?.takeIf { it.version == 2 }?.accountBindings.orEmpty()
            return withLinks(roleResult, linked).copy(version = InterpretationVersion(1, "message-pattern-roles-v1", "$fingerprint:${stamp.id}:${stamp.revision}"),
                patternProvenance = PatternProvenance(fingerprint, stamp.id, stamp.revision, emptySet(), false, roleProfile = true))
        }
        if (roleResult?.blockers?.contains(Blocker.CONFLICTING_FACTS) == true)
            return unresolved("PATTERN_PROFILE_CONTEXT_CONFLICT", Blocker.CONFLICTING_FACTS)
        if (matches.isEmpty()) return unresolved(if (taught.isEmpty()) "PATTERN_NOT_MATCHED" else "PATTERN_CONTRACT_MISSING")
        if (matches.size != 1) return unresolved("PATTERN_CONFLICT", Blocker.CONFLICTING_FACTS)
        val (pattern, preview) = matches.single()
        return completion ?: readTaught(pattern, preview)
    }

    companion object {
        @Volatile private var last: PatternFactInterpreter? = null

        /**
         * The interpreter for [configuration], reused while the caller passes the same instance. The pattern store
         * returns one instance while its file is unchanged, so the fingerprint and compiled rules are built once.
         */
        fun of(configuration: MessagePatternConfiguration): PatternFactInterpreter =
            last?.takeIf { it.source === configuration } ?: PatternFactInterpreter(configuration).also { last = it }

        /**
         * The taught reading posts the same money as the role reading: family, direction, amount, accounts, card and
         * time. A complete role reading must be matched exactly; an incomplete one only on the facts, or account
         * parts, both read. Agreement is checked before [withLinks], which fills only roles the message left empty.
         */
        private fun consistent(taught: Interpretation, role: Interpretation): Boolean {
            val complete = role.status == ParseStatus.SUCCESS
            fun same(taughtValue: Any?, roleValue: Any?) = taughtValue == roleValue || (!complete && (taughtValue == null || roleValue == null))
            fun sameAccount(t: AccountReference?, r: AccountReference?) =
                same(t, r) || (t != null && r != null && !complete && same(t.bank, r.bank) && same(t.maskedNumber, r.maskedNumber))
            val a = taught.facts
            val b = role.facts
            return a.family == b.family && same(a.direction, b.direction) && same(a.amount, b.amount) && same(a.card, b.card) &&
                sameAccount(a.sourceAccount, b.sourceAccount) && sameAccount(a.destinationAccount, b.destinationAccount) &&
                same(a.occurredAtLocal, b.occurredAtLocal)
        }

        /**
         * Fills only a role the message left empty from the user's approved link; a value the message names stays
         * the message's. Purchases and refunds take only a card, never a funding account.
         */
        internal fun withLinks(result: Interpretation, links: List<PatternAccountBinding>): Interpretation {
            if (links.isEmpty() || result.status == ParseStatus.NON_FINANCIAL) return result
            var facts = result.facts
            val satisfied = mutableSetOf<String>()
            fun link(role: PatternAccountRole) = links.singleOrNull { it.role == role }
            val family = facts.family
            // A movement that names a card was paid by it; only a card payment names the card it settles.
            if (facts.sourceAccount == null && family in SOURCE_LINKED && (facts.card == null || family == MessageFamily.CARD_PAYMENT))
                link(PatternAccountRole.SOURCE)?.let {
                facts = facts.copy(sourceAccount = AccountReference(it.bank, it.last4)); satisfied += setOf("MISSING_TRANSFER_SOURCE", "MISSING_SOURCE_ROLE")
            }
            if (facts.destinationAccount == null && family == MessageFamily.TRANSFER_IN) link(PatternAccountRole.DESTINATION)?.let {
                facts = facts.copy(destinationAccount = AccountReference(it.bank, it.last4)); satisfied += "MISSING_TRANSFER_DESTINATION"
            }
            // A purchase that names a funding account is already complete; adding a card would change its channel.
            if (facts.card == null && family in CARD_LINKED &&
                (family == MessageFamily.CARD_PAYMENT || (facts.sourceAccount == null && facts.destinationAccount == null)))
                link(PatternAccountRole.CARD)?.let { facts = facts.copy(card = CardReference(it.bank, it.last4)); satisfied += "MISSING_CARD_ROLE" }
            if (facts == result.facts) return result
            val diagnostics = result.diagnostics.filterNot { it.code in satisfied }
            val open = diagnostics.any { it.severity != DiagnosticSeverity.INFO }
            if (open || result.blockers != setOf(Blocker.VALIDATION_ERROR)) return result.copy(facts = facts, diagnostics = diagnostics)
            return result.copy(facts = facts.copy(executionState = "EXECUTED"), diagnostics = diagnostics,
                blockers = emptySet(), status = ParseStatus.SUCCESS)
        }
        private val SOURCE_LINKED = setOf(MessageFamily.TRANSFER_OUT, MessageFamily.WITHDRAWAL, MessageFamily.CARD_PAYMENT,
            MessageFamily.BILL_PAYMENT, MessageFamily.FINANCING_INSTALLMENT, MessageFamily.FEE)
        val CARD_LINKED = setOf(MessageFamily.PURCHASE, MessageFamily.REFUND, MessageFamily.CARD_PAYMENT)
        private val DECIMAL = Regex(MoneyGrammar.NUMBER)
        // Groups: 1 number, 2 currency | 3 currency, 4 number. CURRENCY is non-capturing, so numbering is unchanged.
        private val MONEY = Regex("(?:(${MoneyGrammar.NUMBER}) *(${MoneyGrammar.CURRENCY})|(${MoneyGrammar.CURRENCY}) *(${MoneyGrammar.NUMBER}))")
        private val SUFFIX = Regex("[0-9]{4}")
        private val NEUTRAL_REFERENCES = setOf("account_reference", "funding_reference", "counterparty_bank")
        private val SUPPORTED_FIELDS = setOf("amount", "merchant", "counterparty", "biller", "biller_code", "reference",
            "occurred_at", "source_account", "destination_account", "card", "available_balance", "outstanding_balance",
            "international_fee", "foreign_amount") + NEUTRAL_REFERENCES
        private fun diagnostic(code: String) = InterpretationDiagnostic(code, code, DiagnosticSeverity.ERROR)

        /** Length-prefixed encoding includes history and generation, so restores and enable cycles cannot reuse a stamp. */
        private fun fingerprint(configuration: MessagePatternConfiguration): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeUTF("message-pattern-facts-v1"); out.writeLong(configuration.generation)
                out.writeInt(configuration.banks.size)
                configuration.banks.sortedBy { it.bankId }.forEach { bank ->
                    out.writeUTF(bank.bankId); out.writeUTF(bank.displayName.orEmpty()); out.writeInt(bank.senders.size)
                    bank.senders.sorted().forEach(out::writeUTF)
                    out.writeInt(bank.patterns.size)
                    bank.patterns.sortedWith(compareBy({ it.id }, { it.revision })).forEach { pattern ->
                        out.writeUTF(pattern.id); out.writeUTF(pattern.sender); out.writeUTF(pattern.label)
                        out.writeInt(pattern.revision); out.writeBoolean(pattern.enabled); out.writeInt(pattern.parts.size)
                        pattern.parts.forEach { part -> when (part) {
                            is StaticText -> { out.writeByte(0); out.writeUTF(part.value) }
                            is FieldSlot -> { out.writeByte(1); out.writeUTF(part.field); out.writeUTF(part.kind.name)
                                out.writeInt(part.width); out.writeBoolean(part.ignored) }
                        } }
                        out.writeBoolean(pattern.factContract != null)
                        pattern.factContract?.let {
                            out.writeInt(it.version); out.writeUTF(it.family.name); out.writeUTF(it.direction.name)
                            out.writeUTF(it.currency?.name.orEmpty()); out.writeUTF(it.sourceAccountBank?.value.orEmpty())
                            out.writeUTF(it.destinationAccountBank?.value.orEmpty()); out.writeUTF(it.cardBank?.value.orEmpty())
                            if (it.version >= 2) {
                                out.writeInt(it.accountBindings.size)
                                it.accountBindings.sortedBy { binding -> binding.role.name }.forEach { binding ->
                                    out.writeUTF(binding.role.name); out.writeUTF(binding.bank.value); out.writeUTF(binding.last4)
                                    out.writeUTF(binding.field.orEmpty()); out.writeUTF(binding.observedValue.orEmpty())
                                }
                                out.writeInt(it.contextReferences.size); it.contextReferences.sorted().forEach(out::writeUTF)
                            }
                        }
                        // Keep the v1/v2 stamp byte-for-byte compatible when no role matcher exists.
                        pattern.roleProfile?.let { out.writeUTF("role-profile-v1"); out.writeUTF(it.toString()) }
                    }
                    if (bank.deletedPatternIds.isNotEmpty()) {
                        out.writeUTF("deleted-patterns-v1"); out.writeInt(bank.deletedPatternIds.size)
                        bank.deletedPatternIds.sorted().forEach(out::writeUTF)
                    }
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).toHexString()
        }
    }
}

/** `*1234`, `xx1234`, `1234**` and `(1234)` name the same reference as `1234`; approval and interpretation share this. */
fun unmaskedReference(normalized: String): String =
    normalized.trim().replace(Regex("^[(*x•.]+|[)*x•.]+$"), "")
