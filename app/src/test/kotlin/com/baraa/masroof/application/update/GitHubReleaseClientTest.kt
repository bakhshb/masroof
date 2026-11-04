package com.baraa.masroof.application.update

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
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

    @Test
    fun bodyReadFailure_isReturnedAsFailureNotThrown() {
        // 200: the release is fine but version.json fails mid-read. 500: the error body fails to read.
        for (code in listOf(200, 500)) {
            val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val body = if (code == 200 && request.url.encodedPath == "/repos/test/masroof/releases/latest") {
                    """{"assets":[{"name":"version.json","url":"https://api.github.com/assets/1"}]}""".toResponseBody()
                } else {
                    brokenBody()
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(code).message("x").body(body).build()
            }.build()

            val result = GitHubReleaseClient(httpClient, owner = "test", repo = "masroof")
                .fetchLatestManifest(UpdateChannel.STABLE)

            val expected = if (code == 200) java.io.IOException::class.java else GitHubRequestException::class.java
            assertTrue(expected.isInstance(result.exceptionOrNull()))
        }
    }

    private fun brokenBody(): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw java.io.IOException("reset")
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
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
