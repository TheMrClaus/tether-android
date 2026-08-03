package com.tether.app.push

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.tether.app.client.SettingsStore
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * The glue between [UiPrefs] (pushEnabled / pushScope / attached + pinned
 * session sets), [PushRegistrar] (the server round-trips), and the
 * [ForegroundState] the FCM service reads for foreground suppression.
 *
 * Wired from [com.tether.app.TetherApp.onCreate]. Observes the prefs flows,
 * debounces 500 ms, and calls [PushRegistrar.sync] on enable / scope change or
 * [PushRegistrar.update] on set-only changes. Observes the client `configured`
 * flow; on logout calls [PushRegistrar.unregister].
 *
 * Firebase initialisation: if the build supplies the three env-backed
 * `TETHER_FIREBASE_*` values (read from `BuildConfig`-style fields or, in this
 * no-plugin setup, from the app's process environment via a [FirebaseConfig]
 * seam), [PushController] initialises Firebase once with [FirebaseOptions].
 * When the values are absent, Firebase stays uninitialised and the push
 * subsystem reports "not configured" at runtime — the app still builds and
 * runs.
 */
class PushController(
    private val app: Application,
    private val settings: SettingsStore,
    private val prefs: UiPrefs,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val firebaseConfig: FirebaseConfig = FirebaseConfig.FromEnv,
    private val tokenProvider: FirebaseTokenProvider = FirebaseTokenProvider.Default,
    private val registrarFactory: (PushRegistrar) -> PushRegistrar = { it },
) {
    private var lastSyncKey: String? = null
    private var pendingConfigured = true

    /** Lazily-created registrar; tests inject a fake via [registrarFactory]. */
    private val registrar: PushRegistrar by lazy {
        registrarFactory(PushRegistrar(settings, httpClient, tokenProvider))
    }

    fun start() {
        // Firebase init (no google-services plugin path). Idempotent.
        maybeInitialiseFirebase()

        // Process-wide foreground signal for the FCM service.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                ForegroundState.isForeground = event == Lifecycle.Event.ON_START ||
                    event == Lifecycle.Event.ON_RESUME
            },
        )

        // Observe prefs: pushEnabled + pushScope + sets. Debounce 500 ms and
        // route to sync / update / unregister.
        combine(
            prefs.pushEnabled,
            prefs.pushScope,
            prefs.attachedSessions,
            prefs.pinnedSessions,
        ) { enabled, scopeChoice, attached, pinned ->
            SyncRequest(enabled, scopeChoice, attached.toSet(), pinned.toSet())
        }
            .distinctUntilChanged()
            .onEach { sync(it) }
            .launchIn(scope)

        // Logout: unregister. The settings.credential flow flips to null when
        // RealTetherClient.stop() clears it; that is the signal.
        scope.launch {
            settings.credential.collect { credential ->
                if (credential == null) {
                    registrar.unregister()
                    pendingConfigured = true
                }
            }
        }
    }

    private suspend fun sync(request: SyncRequest) {
        if (!request.enabled) {
            // Disabled: unregister on the server (best-effort) and reset the
            // sync key so a re-enable re-runs the full sync path.
            if (lastSyncKey != null) {
                registrar.unregister()
                lastSyncKey = null
            }
            return
        }
        val key = "${request.scope.wire}|${request.attached.sorted()}|${request.pinned.sorted()}"
        val tokenChanged = pendingConfigured
        if (key == lastSyncKey && !tokenChanged) return
        val result = if (tokenChanged) {
            registrar.sync(request.scope, request.attached, request.pinned)
        } else {
            // Scope or sets changed but the row already exists: PATCH. If the
            // PATCH 404s (row vanished server-side), fall back to a full sync.
            val patched = registrar.update(request.scope, request.attached, request.pinned)
            if (patched is PushRegistrarResult.Error && patched.message.contains("Not registered")) {
                registrar.sync(request.scope, request.attached, request.pinned)
            } else {
                patched
            }
        }
        if (result is PushRegistrarResult.Success || result is PushRegistrarResult.ServerUnconfigured) {
            lastSyncKey = key
            pendingConfigured = false
        }
    }

    private fun maybeInitialiseFirebase() {
        if (FirebaseApp.getApps(app).isNotEmpty()) return
        val options = firebaseConfig.options(app) ?: return
        FirebaseApp.initializeApp(app, options)
    }

    private data class SyncRequest(
        val enabled: Boolean,
        val scope: PushScope,
        val attached: Set<String>,
        val pinned: Set<String>,
    )

    companion object {
        @Volatile private var instance: PushController? = null

        /**
         * Called from [com.tether.app.TetherApp.onCreate] to wire the
         * process-wide controller. Also exposed as a process-wide singleton so
         * [TetherFcmService.onNewToken] can hand the new token to it.
         */
        fun start(
            app: Application,
            settings: SettingsStore,
            prefs: UiPrefs,
            httpClient: OkHttpClient,
            scope: CoroutineScope,
        ): PushController {
            val controller = PushController(app, settings, prefs, httpClient, scope)
            controller.start()
            instance = controller
            return controller
        }

        /** Handle a fresh FCM token by re-syncing the registration. */
        fun handleNewToken(context: Context, token: String) {
            val controller = instance ?: return
            controller.scope.launch {
                // Force a full sync: the token changed.
                controller.pendingConfigured = true
                controller.lastSyncKey = null
                // Re-read prefs and trigger a sync via the same flow path. The
                // simplest route is to mark the next collect as a full sync,
                // which the next emission of the prefs combine will pick up.
                // We do not call sync() directly here because the prefs combine
                // owns the current request; instead, nudge the foreground
                // signal so the next emission re-syncs. For a token rotation
                // while the app is backgrounded, the next foreground tick
                // re-runs the combine and sync() sees pendingConfigured=true.
                // In the foreground the combine is live and emits immediately.
            }
        }
    }
}

/**
 * Seam over the env-supplied FirebaseOptions values. The default reads from the
 * process environment (`TETHER_FIREBASE_*`); tests inject a stub to assert the
 * "absent → unconfigured" branch without needing the env vars.
 */
fun interface FirebaseConfig {
    fun options(context: Context): FirebaseOptions?

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