package com.baraa.masroof.engine.bank.patterns

/**
 * The one rule for whether two sender names are the same sender: case, spacing, punctuation and Arabic letter
 * forms do not matter, and a promotional suffix written after a separator (`AlJazira-AD`) still names the sender.
 * Detection, sender ownership and teaching all use it, so a sender routed to a bank is also one that bank owns.
 */
object SenderIdentity {
    private val PROMO_SUFFIX = Regex("(?i)[\\s\\-_.]+ad$")

    /** Comparison key; empty when the name has no letter or digit. */
    fun key(sender: String): String = buildString {
        for (c in sender) {
            val type = Character.getType(c)
            if (type == Character.FORMAT.toInt() || type == Character.NON_SPACING_MARK.toInt() || c == 'ـ') continue
            val folded = when (c) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                'ة' -> 'ه'
                else -> c.lowercaseChar()
            }
            if (folded.isLetterOrDigit()) append(folded)
        }
    }

    /** The key without a promotional suffix, or null when there is none. */
    fun promotionalKey(sender: String): String? = sender.trim().takeIf { PROMO_SUFFIX.containsMatchIn(it) }
        ?.replace(PROMO_SUFFIX, "")?.let(::key)?.takeIf { it.isNotEmpty() }

    fun same(configured: String, incoming: String): Boolean {
        val wanted = key(configured)
        return wanted.isNotEmpty() && (wanted == key(incoming) || wanted == promotionalKey(incoming) ||
            key(incoming) == promotionalKey(configured))
    }
}
