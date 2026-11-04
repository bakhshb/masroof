package com.baraa.masroof.engine.bank.patterns

import com.baraa.masroof.engine.bank.interpretation.SourceText
import com.baraa.masroof.engine.bank.BankRuleSchema
import java.util.Collections

data class FieldChoice(val field: String, val start: Int, val end: Int, val ignored: Boolean = false)
internal data class UserFieldBinding(val selection: Selection, val ignored: Boolean = false, val directlySelected: Boolean = false)
sealed interface PatternPart
data class StaticText(val value: String) : PatternPart
data class FieldSlot(val field: String, val kind: FieldKind, val width: Int, val ignored: Boolean) : PatternPart {
    init {
        require(field.matches(Regex("[a-z][a-z0-9_]{0,47}")))
        require(if (kind == FieldKind.DIGITS) width in 1..160 else width == 0)
    }
    fun representative(): String = when (kind) {
        FieldKind.MONEY -> "1SAR"
        FieldKind.DECIMAL -> "1.00"
        FieldKind.TEXT -> "x"
        FieldKind.DIGITS -> "0".repeat(width)
        FieldKind.DATE_DMY_SHORT -> "01/01/00 00:00"
        FieldKind.DATE_DMY_LONG -> "01-01-2000 00:00"
        FieldKind.DATE_YMD -> "2000-01-01 00:00"
        FieldKind.TIME_DMY_LONG -> "00:00 01-01-2000"
        FieldKind.TIME_YMD -> "00:00 2000-01-01"
        FieldKind.DATE_DMY_ONLY -> "01/01/2000"
        FieldKind.DATE_YMD_ONLY -> "2000-01-01"
    }
}

