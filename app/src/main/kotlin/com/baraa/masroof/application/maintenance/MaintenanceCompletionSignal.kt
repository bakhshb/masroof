package com.baraa.masroof.application.maintenance

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Emits after maintenance changed stored financial data, so open screens reload. */
class MaintenanceCompletionSignal {
    private val events = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val completions: SharedFlow<Unit> = events.asSharedFlow()

    fun notifyCompleted() {
        events.tryEmit(Unit)
    }
}
