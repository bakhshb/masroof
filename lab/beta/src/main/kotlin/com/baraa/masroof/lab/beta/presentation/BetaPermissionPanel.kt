package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.ui.components.MasroofLogo
import com.baraa.masroof.ui.theme.*

@Composable
internal fun BetaPermissionPanel(busy: Boolean, onGranted: () -> Unit) {
    val context=LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val permissions=if(com.baraa.masroof.lab.beta.application.sms.BetaLiveSms.supports(context)) arrayOf(Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS) else arrayOf(Manifest.permission.READ_SMS)
    fun granted()=permissions.all { ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val allowed=permissions.all { result[it]==true }; denied=!allowed; if(allowed) onGranted()
    }
    LaunchedEffect(Unit) {
        if(granted()) onGranted()
    }
    val currentGranted by rememberUpdatedState(onGranted)
    val activity=generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<androidx.activity.ComponentActivity>().firstOrNull()
    DisposableEffect(activity) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event -> if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME && granted()) currentGranted() }
        activity?.lifecycle?.addObserver(observer)
        onDispose {activity?.lifecycle?.removeObserver(observer)}
    }
    Column(Modifier.fillMaxWidth().testTag("onboarding-PERMISSION"),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        Text(stringResource(R.string.beta_onboarding_progress,2,6),style=MaterialTheme.typography.bodySmall)
        MasroofLogo(size=MasroofSizes.size64)
        Text(stringResource(R.string.beta_parity_permission_title),style=MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.beta_parity_permission_body))
        if(denied) Text(stringResource(R.string.beta_history_denied),color=MaterialTheme.colorScheme.error)
        Button(enabled=!busy,onClick={permission.launch(permissions)},modifier=Modifier.fillMaxWidth().testTag("onboarding-permission")) {
            Text(stringResource(R.string.beta_parity_permission_action))
        }
        if(denied) OutlinedButton(enabled=!busy,onClick={context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:"+context.packageName)))},modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.beta_parity_open_permissions))
        }
    }
}
