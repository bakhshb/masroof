package com.baraa.masroof.presentation.startup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome

/** Saved UI state cannot certify maintenance in a new process or composition. */
@Composable
internal fun rememberStartupMaintenanceOutcome(
    awaitMaintenance: suspend () -> StartupMaintenanceOutcome,
    onOutcome: (StartupMaintenanceOutcome) -> Unit,
): MutableState<StartupMaintenanceOutcome?> {
    val outcome = remember { mutableStateOf<StartupMaintenanceOutcome?>(null) }
    LaunchedEffect(Unit) {
        val completed = awaitMaintenance()
        onOutcome(completed)
        outcome.value = completed
    }
    return outcome
}
