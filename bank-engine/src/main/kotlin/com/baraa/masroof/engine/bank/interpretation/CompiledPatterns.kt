package com.baraa.masroof.engine.bank.interpretation

import java.util.concurrent.ConcurrentHashMap

/**
 * Compiled regexes shared across messages. Interpretation builds its patterns from configured
 * labels, never from message text, so the same few hundred patterns repeat for every SMS.
 * Regex is immutable and safe to share between threads.
 */
internal object CompiledPatterns {
    private const val MAX_ENTRIES = 8_192
    private val cache = ConcurrentHashMap<String, Regex>()

    fun of(pattern: String): Regex = cache[pattern] ?: Regex(pattern).also {
        // shortcut: clearing drops every entry at once; a size-bounded LRU only if configurations grow past this.
        if (cache.size >= MAX_ENTRIES) cache.clear()
        cache[pattern] = it
    }
}
