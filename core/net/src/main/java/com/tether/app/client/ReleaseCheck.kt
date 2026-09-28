package com.tether.app.client

import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.str
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * D13: the in-app "update available" check against the GitHub Releases API
 * (APK distribution stays GitHub Releases + Obtainium). Unauthenticated and
 * best-effort: any failure is "unknown", never an error the user has to handle.
 */
object ReleaseCheck {
    const val LATEST_API_URL = "https://api.github.com/repos/TheMrClaus/tether-android/releases/latest"

    /** Where the banner sends the user when the API could not name a release. */
    const val RELEASES_PAGE_URL = "https://github.com/TheMrClaus/tether-android/releases/latest"

    data class Release(val tag: String, val pageUrl: String)

    /** The latest published release, or null when it cannot be determined. */
    suspend fun fetchLatest(http: OkHttpClient, url: String = LATEST_API_URL): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        // The API answers 200 directly; never follow a redirect off api.github.com (a 3xx
        // just reads as "no answer"). T1.2 verifier finding.
        val noRedirects = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        try {
            noRedirects.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val text = response.body.string()
                // T6.2: the same stack guard as every other server-sent JSON (ServerMessage.MAX_FRAME_DEPTH).
                if (com.tether.app.protocol.ServerMessage.nestsDeeperThan(text, com.tether.app.protocol.ServerMessage.MAX_FRAME_DEPTH)) return@withContext null
                val obj = TetherJson.parseToJsonElement(text) as? JsonObject
                    ?: return@withContext null
                val tag = obj.str("tag_name")?.takeIf { it.isNotBlank() } ?: return@withContext null
                // Only an https page on github.com is ever opened from here.
                val page = obj.str("html_url")?.takeIf { it.startsWith("https://github.com/") } ?: RELEASES_PAGE_URL
                Release(tag, page)
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            // Malformed JSON (SerializationException is an IllegalArgumentException).
            null
        }
    }

    /**
     * True when [tag] ("v0.7.0", "0.7.0") names a strictly newer version than
     * [current] (BuildConfig.VERSION_NAME). Dotted numeric compare; a
     * pre-release/build suffix ("-rc1", "+sha") is ignored; unparsable = false.
     */
    fun isNewer(tag: String, current: String): Boolean {
        val latest = parse(tag) ?: return false
        val mine = parse(current) ?: return false
        for (i in 0 until maxOf(latest.size, mine.size)) {
            val a = latest.getOrElse(i) { 0 }
            val b = mine.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parse(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        return core.split('.').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    }
}
