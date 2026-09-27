package com.tether.app.push

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.tether.app.client.Credential
import com.tether.app.client.SettingsStore
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * The glue between [UiPrefs] (pushEnabled / pushScope / attached + pinned
 * session sets), [PushRegistrar] (the server round-trips), and the
 * [ForegroundState] the FCM service reads for foreground suppression.
 *
 * Wired from [com.tether.app.TetherApp.onCreate]. Observes the prefs flows and
 * hands every change to a [PushSyncCoordinator], which calls
 * [PushRegistrar.sync] on enable / token rotation or [PushRegistrar.update] on
 * set-only changes. Observes the client `configured` flow; on logout calls
 * [PushRegistrar.unregister].
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
    private val prefs: PushPrefs,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val firebaseConfig: FirebaseConfig = FirebaseConfig.FromEnv,
    private val tokenProvider: FirebaseTokenProvider = FirebaseTokenProvider.Default,
    private val syncHints: SyncHintsSource = SyncHintsSource.Off,
    private val registrarFactory: (PushRegistrar) -> PushRegistrar = { it },
) {
    /** Lazily-created registrar; tests inject a fake via [registrarFactory]. */
    private val registrar: PushRegistrar by lazy {
        registrarFactory(PushRegistrar(settings, httpClient, tokenProvider))
    }

    private val coordinator: PushSyncCoordinator by lazy { PushSyncCoordinator(registrar) }

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

        startSync()
    }

    /**
     * The registration trigger path. It observes the prefs, the sync-hint
     * opt-in, and the server identity (base URL + paired-device credential), and
     * hands every distinct snapshot to the [PushSyncCoordinator]. Because the
     * identity is part of the snapshot, a fresh sign-in or pairing, or a switch
     * to another server, registers at once. Before round 2 only a pref change or
     * a token rotation did. Split from [start] so tests drive the real path
     * without the process lifecycle.
     */
    internal fun startSync() {
        val prefsRequest = combine(
            prefs.pushEnabled,
            prefs.pushScope,
            prefs.attachedSessions,
            prefs.pinnedSessions,
            syncHints.optedIn(),
        ) { enabled, scopeChoice, attached, pinned, hints ->
            PushSyncRequest(enabled, scopeChoice, attached.toSet(), pinned.toSet(), hints, server = null)
        }
        val server = combine(settings.baseUrl, settings.credential) { baseUrl, credential ->
            PushServerIdentity.of(baseUrl, credential)
        }
        combine(prefsRequest, server) { request, identity -> request.copy(server = identity) }
            .distinctUntilChanged()
            .onEach { coordinator.onRequest(it) }
            .launchIn(scope)
    }

    /**
     * User logout ([com.tether.app.client.RealTetherClient.logout] hook): drop
     * this device's push row with the credential that was just forgotten. The
     * credential-null collector above cannot — by then there is nothing to
     * authenticate the DELETE with, so the server would keep pushing to a
     * signed-out phone.
     */
    suspend fun unregisterAfterLogout(baseUrl: String, credential: Credential) {
        coordinator.onLoggedOut(baseUrl, credential)
    }

    private fun maybeInitialiseFirebase() {
        if (FirebaseApp.getApps(app).isNotEmpty()) return
        val options = firebaseConfig.options(app) ?: return
        FirebaseApp.initializeApp(app, options)
    }

    companion object {
        @Volatile private var instance: PushController? = null

        /**
         * Called from [com.tether.app.TetherApp.onCreate] to wire the
         * process-wide controller. Also exposed as a process-wide singleton so
         * [TetherFcmService.onNewToken] can reach it.
         */
        fun start(
            app: Application,
            settings: SettingsStore,
            prefs: UiPrefs,
            httpClient: OkHttpClient,
            scope: CoroutineScope,
        ): PushController {
            val controller = PushController(app, settings, PushPrefs.fromUiPrefs(prefs), httpClient, scope)
            controller.start()
            instance = controller
            return controller
        }

        /**
         * FCM rotated the token: re-register now with the latest prefs, including
         * the stored sync-hint opt-in. Before T12.1 this only set flags, and the
         * prefs combine never re-emits on its own, so a rotated token did not
         * reach the server until an unrelated pref changed.
         */
        fun handleNewToken() {
            val controller = instance ?: return
            controller.scope.launch { controller.coordinator.onTokenRotated() }
        }
    }
}

/**
 * One snapshot of everything the server's push row holds for this device, plus
 * which server and credential it belongs to. [server] is null while there is no
 * paired-device credential: nothing can be registered then.
 */
