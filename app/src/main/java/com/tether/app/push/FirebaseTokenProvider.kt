package com.tether.app.push

import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

/**
 * Seam over [FirebaseMessaging.token], which is a Play Services `Task<String>`.
 * Production wires [Default]; tests inject a stub so no Firebase / Play
 * Services initialisation is required for [PushRegistrar] unit tests.
 */
fun interface FirebaseTokenProvider {
    /** Returns the FCM registration token, or null when it could not be obtained. */
    suspend fun token(): String?

    /** Production binding: delegates to [FirebaseMessaging.getInstance().token]. */
    companion object Default : FirebaseTokenProvider {
        // firebase-messaging 25.1.0 deprecated getToken/onNewToken in favour of
        // register()/onRegistered(). T12.1 checked 25.1.3 (BOM 34.19.0):
        // register() returns Task<Void> and hands the identifier only to
        // FirebaseMessagingService.onRegistered(String). It needs the manifest
        // meta-data firebase_messaging_installation_id_enabled=true, and that
        // same flag makes getToken() fail. On Play services 26.12 or newer, the
        // identifier is the Firebase Installation ID, not an FCM registration
        // token; older Play services fall back to a legacy token. The server
        // sends to `message.token`, so the switch waits on a server S-task. The
        // token API still works until then.
        @Suppress("DEPRECATION")
        override suspend fun token(): String? = try {
            // token() returns a Task<String>; await it off the IO dispatcher.
            // The 10s cap matches Play Services' own task timeout fallback.
            Tasks.await(FirebaseMessaging.getInstance().token, 10, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            // Play Services missing / not initialized / network error: the
            // caller surfaces "push not available" via the same `null` path the
            // unconfigured-server branch takes.
            null
        }
    }
}