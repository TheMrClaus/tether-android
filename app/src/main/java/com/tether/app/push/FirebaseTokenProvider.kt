package com.tether.app.push

import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Seam over the Firebase token calls, which are Play Services `Task`s.
 * Production wires [Default]; tests inject a stub so no Firebase / Play
 * Services initialisation is required for [PushRegistrar] unit tests.
 */
fun interface FirebaseTokenProvider {
    /** Returns the FCM registration token, or null when it could not be obtained. */
    suspend fun token(): String?

    /**
     * Logout: invalidate the current token everywhere, best-effort. A server
     * that still holds it gets 404/410 on its next send and prunes the row.
     * Never throws (except for cancellation). Stubs default to doing nothing.
     */
    suspend fun delete() {}

    /** Production binding over [FirebaseMessaging]; see [PlayServicesTokenProvider]. */
    companion object Default : FirebaseTokenProvider by PlayServicesTokenProvider(
        // firebase-messaging 25.1.0 deprecated getToken/deleteToken/onNewToken in
        // favour of register()/onRegistered(). T12.1 checked 25.1.3 (BOM 34.19.0):
        // register() returns Task<Void> and hands the identifier only to
        // FirebaseMessagingService.onRegistered(String). It needs the manifest
        // meta-data firebase_messaging_installation_id_enabled=true, and that
        // same flag makes getToken() fail. On Play services 26.12 or newer, the
        // identifier is the Firebase Installation ID, not an FCM registration
        // token; older Play services fall back to a legacy token. The server
        // sends to `message.token`, so the switch waits on a server S-task. The
        // token API still works until then.
        getToken = @Suppress("DEPRECATION") { FirebaseMessaging.getInstance().token },
        deleteToken = @Suppress("DEPRECATION") { FirebaseMessaging.getInstance().deleteToken() },
    )
}

/**
 * The real token calls. Every Play services call starts on [io], never on the
 * caller's thread. Logout reaches [delete] from the UI (TetherViewModel.logout
 * runs on Main), and the old `Tasks.await` threw on the main thread; the
 * `catch` swallowed that, so the token was never deleted (round 3). The wait is
 * [await] under [withTimeoutOrNull], so it is cancellable: a logout timeout or a
 * cancelled scope cuts it short, and propagates to the caller.
 */
internal class PlayServicesTokenProvider(
    private val getToken: () -> Task<String>,
    private val deleteToken: () -> Task<Void>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = 10_000,
) : FirebaseTokenProvider {

    override suspend fun token(): String? = bestEffort { getToken().await() }

    override suspend fun delete() {
        bestEffort { deleteToken().await() }
    }

    // withTimeoutOrNull answers null for its own timeout only. A caller's timeout
    // (the 5 s logout bound) or cancellation is rethrown below, never taken as
    // this call's own (ta-ouu): catching TimeoutCancellationException here would
    // swallow the caller's too.
    private suspend fun <T> bestEffort(call: suspend () -> T): T? = try {
        withContext(io) { withTimeoutOrNull(timeoutMs) { call() } }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Play services missing, Firebase not initialised, offline: the caller
        // treats null as "push not available" (token) or "nothing more to do"
        // (delete: the DELETE to the server already went out).
        null
    }
}
