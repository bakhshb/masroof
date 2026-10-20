package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.OutlinedTextField
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.BetaIo
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A settings route for Beta-owned preferences and local snapshots only. */
@Composable
fun BetaSettingsPanel(container: BetaContainer, onClose: () -> Unit, onBusyChanged: (Boolean) -> Unit = {}, onChanged: () -> Unit = {}, showMaintenance: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberBetaUiScope()
    var archivePassword by remember { mutableStateOf("") }
    var passwordRepeat by remember { mutableStateOf("") }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    var picking by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { pendingPassword?.fill('\u0000'); pendingPassword = null } }
    var busy by remember { mutableStateOf(false) }
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var result by rememberSaveable { mutableStateOf("IDLE") }
    var theme by remember { mutableStateOf("SYSTEM") }
    var backupAvailable by remember { mutableStateOf(false) }
    suspend fun refresh() {
        val settings = withContext(BetaIo.dispatcher) { container.settings() }
        theme = settings.theme; backupAvailable = settings.backupAvailable
    }
    BetaLaunchedEffect(Unit) {
        try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { result = "FAILED" }
    }
    fun perform(onFinished: () -> Unit = {}, action: () -> Boolean) {
        if (busy) return
        busy = true; onBusyChanged(true); result = "IDLE"
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val succeeded = withContext(BetaIo.dispatcher) { action() }
                refresh(); result = if (succeeded) "SUCCESS" else "MISSING"; onChanged()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { result = "FAILED" }
            finally { onFinished(); busy = false; onBusyChanged(false) }
        }
    }
    fun archiveResult(uri: android.net.Uri?, exporting: Boolean) {
        picking = false; onBusyChanged(false)
        val secret = pendingPassword; pendingPassword = null
        if (uri == null || secret == null) { secret?.fill('\u0000'); return }
        if (busy) { secret.fill('\u0000'); return }
        perform(onFinished = { secret.fill('\u0000') }) {
            try {
                if (exporting) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { container.exportArchive(it, secret) }; true
                } else requireNotNull(context.contentResolver.openInputStream(uri)).use { container.importArchive(it, secret) }
            } finally { secret.fill('\u0000') }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { archiveResult(it, true) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { archiveResult(it, false) }
    BackHandler { if (!busy && !picking) onClose() }
    Column(verticalArrangement = Arrangement.spacedBy(BetaSpacing.section), modifier = Modifier.testTag("beta-settings-panel")) {
        if(showMaintenance) Text(stringResource(R.string.beta_settings_title), style = MaterialTheme.typography.titleLarge)
        if(showMaintenance) Text(stringResource(R.string.beta_settings_isolation))
        if(showMaintenance) {
        Text(stringResource(R.string.beta_settings_theme), style = MaterialTheme.typography.titleMedium)
        listOf("SYSTEM" to R.string.beta_theme_system, "LIGHT" to R.string.beta_theme_light, "DARK" to R.string.beta_theme_dark).forEach { (value, label) ->
            OutlinedButton(enabled = !busy && !picking && theme != value, onClick = { perform { container.setTheme(value); true } }, modifier = Modifier.fillMaxWidth().testTag("beta-theme-$value")) { Text(stringResource(label)) }
        }
        }
        if(showMaintenance) {
        Text(stringResource(if (backupAvailable) R.string.beta_settings_backup_available else R.string.beta_settings_backup_missing))
        Text(stringResource(R.string.beta_settings_backup_help))
        OutlinedButton(enabled = !busy && !picking, onClick = { perform { container.backup(); true } }, modifier = Modifier.fillMaxWidth().testTag("beta-settings-backup")) { Text(stringResource(R.string.beta_backup)) }
        OutlinedButton(enabled = !busy && !picking && backupAvailable, onClick = { confirmation = "RESTORE" }, modifier = Modifier.fillMaxWidth().testTag("beta-settings-restore")) { Text(stringResource(R.string.beta_restore)) }
        if(showMaintenance) OutlinedButton(enabled = !busy && !picking, onClick = { confirmation = "RESET" }, modifier = Modifier.fillMaxWidth().testTag("beta-settings-reset")) { Text(stringResource(R.string.beta_reset)) }
        }
        Text(stringResource(R.string.beta_archive_boundary))
        OutlinedButton(enabled = !busy && !picking, onClick = { archivePassword = ""; passwordRepeat = ""; confirmation = "EXPORT" }, modifier = Modifier.fillMaxWidth().testTag("beta-archive-export")) { Text(stringResource(R.string.beta_archive_export)) }
        OutlinedButton(enabled = !busy && !picking, onClick = { archivePassword = ""; passwordRepeat = ""; confirmation = "IMPORT" }, modifier = Modifier.fillMaxWidth().testTag("beta-archive-import")) { Text(stringResource(R.string.beta_archive_import)) }
        if (busy) Text(stringResource(R.string.beta_settings_busy), modifier = Modifier.testTag("beta-settings-busy"))
        if (result != "IDLE") Text(stringResource(when (result) {
            "SUCCESS" -> R.string.beta_settings_success
            "MISSING" -> R.string.beta_restore_missing
            else -> R.string.beta_settings_failed
        }), modifier = Modifier.testTag("beta-settings-result"), color = if (result == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        OutlinedButton(enabled = !busy && !picking, onClick = onClose, modifier = Modifier.testTag("beta-settings-close")) { Text(stringResource(R.string.beta_asset_cancel)) }
    }
    confirmation?.takeIf { it == "EXPORT" || it == "IMPORT" }?.let { action ->
        AlertDialog(onDismissRequest = { if (!busy && !picking) { confirmation = null; archivePassword = ""; passwordRepeat = "" } },
            title = { Text(stringResource(if (action == "EXPORT") R.string.beta_archive_export else R.string.beta_archive_import)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(BetaSpacing.section), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.beta_archive_boundary))
                if (action == "IMPORT") Text(stringResource(R.string.beta_archive_replace_confirm))
                OutlinedTextField(value = archivePassword, onValueChange = { if (it.length <= 256) archivePassword = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false), visualTransformation = PasswordVisualTransformation(), label = { Text(stringResource(R.string.beta_archive_password)) }, modifier = Modifier.testTag("beta-archive-password"))
                if (action == "EXPORT") OutlinedTextField(value = passwordRepeat, onValueChange = { if (it.length <= 256) passwordRepeat = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false), visualTransformation = PasswordVisualTransformation(), label = { Text(stringResource(R.string.beta_archive_password_repeat)) }, modifier = Modifier.testTag("beta-archive-password-repeat"))
            } },
            confirmButton = { Button(enabled = !busy && !picking && archivePassword.length in 12..256 && (action == "IMPORT" || archivePassword == passwordRepeat), onClick = {
                pendingPassword?.fill('\u0000'); pendingPassword = archivePassword.toCharArray(); archivePassword = ""; passwordRepeat = ""; confirmation = null
                picking = true; onBusyChanged(true)
                try { if (action == "EXPORT") exportLauncher.launch("masroof-beta.mbeta") else importLauncher.launch(arrayOf("application/octet-stream", "application/*")) }
                catch (_: Exception) { picking = false; onBusyChanged(false); pendingPassword?.fill('\u0000'); pendingPassword = null; result = "FAILED" }
            }, modifier = Modifier.testTag("beta-archive-confirm")) { Text(stringResource(R.string.beta_settings_confirm)) } },
            dismissButton = { TextButton(enabled = !busy && !picking, onClick = { confirmation = null; archivePassword = ""; passwordRepeat = "" }, modifier = Modifier.testTag("beta-archive-cancel")) { Text(stringResource(R.string.beta_asset_cancel)) } })
    }
    confirmation?.takeUnless { it == "EXPORT" || it == "IMPORT" }?.let { action ->
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null },
            title = { Text(stringResource(if (action == "RESET") R.string.beta_reset else R.string.beta_restore)) },
            text = { Text(stringResource(if (action == "RESET") R.string.beta_settings_reset_confirm else R.string.beta_settings_restore_confirm)) },
            confirmButton = { Button(enabled = !busy && !picking, onClick = { confirmation = null; perform { if (action == "RESET") { container.reset(); true } else container.restore() } }, modifier = Modifier.testTag("beta-settings-confirm")) { Text(stringResource(R.string.beta_settings_confirm)) } },
            dismissButton = { TextButton(enabled = !busy && !picking, onClick = { confirmation = null }, modifier = Modifier.testTag("beta-settings-cancel")) { Text(stringResource(R.string.beta_asset_cancel)) } })
    }
}
