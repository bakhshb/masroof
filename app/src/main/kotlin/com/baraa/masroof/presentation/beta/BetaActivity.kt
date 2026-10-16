package com.baraa.masroof.presentation.beta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.baraa.masroof.application.beta.BetaContainer
import com.baraa.masroof.presentation.theme.MasroofTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen Beta shell. It does not read the production composition root.
 */
class BetaActivity : ComponentActivity() {
    private var betaContainer: BetaContainer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = BetaContainer(applicationContext)
        betaContainer = container
        setContent {
            MasroofTheme {
                val shellContainer = remember { container }
                val scope = rememberCoroutineScope()
                var generation by remember { mutableIntStateOf(0) }
                var evidenceCount by remember { mutableIntStateOf(0) }
                var restoreMissing by remember { mutableStateOf(false) }
                LaunchedEffect(generation) {
                    evidenceCount = withContext(Dispatchers.IO) { shellContainer.evidenceCount() }
                }
                BetaShell(
                    evidenceCount = evidenceCount,
                    restoreMissing = restoreMissing,
                    onReturn = { finish() },
                    onReset = {
                        scope.launch {
                            withContext(Dispatchers.IO) { shellContainer.reset() }
                            restoreMissing = false
                            generation += 1
                        }
                    },
                    onBackup = {
                        scope.launch {
                            withContext(Dispatchers.IO) { shellContainer.backup() }
                            restoreMissing = false
                        }
                    },
                    onRestore = {
                        scope.launch {
                            val restored = withContext(Dispatchers.IO) { shellContainer.restore() }
                            restoreMissing = !restored
                            generation += 1
                        }
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        betaContainer?.close()
        betaContainer = null
        super.onDestroy()
    }
}
