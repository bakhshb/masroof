package com.baraa.masroof.application.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class GitHubReleaseClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val owner: String,
    private val repo: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun fetchLatestManifest(
        channel: UpdateChannel = UpdateChannel.STABLE,
        token: String? = null,
    ): Result<UpdateManifest> =
        when (channel) {
            UpdateChannel.STABLE -> fetchStableLatestManifest(token)
            UpdateChannel.NIGHTLY -> fetchNightlyLatestManifest(token)
        }

    fun downloadReleaseAsset(
        token: String? = null,
        manifest: UpdateManifest,
        destination: java.io.File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<java.io.File> {
        val tokenWasProvided = token.isConfigured()
        val downloadUrl =
            findApkAssetUrl(manifest, token, tokenWasProvided).getOrElse { return Result.failure(it) }
        return downloadBinary(downloadUrl, token, tokenWasProvided, destination, onProgress)
    }

    private fun fetchStableLatestManifest(token: String?): Result<UpdateManifest> {
        val tokenWasProvided = token.isConfigured()
        val release = fetchLatestRelease(token, tokenWasProvided).getOrElse { return Result.failure(it) }
        return manifestFromRelease(release, token, tokenWasProvided)
    }

    private fun fetchNightlyLatestManifest(token: String?): Result<UpdateManifest> {
        val tokenWasProvided = token.isConfigured()
        val releases = fetchRecentReleases(token, tokenWasProvided).getOrElse { return Result.failure(it) }
        val manifests = mutableListOf<UpdateManifest>()
        for (release in releases) {
            val releaseObject = release.jsonObject
            manifestFromRelease(releaseObject, token, tokenWasProvided)
                .getOrNull()
                ?.let(manifests::add)
        }
        val best = manifests.maxByOrNull { it.versionCode }
            ?: return Result.failure(IllegalStateException("version.json asset not found in recent releases"))
        return Result.success(best)
    }

    private fun manifestFromRelease(
        release: JsonObject,
        token: String?,
        tokenWasProvided: Boolean,
    ): Result<UpdateManifest> {
        val assets = release["assets"]?.jsonArray ?: JsonArray(emptyList())
        val versionAsset = assets.firstOrNull { asset ->
            asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull == VERSION_JSON_NAME
        }?.jsonObject

        val downloadUrl = versionAsset?.get("url")?.jsonPrimitive?.contentOrNull
            ?: return Result.failure(IllegalStateException("version.json asset not found in release"))

        return downloadText(downloadUrl, token, tokenWasProvided).mapCatching { body ->
            json.decodeFromString(UpdateManifest.serializer(), body)
        }
    }

    private fun findApkAssetUrl(
        manifest: UpdateManifest,
        token: String?,
        tokenWasProvided: Boolean,
    ): Result<String> {
        val tag = ReleaseAssets.tagForVersionName(manifest.versionName)
        fetchReleaseByTag(tag, token, tokenWasProvided)
            .getOrNull()
            ?.let { release -> ReleaseAssets.findApkAssetUrl(release, manifest.apkFileName) }
            ?.let { return Result.success(it) }

        val releases = fetchRecentReleases(token, tokenWasProvided).getOrElse { return Result.failure(it) }
        for (release in releases) {
            val downloadUrl = ReleaseAssets.findApkAssetUrl(release.jsonObject, manifest.apkFileName)
            if (downloadUrl != null) {
                return Result.success(downloadUrl)
            }
        }
        return Result.failure(IllegalStateException("APK asset not found: ${manifest.apkFileName}"))
    }

    private fun fetchReleaseByTag(
        tag: String,
        token: String?,
        tokenWasProvided: Boolean,
    ): Result<JsonObject> {
        val request =
            authorizedRequest(token)
                .url("https://api.github.com/repos/$owner/$repo/releases/tags/$tag")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()

        return executeJsonObject(request, tokenWasProvided)
    }

    private fun fetchLatestRelease(token: String?, tokenWasProvided: Boolean): Result<JsonObject> {
        val request =
            authorizedRequest(token)
                .url("https://api.github.com/repos/$owner/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()

        return executeJsonObject(request, tokenWasProvided)
    }

    private fun fetchRecentReleases(token: String?, tokenWasProvided: Boolean): Result<List<JsonElement>> {
        val request =
            authorizedRequest(token)
                .url("https://api.github.com/repos/$owner/$repo/releases?per_page=$RELEASES_PAGE_SIZE")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()

        return executeJsonArray(request, tokenWasProvided)
    }

    private fun downloadText(url: String, token: String?, tokenWasProvided: Boolean): Result<String> {
        val request =
            authorizedRequest(token)
                .url(url)
                .header("Accept", "application/octet-stream")
                .get()
                .build()

        return executeBody(request, tokenWasProvided).map { it.string() }
    }

    private fun downloadBinary(
        url: String,
        token: String?,
        tokenWasProvided: Boolean,
        destination: java.io.File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): Result<java.io.File> {
        val request =
            authorizedRequest(token)
                .url(url)
                .header("Accept", "application/octet-stream")
                .get()
                .build()

        return executeBody(request, tokenWasProvided).mapCatching { body ->
            destination.parentFile?.mkdirs()
            val totalBytes = body.contentLength()
            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytesReadTotal = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesReadTotal += read
                        onProgress(bytesReadTotal, totalBytes)
                    }
                }
            }
            destination
        }
    }

    internal fun buildAuthorizedRequestForTest(token: String?): Request.Builder = authorizedRequest(token)

    private fun authorizedRequest(token: String?): Request.Builder {
        val builder =
            Request.Builder()
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Masroof-Android")
        val trimmed = token?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            builder.header("Authorization", authorizationHeader(trimmed))
        }
        return builder
    }

    private fun executeJsonObject(request: Request, tokenWasProvided: Boolean): Result<JsonObject> =
        executeBody(request, tokenWasProvided).mapCatching { body ->
            json.parseToJsonElement(body.string()).jsonObject
        }

    private fun executeJsonArray(request: Request, tokenWasProvided: Boolean): Result<List<JsonElement>> =
        executeBody(request, tokenWasProvided).mapCatching { body ->
            json.parseToJsonElement(body.string()).jsonArray.toList()
        }

    private fun executeBody(request: Request, tokenWasProvided: Boolean): Result<okhttp3.ResponseBody> {
        // A network failure is a result, so callers can log it and the worker can retry.
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: java.io.IOException) {
            return Result.failure(e)
        }
        val body = response.body
        if (!response.isSuccessful) {
            val errorSnippet = body.string().take(200)
            body.close()
            val code = response.code
            response.close()
            return Result.failure(
                GitHubRequestException(
                    httpCode = code,
                    tokenWasProvided = tokenWasProvided,
                    message = when (code) {
                        401, 403 ->
                            "GitHub authentication failed (HTTP $code). " +
                                "Use a read-only token with Contents access for this repo."
                        404 -> "Release not found (HTTP $code)"
                        else -> "GitHub request failed (HTTP $code): $errorSnippet"
                    },
                ),
            )
        }
        return Result.success(body)
    }

    internal fun authorizationHeader(token: String): String {
        val trimmed = token.trim()
        when {
            trimmed.startsWith("Bearer ", ignoreCase = true) -> return trimmed
            trimmed.startsWith("token ", ignoreCase = true) -> return trimmed
            trimmed.startsWith("github_pat_") -> return "Bearer $trimmed"
            trimmed.startsWith("ghp_") -> return "token $trimmed"
            trimmed.startsWith("gho_") -> return "token $trimmed"
            else -> return "Bearer $trimmed"
        }
    }

    private fun String?.isConfigured(): Boolean = !this.isNullOrBlank()

    companion object {
        const val VERSION_JSON_NAME: String = "version.json"
        private const val RELEASES_PAGE_SIZE: Int = 10

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
    }
}
