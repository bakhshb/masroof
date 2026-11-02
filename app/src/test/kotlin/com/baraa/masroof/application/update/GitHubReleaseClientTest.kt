package com.baraa.masroof.application.update

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseClientTest {
    @Test
    fun nightly_picksHighestVersionCodeAcrossStableAndNightlyReleases() {
        val stable = manifest(70, "0.3.17")
        val nightly = manifest(72, "0.3.18-nightly.2")

        val result = client(listOf(stable, nightly)).fetchLatestManifest(UpdateChannel.NIGHTLY)

        assertEquals(nightly, result.getOrThrow())
    }

    @Test
    fun nightly_rejectsEmptyReleaseList() {
        val result = client(emptyList()).fetchLatestManifest(UpdateChannel.NIGHTLY)

        assertTrue(result.isFailure)
        assertEquals("version.json asset not found in recent releases", result.exceptionOrNull()?.message)
    }

    @Test
    fun networkFailure_isReturnedAsIoFailure() {
        val offline = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline") }.build()

        for (channel in UpdateChannel.entries) {
            val result = GitHubReleaseClient(offline, owner = "test", repo = "masroof").fetchLatestManifest(channel)

            assertTrue(result.exceptionOrNull() is java.io.IOException)
        }
    }

    private fun client(manifests: List<UpdateManifest>): GitHubReleaseClient {
        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/repos/test/masroof/releases" -> manifests.joinToString(",", prefix = "[", postfix = "]") {
                    """{"assets":[{"name":"version.json","url":"https://api.github.com/assets/${it.versionCode}"}]}"""
                }
                else -> Json.encodeToString(manifests.single { request.url.encodedPath == "/assets/${it.versionCode}" })
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        return GitHubReleaseClient(httpClient, owner = "test", repo = "masroof")
    }

    private fun manifest(code: Int, name: String) = UpdateManifest(
        versionCode = code,
        versionName = name,
        apkFileName = "masroof-$name.apk",
        sha256 = "a",
    )
}
