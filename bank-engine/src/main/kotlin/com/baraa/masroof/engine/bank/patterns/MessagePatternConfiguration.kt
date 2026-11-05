package com.baraa.masroof.engine.bank.patterns

/** Editor snapshots; the store serializes/validates a complete document before replacing durable state. */
data class MessagePatternConfiguration(val generation: Long = 0, val banks: List<BankMessagePatterns> = emptyList())

data class BankMessagePatterns(
    val bankId: String,
    val senders: List<String>,
    val displayName: String? = null,
    /** Immutable revisions are retained. Only the latest revision of each id can be active. */
    val patterns: List<SavedMessagePattern> = emptyList(),
    /** Identifiers only: prevents bundled definitions from returning after an explicit permanent deletion. */
    val deletedPatternIds: Set<String> = emptySet(),
) {
    init {
        require(deletedPatternIds.size <= 512 && deletedPatternIds.all { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) })
        require(patterns.none { it.id in deletedPatternIds }) { "Deleted patterns cannot be restored as revisions" }
    }
    /** The configured sender that [sender] names under [SenderIdentity], or null. */
    fun configuredSender(sender: String): String? = senders.firstOrNull { SenderIdentity.same(it, sender) }
    fun ownsSender(sender: String): Boolean = configuredSender(sender) != null

    val currentPatterns: List<SavedMessagePattern>
        get() = patterns.groupBy { it.id }.values.map { versions -> versions.maxBy { it.revision } }.sortedBy { it.id }
}
