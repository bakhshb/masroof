package com.baraa.masroof.application.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object InstallPermissionHelper {
    fun canInstallPackages(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun buildManageUnknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}

open class ApkInstaller(
    private val context: Context,
) {
    open fun install(apkFile: File): Result<Unit> {
        if (!apkFile.exists()) {
            return Result.failure(IllegalStateException("APK file not found"))
        }

        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )

        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        return try {
            context.startActivity(intent)
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
