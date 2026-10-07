package com.tether.app.push

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.tether.app.client.Credential
import com.tether.app.client.SettingsStore
import com.tether.app.ui.prefs.UiPrefs
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
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
 * Firebase initialisation (T12.1 round 2): there is no google-services plugin
 * and no build-time config, and an app process has no environment, so the
 * options come from the paired server's fcm-config response
 * ([FirebaseClientConfig], via [AndroidFirebaseInitializer]). [start] restores
 * the last working config, so FirebaseApp is up before any FCM delivery. Until
 * the server sends the config, push reports "not available" and the app still
 * runs.
 */
class PushController(
    private val app: Application,
    private val settings: SettingsStore,
    private val prefs: PushPrefs,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val tokenProvider: FirebaseTokenProvider = FirebaseTokenProvider.Default,
    private val firebase: FirebaseInitializer = AndroidFirebaseInitializer(app),
    private val syncHints: SyncHintsSource = SyncHintsSource.Off,
    private val registrarFactory: (PushRegistrar) -> PushRegistrar = { it },
) : PushRegistration {
    private val statusFlow = MutableStateFlow<PushRegistrationStatus>(PushRegistrationStatus.Idle)

    /** T12.2: the registration's outcome, for the Settings push row. */
    override val status: StateFlow<PushRegistrationStatus> = statusFlow.asStateFlow()

    /** Lazily-created registrar; tests inject a fake via [registrarFactory]. */
    private val registrar: PushRegistrar by lazy {
        registrarFactory(PushRegistrar(settings, httpClient, tokenProvider, firebase))
    }

    private val coordinator: PushSyncCoordinator by lazy { PushSyncCoordinator(registrar, statusFlow) {
            // Local first (ta-ouu): forget the accepted project, so a re-pair may
            // accept another one from the same server, before the network
            // delete. A hung delete (cut by the 5 s logout bound) or a failed one
            // then never leaves the old binding behind.
            // A throwing forget() must not skip the token delete (ta-6z4): it has
            // its own guard, and cancellation still propagates.
            try {
                firebase.forget()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort by contract; the token delete below still runs.
            }
            tokenProvider.delete()
        } }

    /** T12.2: the web's refresh on the settings dialog's mount; see [PushRegistration.refresh]. */
    override fun refresh() {
        scope.launch { guarded { coordinator.refresh() } }
    }

    /** T12.2: the web's Re-enable on a stale registration; see [PushRegistration.reEnable]. */
    override fun reEnable() {
        scope.launch { guarded { coordinator.reEnable() } }
    }

    fun start() {
        // Bring FirebaseApp up from the last server config that worked, before
        // any FCM delivery in this process needs it. The first sync with a
        // server supplies (and saves) the config.
        (firebase as? AndroidFirebaseInitializer)?.restore()

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
        // The two flows are only the trigger. combine() collects them
        // independently, so it can briefly pair a new URL with the old credential.
        // The identity is read from session(), the store's untorn (URL,
        // credential) snapshot, so a server switch yields one new identity.
        val server = combine(settings.baseUrl, settings.credential) { _, _ -> }
            .map { settings.session().let { PushServerIdentity.of(it.baseUrl, it.credential) } }
            .distinctUntilChanged()
        combine(prefsRequest, server) { request, identity -> request.copy(server = identity) }
            .distinctUntilChanged()
            .onEach { request -> guarded { coordinator.onRequest(request) } }
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
        guarded { coordinator.onLoggedOut(baseUrl, credential) }
    }

    /**
     * Push is best-effort: a failed round-trip must never end the collector (push
     * would stop until a restart) or reach the app scope (a crash, and on every
     * start a crash loop). Cancellation still propagates. The next trigger
     * retries. Nothing about the failure is logged.
     */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Swallowed by design; see above.
        }
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
            PushRegistration.current = controller
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
            controller.scope.launch { controller.guarded { controller.coordinator.onTokenRotated() } }
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
internal class PushSyncCoordinator(
    private val registrar: PushRegistrar,
    /** T12.2: where each reconcile's outcome is published, for the Settings push row. */
    private val status: MutableStateFlow<PushRegistrationStatus> = MutableStateFlow(PushRegistrationStatus.Idle),
    /**
     * Logout, and Re-enable: forget the accepted project and invalidate the FCM token everywhere
     * (best-effort; see [FirebaseTokenProvider.delete]).
     */
    private val deleteToken: suspend () -> Unit = {},
) {
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

    /** The web's refresh (use-push-notifications.ts:189-191): a full POST of the latest request. */
    suspend fun refresh() = mutex.withLock {
        needsFullSync = true
        latest?.let { reconcile(it) }
    }

    /**
     * The web's Re-enable on a stale subscription (use-push-notifications.ts:240-255: drop the
     * old subscription, subscribe with the server's current key, register). Here: forget the
     * accepted Firebase project and its token, so the full POST that follows accepts the
     * project the server names now and registers a fresh token.
     */
    suspend fun reEnable() = mutex.withLock {
        val request = latest ?: return@withLock
        if (request.server == null || !request.enabled) return@withLock reconcile(request)
        status.value = PushRegistrationStatus.Registering
        try {
            deleteToken()
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            // Best-effort by contract; the POST below reports what came of it.
        }
        lastSyncKey = null
        syncedServer = null
        needsFullSync = true
        reconcile(request)
    }

    suspend fun onLoggedOut(baseUrl: String, credential: Credential) = mutex.withLock {
        // Reset first: the logout bound may cancel what follows, and nothing
        // synced belongs to a signed-in server any more.
        lastSyncKey = null
        syncedServer = null
        needsFullSync = true
        status.value = PushRegistrationStatus.Idle
        registrar.unregister(baseUrl, credential)
        // Also kill the token itself: any other server that still holds it (a
        // failed DELETE, an earlier pairing) prunes its row on the next send.
        try {
            deleteToken()
        } catch (e: CancellationException) {
            // The caller's logout bound (or scope) ended it: honour that.
            throw e
        } catch (_: RuntimeException) {
            // Best-effort by contract.
        }
    }

    private suspend fun reconcile(request: PushSyncRequest) {
        if (request.server == null) {
            // Signed out, or a cookie login: no row can exist for this device. The
            // logout hook ([onLoggedOut]) already DELETEd with the old credential.
            lastSyncKey = null
            syncedServer = null
            needsFullSync = true
            status.value = PushRegistrationStatus.NoDevice
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
            status.value = PushRegistrationStatus.Idle
            return
        }
        val key = "${request.scope.wire}|${request.attached.sorted()}|${request.pinned.sorted()}|${request.syncHints}"
        if (key == lastSyncKey && !needsFullSync) return
        if (needsFullSync) status.value = PushRegistrationStatus.Registering
        val result = try {
            if (needsFullSync) {
                registrar.sync(request.scope, request.attached, request.pinned, request.syncHints)
            } else {
                val patched = registrar.update(request.scope, request.attached, request.pinned, request.syncHints)
                if (patched is PushRegistrarResult.Error && patched.message.contains("Not registered")) {
                    registrar.sync(request.scope, request.attached, request.pinned, request.syncHints)
                } else {
                    patched
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The registrar never throws by contract; if something under it does, the row still
            // leaves "checking" (the web's catch-all, use-push-notifications.ts:186-193).
            status.value = PushRegistrationStatus.Failed(e.message ?: "Push status could not be checked.")
            throw e
        }
        status.value = when (result) {
            PushRegistrarResult.Success -> PushRegistrationStatus.Registered
            PushRegistrarResult.ServerUnconfigured -> PushRegistrationStatus.ServerUnconfigured
            PushRegistrarResult.ProjectChanged -> PushRegistrationStatus.ProjectChanged
            is PushRegistrarResult.Error -> PushRegistrationStatus.Failed(result.message)
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
