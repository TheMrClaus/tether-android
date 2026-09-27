package com.tether.app.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.tether.app.protocol.TetherJson
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

/**
 * Makes the process's default [FirebaseApp] usable for FCM before a token is
 * requested. [PushRegistrar] calls [ensure] with the config from the server's
 * fcm-config response.
 */
fun interface FirebaseInitializer {
    /** True when the default FirebaseApp is initialised and FCM can be used. */
    fun ensure(serverConfig: FirebaseClientConfig?): Boolean

    companion object {
        /** For JVM tests of the registrar, where no Firebase exists or is needed. */
        val AlreadyInitialised = FirebaseInitializer { true }
    }
}

/**
 * Production [FirebaseInitializer]. The options come, in order, from:
 * 1. [override], an explicit dev/test [FirebaseConfig]. It is null in
 *    production. [FirebaseConfig.FromEnv] is one such override; it only works
 *    where a process environment exists (a JVM tool), never in an app process.
 * 2. The server's config, passed to [ensure] from fcm-config.
 * 3. The last server config that worked, saved in [store]. [restore] applies
 *    it at app start, so a cold-start FCM delivery finds FirebaseApp ready
 *    before any sync has run.
 *
 * A config that differs from the running default app (the device was paired
 * with another server's project) replaces it. The registrar then fetches a
 * token for the new project.
 */
class AndroidFirebaseInitializer(
    private val context: Context,
    private val store: FirebaseClientConfigStore = FirebaseClientConfigStore(context),
    private val override: FirebaseConfig? = null,
) : FirebaseInitializer {

    @Synchronized
    override fun ensure(serverConfig: FirebaseClientConfig?): Boolean {
        val forced = override?.options(context)
        val wanted = forced ?: serverConfig?.toOptions() ?: return defaultApp() != null
        val ok = apply(wanted)
        if (ok && forced == null && serverConfig != null) store.save(serverConfig)
        return ok
    }

    /** App start: bring FirebaseApp up from the override or the saved config, if any. */
    @Synchronized
    fun restore(): Boolean {
        val wanted = override?.options(context) ?: store.load()?.toOptions() ?: return defaultApp() != null
        return apply(wanted)
    }

    private fun apply(wanted: FirebaseOptions): Boolean = try {
        val existing = defaultApp()
        when {
            existing == null -> FirebaseApp.initializeApp(context, wanted)
            existing.options == wanted -> Unit
            else -> {
                existing.delete()
                FirebaseApp.initializeApp(context, wanted)
            }
        }
        true
    } catch (_: RuntimeException) {
        // A rejected option set or a Firebase internal failure: push stays off,
        // and the registrar reports it. Nothing about the config is logged.
        false
    }

    private fun defaultApp(): FirebaseApp? =
        FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
}

/**
 * The last working [FirebaseClientConfig], in the app's own small preferences
 * file. It holds public identifiers only, never a token.
 */
class FirebaseClientConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): FirebaseClientConfig? = FirebaseClientConfig.fromJson(prefs.getString(KEY, null))

    fun save(config: FirebaseClientConfig) {
        prefs.edit().putString(KEY, config.toJson()).apply()
    }

    companion object {
        const val FILE = "tether_push_firebase"
        private const val KEY = "client"
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
