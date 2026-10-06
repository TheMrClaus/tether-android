package com.tether.app.testsupport

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/**
 * Runs a preferences write (any [UiPrefs] suspend call, or several) from a test without ever parking
 * the Robolectric main thread in `runBlocking` (DataStore 1.2 applies an update transform in the CALLER's context, so a main thread parked in `runBlocking` behind a main-dispatched write already in flight deadlocks; ta-19vj): [block] runs on
 * [Dispatchers.IO] while the main looper is pumped, its result (or failure) is returned, and a write
 * that does not land within [timeoutMs] fails the test with [what] instead of hanging the gate.
 */
internal fun <T> runPrefsWrite(timeoutMs: Long = 30_000, what: String = "preferences write", block: suspend () -> T): T {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val job = scope.async { block() }
    val looper = shadowOf(Looper.getMainLooper())
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    try {
        while (!job.isCompleted) {
            check(System.nanoTime() < deadline) {
                "$what: the preferences DataStore did not take the write within ${timeoutMs}ms " +
                    "(a write from an earlier composition is stuck waiting on the main looper?)"
            }
            looper.idle()
            Thread.sleep(2)
        }
        // Completed: this returns the result or rethrows the failure, never waits.
        return runBlocking { job.await() }
    } finally {
        scope.cancel()
    }
}
