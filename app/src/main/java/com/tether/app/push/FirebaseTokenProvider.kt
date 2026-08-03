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