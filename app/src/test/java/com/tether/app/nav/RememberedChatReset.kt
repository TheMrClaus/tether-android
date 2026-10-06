package com.tether.app.nav

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/**
 * ta-coik.41: the app's preferences DataStore is a process singleton, so the chat one test had on
 * screen (`lastOpenedSession`, dashboard.tsx 90fbb9f :829-835) would be restored at the next test's
 * cold start. A test that boots the real root starts from no remembered chat.
 *
 * ta-19vj: this must never `runBlocking` the (Robolectric) main thread. DataStore applies each
 * update's transform in the CALLER's coroutine context, one message at a time, so a write the app
 * itself had in flight from a composition (main-looper dispatcher) leaves the DataStore's actor
 * waiting for the main looper to run that transform; a main thread parked in `runBlocking` behind it
 * never runs it (a deadlock, seen as a gate hang in `UiRootPushTapTest.render`). The reset therefore
 * runs on [Dispatchers.IO] (its own transform needs no looper) while the main looper is pumped until
 * it lands, and a reset that does not land within [timeoutMs] FAILS the test instead of hanging.
 */
internal fun forgetRememberedChat(timeoutMs: Long = 30_000) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val reset = scope.async {
        UiPrefs(context).updatePreferences { it.copy(lastOpenedSession = null, lastOpenedByOrigin = emptyMap()) }
    }
    val looper = shadowOf(Looper.getMainLooper())
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    try {
        while (!reset.isCompleted) {
            check(System.nanoTime() < deadline) {
                "forgetRememberedChat: the preferences DataStore did not take the reset within ${timeoutMs}ms " +
                    "(a write from an earlier composition is stuck waiting on the main looper?)"
            }
            looper.idle()
            Thread.sleep(2)
        }
        // Completed: this returns the result or rethrows the failure, never waits.
        runBlocking { reset.await() }
    } finally {
        scope.cancel()
    }
}
