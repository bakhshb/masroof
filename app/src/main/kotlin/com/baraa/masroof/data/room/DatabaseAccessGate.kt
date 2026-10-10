package com.baraa.masroof.data.room

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Drains repository operations before replacing files; a closed process instance is never reused. */
class DatabaseAccessGate {
    private val state = Mutex()
    private var active = 0
    private var restoring: CompletableDeferred<Unit>? = null
    private var drained: CompletableDeferred<Unit>? = null
    @Volatile private var retired = false

    suspend fun <T> withAccess(block: suspend () -> T): T {
        checkAvailable()
        if (coroutineContext[Lease]?.gate === this) return block()
        while (true) {
            val wait = state.withLock {
                checkAvailable()
                restoring.also { if (it == null) active++ }
            }
            if (wait == null) break
            wait.await()
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

    /** Called under exclusive restore immediately before closing the live Room instance. */
    fun retire() {
        retired = true
    }

    private fun checkAvailable() {
        if (retired) throw DatabaseRestartRequiredException()
    }

    private class Lease(val gate: DatabaseAccessGate) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
    }
}

class DatabaseRestartRequiredException : kotlinx.coroutines.CancellationException("Database instance requires process restart")
