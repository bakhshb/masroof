package com.baraa.masroof.application.update

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseAssetsTest {
    @Test
    fun tagForVersionName_prefixesVersionWithV() {
        assertEquals("v0.3.18", ReleaseAssets.tagForVersionName("0.3.18"))
        assertEquals("v0.3.18-nightly.2", ReleaseAssets.tagForVersionName("0.3.18-nightly.2"))
    }

    @Test
    fun findApkAssetUrl_returnsMatchingAssetUrl() {
        val release = buildJsonObject {
            putJsonArray("assets") {
                add(
                    buildJsonObject {
                        put("name", "version.json")
                        put("url", "https://example.com/version.json")
                    },
                )
                add(
                    buildJsonObject {
                        put("name", "masroof-0.3.18.apk")
                        put("url", "https://example.com/masroof-0.3.18.apk")
                    },
                )
            }
        }

        assertEquals(
            "https://example.com/masroof-0.3.18.apk",
            ReleaseAssets.findApkAssetUrl(release, "masroof-0.3.18.apk"),
        )
    }

    @Test
    fun findApkAssetUrl_returnsNullWhenAssetMissing() {
        val release = buildJsonObject {
            put("assets", JsonArray(emptyList()))
        }

        assertNull(ReleaseAssets.findApkAssetUrl(release, "masroof-0.3.18.apk"))
    }
}