/** Only declarative literals and typed slots survive; original selected values are not stored. */
class SavedMessagePattern(val id: String, val bankId: String, val sender: String, val label: String,
    val revision: Int = 1, val enabled: Boolean = true, parts: List<PatternPart>,
    factContract: PatternFactContract? = null, roleProfile: BankRuleSchema? = null) {
    val parts: List<PatternPart> = Collections.unmodifiableList(parts.toList())
    // Banks may leave the neutral account-reference line blank. Identity bindings still require an exact value.
    internal val optionalTextFields = this.parts.filterIsInstance<FieldSlot>()
        .filter { it.field == "account_reference" && it.kind == FieldKind.TEXT }.map { it.field }.toSet()
    val factContract = factContract?.copy(accountBindings = Collections.unmodifiableList(factContract.accountBindings.toList()),
        contextReferences = Collections.unmodifiableSet(factContract.contextReferences.toSet()))
    val roleProfile: BankRuleSchema? = roleProfile?.frozen()

    init {
        require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}")) && bankId.isNotBlank() && bankId != "UNKNOWN")
        require(sender.length in 1..128 && label.length in 1..64 && revision > 0)
        if (this.roleProfile != null) {
            this.roleProfile.validate()
            val rule = this.roleProfile.rules.single()
            // Approval may add exact account/card links; every other built-in fact stays the rule's own.
            val contract = requireNotNull(this.factContract)
            require(this.parts.isEmpty() && rule.id == id && contract.accountBindings.none { it.field != null } &&
                contract.copy(version = 1, accountBindings = emptyList()) == PatternFactContract(rule.family, rule.direction))
        } else require(this.parts.size in 3..33 && this.parts.size % 2 == 1)
        require(this.parts.withIndex().all { (index, part) -> if (index % 2 == 0) part is StaticText else part is FieldSlot })
        require(this.parts.filterIsInstance<StaticText>().sumOf { it.value.length } <= 4096)
        val slots = this.parts.filterIsInstance<FieldSlot>()
        require(slots.map { it.field }.distinct().size == slots.size)
    }

    private val compiled: ExampleTemplate by lazy {
        val selections = mutableListOf<Selection>()
        val synthetic = buildString {
            this@SavedMessagePattern.parts.forEach { part -> when (part) {
                is StaticText -> append(part.value)
                is FieldSlot -> {
                    val start = length
                    append(part.representative())
                    selections += Selection(part.field, part.kind, start, length, allowEmpty = part.field in optionalTextFields)
                }
            } }
        }
        // Reuse the trusted compiler with generated representatives; no original teaching message is needed.
        ExampleTemplate.learn(sender, label, synthetic, selections)
    }

    fun validate() { if (roleProfile == null) compiled else roleProfile.validate() }

    fun copy(id: String = this.id, bankId: String = this.bankId, sender: String = this.sender,
        label: String = this.label, revision: Int = this.revision, enabled: Boolean = this.enabled,
        parts: List<PatternPart> = this.parts, factContract: PatternFactContract? = this.factContract,
        roleProfile: BankRuleSchema? = this.roleProfile) =
        SavedMessagePattern(id, bankId, sender, label, revision, enabled, parts, factContract, roleProfile)

    override fun equals(other: Any?): Boolean = other is SavedMessagePattern && id == other.id &&
        bankId == other.bankId && sender == other.sender && label == other.label && revision == other.revision &&
        enabled == other.enabled && parts == other.parts && factContract == other.factContract && roleProfile == other.roleProfile

    override fun hashCode(): Int = listOf(id, bankId, sender, label, revision, enabled, parts, factContract, roleProfile).hashCode()

    fun preview(bankId: String, sender: String, body: String): Preview? {
        if (!enabled || bankId != this.bankId) return null
        if (roleProfile != null) return if (sender == this.sender) previewRoles(body) else null
        val result = compiled.preview(sender, body) ?: return null
        val ignored = parts.filterIsInstance<FieldSlot>().filter { it.ignored }.map { it.field }.toSet()
        return result.copy(fields = result.fields.filterKeys { it !in ignored })
    }

    companion object {
        private val numericMoneyFields = setOf("amount", "available_balance", "outstanding_balance",
            "international_fee", "foreign_amount", "due_amount")

        fun fromChoices(id: String, bankId: String, sender: String, label: String, body: String,
            choices: List<FieldChoice>): SavedMessagePattern {
            val bindings = choices.map { choice ->
                require(choice.start in 0 until choice.end && choice.end <= body.length)
                val raw = SourceText(body.substring(choice.start, choice.end)).text
                val kind = when {
                    choice.field in numericMoneyFields && raw.matches(Regex(FieldKind.DECIMAL.pattern(raw))) -> FieldKind.DECIMAL
                    raw.matches(Regex("[0-9]+")) -> FieldKind.DIGITS
                    raw.matches(Regex(FieldKind.MONEY.pattern(raw))) && FieldKind.MONEY.accepts(raw) -> FieldKind.MONEY
                    else -> FieldKind.entries.firstOrNull { it.dateFormat != null && raw.matches(Regex(it.pattern(raw))) && it.accepts(raw) }
                        ?: FieldKind.TEXT
                }
                // Explicit user ranges also cover values discovery could not offer.
                UserFieldBinding(Selection(choice.field, kind, choice.start, choice.end), ignored = choice.ignored, directlySelected = true)
            }
            return fromBindings(id, bankId, sender, label, body, bindings)
        }

        internal fun fromBindings(id: String, bankId: String, sender: String, label: String, body: String,
            bindings: List<UserFieldBinding>): SavedMessagePattern {
            val ordered = bindings.sortedBy { it.selection.start }
            val candidates = GenericCandidateExtractor.extract(body)
            require(ordered.all { binding -> binding.directlySelected || candidates.any {
                it.text.start <= binding.selection.start && it.text.end >= binding.selection.end
            } }) { "A binding must come from a candidate or an explicit direct selection" }
            ExampleTemplate.learn(sender, label, body, ordered.map { it.selection })
            var cursor = 0
            val parts = buildList {
                ordered.forEach { binding ->
                    val selection = binding.selection
                    add(StaticText(body.substring(cursor, selection.start)))
                    add(FieldSlot(selection.field, selection.kind,
                        if (selection.kind == FieldKind.DIGITS) SourceText(body.substring(selection.start, selection.end)).text.length else 0,
                        binding.ignored))
                    cursor = selection.end
                }
                add(StaticText(body.substring(cursor)))
            }
            return SavedMessagePattern(id, bankId, sender, label, parts = parts).also { it.validate() }
        }
    }
}
