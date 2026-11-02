package com.baraa.masroof.application.update

import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkFileName: String,
    val sha256: String,
    val releaseNotes: String? = null,
) {
    init {
        // The name becomes a path under cache/updates, which is deleted on hash mismatch.
        require(SAFE_APK_FILE_NAME.matches(apkFileName)) { "Unsafe APK file name" }
    }
}

private val SAFE_APK_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
