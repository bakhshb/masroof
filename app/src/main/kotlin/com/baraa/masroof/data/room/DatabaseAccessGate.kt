package com.baraa.masroof.data.room

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Drains repository operations before replacing files; a closed process instance is never reused. */
class DatabaseAccessGate {
    private val state = Mutex()
    private val exclusiveEdits = Mutex()
    private var active = 0
    private var restoring: CompletableDeferred<Unit>? = null
    private var drained: CompletableDeferred<Unit>? = null
    private val retirement = CompletableDeferred<Unit>()
    @Volatile private var retired = false

    suspend fun <T> withAccess(block: suspend () -> T): T {
        if (coroutineContext[Lease]?.gate === this) return block()
        checkAvailable()
        while (true) {
            val wait = state.withLock {
                checkAvailable()
                restoring.also { if (it == null) active++ }
            }
            if (wait == null) break
            select<Unit> {
                wait.onAwait { }
                // Restore may need to join this reader before leaving its exclusive block.
                // No Room operation has been admitted yet, so retire the waiter immediately.
                retirement.onAwait { throw DatabaseRestartRequiredException() }
            }
        }
        try {
            return withContext(Lease(this)) { block() }
        } finally {
            withContext(NonCancellable) {
                state.withLock {
                    active--
                    if (active == 0) drained?.complete(Unit)
                }
            }
        }
    }

    suspend fun <T> withRestore(block: suspend () -> T): T {
        check(coroutineContext[Lease]?.gate !== this) { "Restore cannot run inside repository access" }
        val finished = CompletableDeferred<Unit>()
        val idle = state.withLock {
            checkAvailable()
            check(restoring == null) { "Database restore already running" }
            restoring = finished
            CompletableDeferred<Unit>().also {
                drained = it
                if (active == 0) it.complete(Unit)
            }
        }
        try {
            idle.await()
            return block()
        } finally {
            withContext(NonCancellable) {
                state.withLock {
                    restoring = null
                    drained = null
                    finished.complete(Unit)
                }
            }
        }
    }

    /**
     * Exclusive configuration edits may read the still-open repositories without waiting on their own gate.
     * They queue behind each other; only a real [withRestore] fails fast while one runs.
     */
    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T {
        // Fail fast instead of waiting on a mutex this coroutine (or its access lease) already holds.
        check(coroutineContext[Lease]?.gate !== this) { "Exclusive edit cannot run inside repository access" }
        return exclusiveEdits.withLock { withRestore { withContext(Lease(this)) { block() } } }
    }

    /** Called under exclusive restore immediately before closing the live Room instance. */
    fun retire() {
        retired = true
        retirement.complete(Unit)
    }

    /** Reject new work, but let an admitted multi-stage operation finish before Room closes. */
    suspend fun retireAndDrain() {
        check(coroutineContext[Lease]?.gate !== this) { "Close cannot run inside repository access" }
        val idle = state.withLock {
            retire()
            (drained ?: CompletableDeferred<Unit>().also { drained = it }).also {
                if (active == 0) it.complete(Unit)
            }
        }
        idle.await()
    }

    private fun checkAvailable() {
        if (retired) throw DatabaseRestartRequiredException()
    }

    private class Lease(val gate: DatabaseAccessGate) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
    }
}

class DatabaseRestartRequiredException : kotlinx.coroutines.CancellationException("Database instance requires process restart")
