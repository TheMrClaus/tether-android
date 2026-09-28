package com.tether.app.push

import android.content.Context
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.tether.app.protocol.TetherJson
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The public Firebase client identifiers for the server's FCM project: what a
 * `google-services.json` would carry. The app has no google-services plugin and
 * no build-time Firebase config (PLAN §1.1), and an Android process has no
 * `TETHER_FIREBASE_*` environment. So these come from the Tether server the
 * device is paired with, in `GET /api/push/fcm-config` as
 * `client: {projectId, appId, apiKey, senderId}`. That route change is a server
 * S-task (T12.1 round 2). Until the server sends it, [parse] finds nothing and
 * push reports "not available".
 *
 * They are identifiers, not secrets: Firebase ships them inside every APK.
 * Taking them from the server also keeps the client on the same project the
 * server sends from, by construction.
 */
data class FirebaseClientConfig(
    val projectId: String,
    val appId: String,
    val apiKey: String,
    val senderId: String,
) {
    fun toOptions(): FirebaseOptions = FirebaseOptions.Builder()
        .setProjectId(projectId)
        .setApplicationId(appId)
        .setApiKey(apiKey)
        .setGcmSenderId(senderId)
        .build()

    internal fun toJson(): String = buildJsonObject {
        put("projectId", projectId)
        put("appId", appId)
        put("apiKey", apiKey)
        put("senderId", senderId)
    }.toString()

    companion object {
        // GCP project ids: 6-30 chars, lowercase letters, digits, hyphens.
        private val PROJECT_ID = Regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$")

        // Android app ids: 1:<project number>:android:<hex>.
        private val APP_ID = Regex("^1:([0-9]{1,20}):android:[0-9a-f]{1,64}$")
        private val API_KEY = Regex("^[A-Za-z0-9_-]{20,100}$")
        private val SENDER_ID = Regex("^[0-9]{1,20}$")

        /**
         * Reads the `client` object from an fcm-config response, or null when it
         * is absent (a server before the S-task) or malformed. The sender id must
         * be the project number embedded in the app id, so a mismatched pair is
         * refused rather than half-applied.
         */
        fun parse(client: JsonObject?): FirebaseClientConfig? {
            client ?: return null
            fun field(name: String): String? = (client[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val projectId = field("projectId")?.takeIf(PROJECT_ID::matches) ?: return null
            val appId = field("appId") ?: return null
            val projectNumber = APP_ID.matchEntire(appId)?.groupValues?.get(1) ?: return null
            val apiKey = field("apiKey")?.takeIf(API_KEY::matches) ?: return null
            val senderId = field("senderId")?.takeIf(SENDER_ID::matches) ?: return null
            if (senderId != projectNumber) return null
            return FirebaseClientConfig(projectId, appId, apiKey, senderId)
        }

        internal fun fromJson(raw: String?): FirebaseClientConfig? = try {
            raw?.let { parse(TetherJson.parseToJsonElement(it).jsonObject) }
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** What [FirebaseInitializer.ensure] found. */
enum class FirebaseSetup {
    /** The default FirebaseApp matches this server's project: a token may be fetched. */
    Ready,

    /** No usable config for this server: push is unavailable. */
    Unavailable,

    /**
     * The same server now names a different Firebase project than the one this
     * device first used with it. The current project is kept, nothing is
     * registered, and the user is told to re-pair to accept the change.
     */
    ProjectChanged,
}

/**
 * Makes the process's default [FirebaseApp] usable for FCM before a token is
 * requested. [PushRegistrar] calls [ensure] with the config from the server's
 * fcm-config response and that server's origin.
 */
fun interface FirebaseInitializer {
    suspend fun ensure(serverConfig: FirebaseClientConfig?, origin: String): FirebaseSetup

    /** Logout: forget which project this device accepted, so a re-pair can accept a new one. */
    suspend fun forget() {}

    companion object {
        /** For JVM tests of the registrar, where no Firebase exists or is needed. */
        val AlreadyInitialised = FirebaseInitializer { _, _ -> FirebaseSetup.Ready }
    }
}

/**
 * Production [FirebaseInitializer]. The saved config is bound to the server
 * origin it came from, and **first use wins**:
 * - Explicit [override] (dev/test only; [FirebaseConfig.FromEnv] works only
 *   where a process environment exists, never in an app process): used as is.
 * - The same server, same project: applied (a rotated API key is fine).
 * - The same server, a **different project** (project id, sender id or app id,
 *   see [sameProjectAs]): not switched silently. The saved
 *   project stays up and [FirebaseSetup.ProjectChanged] is reported. Re-pairing
 *   (logout, [forget]) accepts the new one.
 * - The same server, no client block: its saved config is reused.
 * - Another server with a client block: accepted and bound to that origin.
 * - Another server, **no client block**: the old config is not reused. The old
 *   project's token is deleted, the default app is deleted, the binding is
 *   cleared, and push is [FirebaseSetup.Unavailable].
 * - Before the default app switches to another project (same comparison), the
 *   old project's token is deleted, so the server that held it prunes the row.
 *
 * [restore] brings the saved project up at app start, so a cold-start FCM
 * delivery finds FirebaseApp ready before any sync has run. (The SDK tolerates
 * a delivery with no FirebaseApp; it skips delivery metrics.)
 */
class AndroidFirebaseInitializer(
    private val context: Context,
    private val store: FirebaseClientConfigStore = FirebaseClientConfigStore(context),
    private val override: FirebaseConfig? = null,
    /** Deletes the current default app's token, off-main and bounded ([FirebaseTokenProvider.delete]). */
    private val deleteCurrentToken: suspend () -> Unit = { FirebaseTokenProvider.Default.delete() },
) : FirebaseInitializer {

    private val mutex = Mutex()

    override suspend fun ensure(serverConfig: FirebaseClientConfig?, origin: String): FirebaseSetup = mutex.withLock {
        override?.options(context)?.let { forced ->
            return@withLock if (switchTo(forced)) FirebaseSetup.Ready else FirebaseSetup.Unavailable
        }
        val saved = store.load()
        val sameServer = saved != null && saved.origin == origin
        when {
            serverConfig == null && sameServer ->
                if (switchTo(saved!!.config.toOptions())) FirebaseSetup.Ready else FirebaseSetup.Unavailable
            serverConfig == null -> {
                // A server we have no binding for, and it names no project: never
                // reuse another server's.
                dropDefaultApp()
                if (saved != null) store.clear()
                FirebaseSetup.Unavailable
            }
            sameServer && !saved!!.config.toOptions().sameProjectAs(serverConfig.toOptions()) -> {
                // Keep what this device accepted; make sure it is the one running.
                switchTo(saved.config.toOptions())
                FirebaseSetup.ProjectChanged
            }
            switchTo(serverConfig.toOptions()) -> {
                store.save(origin, serverConfig)
                FirebaseSetup.Ready
            }
            else -> FirebaseSetup.Unavailable
        }
    }

    override suspend fun forget() = mutex.withLock { store.clear() }

    /** App start: bring the saved (or overridden) project up if nothing runs yet. Never switches. */
    fun restore(): Boolean {
        if (defaultApp() != null) return true
        val wanted = override?.options(context) ?: store.load()?.config?.toOptions() ?: return false
        return try {
            FirebaseApp.initializeApp(context, wanted)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private suspend fun switchTo(wanted: FirebaseOptions): Boolean = try {
        val existing = defaultApp()
        when {
            existing == null -> FirebaseApp.initializeApp(context, wanted)
            existing.options == wanted -> Unit
            else -> {
                // The old project's token must die while its app is still the
                // default (FirebaseMessaging resolves through it).
                if (!existing.options.sameProjectAs(wanted)) deleteCurrentToken()
                existing.delete()
                FirebaseApp.initializeApp(context, wanted)
            }
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        // A rejected option set or a Firebase internal failure: push stays off,
        // and the registrar reports it. Nothing about the config is logged.
        false
    }

    private suspend fun dropDefaultApp() {
        val existing = defaultApp() ?: return
        deleteCurrentToken()
        try {
            existing.delete()
        } catch (_: RuntimeException) {
            // Already gone.
        }
    }

    private fun defaultApp(): FirebaseApp? =
        FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
}

/**
 * The pinned identity: project id, sender id (the project number FCM tokens
 * are issued for) and app id. A server that keeps its project id but names
 * another sender or app would move this device's token to someone else's
 * project, so any of the three changing is a project change. Only the API key
 * may rotate silently.
 */
internal fun FirebaseOptions.sameProjectAs(other: FirebaseOptions): Boolean =
    projectId == other.projectId && gcmSenderId == other.gcmSenderId && applicationId == other.applicationId

/** A [FirebaseClientConfig] and the server origin it was first accepted from. */
data class BoundFirebaseConfig(val origin: String, val config: FirebaseClientConfig)

/**
 * The accepted [BoundFirebaseConfig], in the app's own small preferences file
 * (excluded from backup). It holds public identifiers and an origin, never a
 * token.
 */
class FirebaseClientConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): BoundFirebaseConfig? {
        val origin = prefs.getString(KEY_ORIGIN, null) ?: return null
        val config = FirebaseClientConfig.fromJson(prefs.getString(KEY_CLIENT, null)) ?: return null
        return BoundFirebaseConfig(origin, config)
    }

    fun save(origin: String, config: FirebaseClientConfig) {
        prefs.edit {
            putString(KEY_ORIGIN, origin)
            putString(KEY_CLIENT, config.toJson())
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        const val FILE = "tether_push_firebase"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_CLIENT = "client"
    }
}

/**
 * Explicit dev/test override of the Firebase options (see
 * [AndroidFirebaseInitializer]). Not used in production.
 */
fun interface FirebaseConfig {
    fun options(context: Context): FirebaseOptions?

    /** Reads `TETHER_FIREBASE_*` from a process environment (JVM tooling only). */
    companion object FromEnv : FirebaseConfig {
        override fun options(context: Context): FirebaseOptions? {
            val projectId = System.getenv("TETHER_FIREBASE_PROJECT_ID") ?: return null
            val appId = System.getenv("TETHER_FIREBASE_APP_ID") ?: return null
            val apiKey = System.getenv("TETHER_FIREBASE_API_KEY") ?: return null
            return FirebaseOptions.Builder()
                .setProjectId(projectId)
                .setApplicationId(appId)
                .setApiKey(apiKey)
                .build()
        }
    }
}

/** The `client` object of an fcm-config response body, if it is an object. */
internal fun JsonObject.fcmClientObject(): JsonObject? = (this["client"] as? JsonObject)
