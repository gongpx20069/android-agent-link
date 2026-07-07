package com.gongpx.androidacpclient.data.update

import com.gongpx.androidacpclient.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class AppUpdate(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val isNewer: Boolean,
)

class UpdateClient {
    suspend fun checkForUpdate(): Result<AppUpdate?> = withContext(Dispatchers.IO) {
        runCatching {
            val releases = getJsonArray(RELEASES_URL)
            val latest = (0 until releases.length())
                .map { releases.getJSONObject(it) }
                .firstOrNull { it.optString("tag_name").matches(RELEASE_TAG_REGEX) }
                ?: return@runCatching null

            val version = latest.getString("tag_name")
            val releaseUrl = latest.getString("html_url")
            val assets = latest.optJSONArray("assets") ?: JSONArray()
            val apkUrl = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url")
                ?.ifBlank { null }

            AppUpdate(
                version = version,
                releaseUrl = releaseUrl,
                apkUrl = apkUrl,
                isNewer = compareVersions(version, BuildConfig.VERSION_NAME) > 0,
            )
        }
    }

    private fun getJsonArray(url: String): JSONArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "AgentLink/${BuildConfig.VERSION_NAME}")
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                error("GitHub releases request failed with HTTP $status: $body")
            }
            JSONArray(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.trimStart('v').split(".").map { it.toIntOrNull() ?: 0 }
        val rightParts = right.trimStart('v').split(".").map { it.toIntOrNull() ?: 0 }
        val max = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until max) {
            val diff = (leftParts.getOrNull(index) ?: 0) - (rightParts.getOrNull(index) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }

    private companion object {
        const val RELEASES_URL = "https://api.github.com/repos/gongpx20069/android-agent-link/releases"
        const val TIMEOUT_MS = 10_000
        val RELEASE_TAG_REGEX = Regex("""0\.0\.\d+""")
    }
}