internal data class PushSyncRequest(
    val enabled: Boolean,
    val scope: PushScope,
    val attached: Set<String>,
    val pinned: Set<String>,
    val syncHints: Boolean,
    val server: PushServerIdentity?,
)

/**
 * The server a registration belongs to. A change here (sign-in, re-pair, server
 * switch) forces a full POST. [Credential.toString] is masked, so this never
 * prints the token.
 */
internal data class PushServerIdentity(val baseUrl: String, val credential: Credential.DeviceToken) {
    companion object {
        /** Only a paired device has an FCM row (fcm-register needs a device principal). */
        fun of(baseUrl: String?, credential: Credential?): PushServerIdentity? =
            if (baseUrl != null && credential is Credential.DeviceToken) PushServerIdentity(baseUrl, credential) else null
    }
}

/**
 * Keeps the server's push row in step with the prefs. Every entry point runs
 * under one lock, so a token rotation and a prefs change never interleave
 * their round-trips.
 *
 * - First sync, and every sync after a token rotation, is a full POST
 *   ([PushRegistrar.sync]). The POST replaces the row, so it carries the whole
 *   request, `syncHints` included.
 * - Later changes are a PATCH ([PushRegistrar.update]). A 404 (the row vanished
 *   server-side) falls back to the full POST.
 * - A rotation re-applies the latest request, so the new token is sent at once.
 *   If the prefs have not emitted yet, the first emission does the full sync.
 * - A new server identity (sign-in, re-pair, server switch) is a full POST too.
 *   No identity (signed out, cookie login) calls nothing.
 */
internal class PushSyncCoordinator(private val registrar: PushRegistrar) {
    private val mutex = Mutex()
    private var latest: PushSyncRequest? = null
    private var lastSyncKey: String? = null
    private var syncedServer: PushServerIdentity? = null
    private var needsFullSync = true

    suspend fun onRequest(request: PushSyncRequest) = mutex.withLock {
        latest = request
        reconcile(request)
    }

    suspend fun onTokenRotated() = mutex.withLock {
        needsFullSync = true
        latest?.let { reconcile(it) }
    }

    suspend fun onLoggedOut(baseUrl: String, credential: Credential) = mutex.withLock {
        registrar.unregister(baseUrl, credential)
        lastSyncKey = null
        syncedServer = null
        needsFullSync = true
    }

    private suspend fun reconcile(request: PushSyncRequest) {
        if (request.server == null) {
            // Signed out, or a cookie login: no row can exist for this device. The
            // logout hook ([onLoggedOut]) already DELETEd with the old credential.
            lastSyncKey = null
            syncedServer = null
            needsFullSync = true
            return
        }
        // A new sign-in, re-pair or server switch: the current server has no row
        // for this token yet, so POST the whole row. Nothing synced so far belongs
        // to this server, so a disable right after a switch DELETEs nothing here.
        if (request.server != syncedServer) {
            needsFullSync = true
            lastSyncKey = null
        }
        if (!request.enabled) {
            // Disabled: unregister on the server (best-effort); a re-enable then
            // re-runs the full sync path.
            if (lastSyncKey != null) {
                registrar.unregister()
                lastSyncKey = null
            }
            needsFullSync = true
            return
        }
        val key = "${request.scope.wire}|${request.attached.sorted()}|${request.pinned.sorted()}|${request.syncHints}"
        if (key == lastSyncKey && !needsFullSync) return
        val result = if (needsFullSync) {
            registrar.sync(request.scope, request.attached, request.pinned, request.syncHints)
        } else {
            val patched = registrar.update(request.scope, request.attached, request.pinned, request.syncHints)
            if (patched is PushRegistrarResult.Error && patched.message.contains("Not registered")) {
                registrar.sync(request.scope, request.attached, request.pinned, request.syncHints)
            } else {
                patched
            }
        }
        if (result is PushRegistrarResult.Success || result is PushRegistrarResult.ServerUnconfigured) {
            lastSyncKey = key
            syncedServer = request.server
            needsFullSync = false
        }
    }
}

/**
 * The device's stored opt-in to the server's content-free sync hint (tether
 * v130 `syncHints`). It is always sent with the registration ([PushRegistrar]),
 * so a token rotation keeps it.
 *
 * [Off] until the opt-in exists. The setting ("Keep sessions up to date in the
 * background", SYNC_DESIGN §6.2) needs a key in `core/data` (T10.1/T13.4). The
 * catch-up worker that acts on a hint is T13.4. Opting in before then would only
 * wake the device for hints that the app ignores.
 */
fun interface SyncHintsSource {
    fun optedIn(): Flow<Boolean>

    companion object Off : SyncHintsSource {
        override fun optedIn(): Flow<Boolean> = flowOf(false)
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
