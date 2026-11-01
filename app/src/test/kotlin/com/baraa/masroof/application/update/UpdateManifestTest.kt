package com.baraa.masroof.application.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateManifestTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun manifestJson(name: String) =
        """{"versionCode":5,"versionName":"1.0.0","apkFileName":"$name","sha256":"abc"}"""

    @Test
    fun releaseNames_areAccepted() {
        for (name in listOf("masroof.apk", "masroof-1.2.3.apk", "masroof-1.2.3-nightly.4.apk")) {
            assertEquals(name, json.decodeFromString(UpdateManifest.serializer(), manifestJson(name)).apkFileName)
        }
    }

    @Test
    fun pathTraversalAndNonApkNames_areRejectedOnDecode() {
        for (name in listOf("../../databases/masroof.db", "../x.apk", "sub/x.apk", "/data/x.apk", ".apk", "x.apk.db", "")) {
            assertThrows(IllegalArgumentException::class.java) {
                json.decodeFromString(UpdateManifest.serializer(), manifestJson(name))
            }
        }
    }

    @Test
    fun storedUnsafeManifest_isDropped() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(PendingUpdateStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("available_manifest", manifestJson("../../databases/masroof.db")).apply()
        assertNull(PendingUpdateStore(prefs).readAvailable())
    }
}
