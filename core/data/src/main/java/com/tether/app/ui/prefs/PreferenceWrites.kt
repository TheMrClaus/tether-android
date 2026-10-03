package com.tether.app.ui.prefs

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private const val LOG_TAG = "TetherPrefs"

/**
 * A best-effort preference write, like the web's `localStorage.setItem` in a try/catch
 * (hooks/use-preferences.ts `update`): a disk that refuses the write (an IOException, a corrupt
 * file) is logged by exception type only, never with what was being written, and is otherwise
 * lost silently. Cancellation is never swallowed.
 */
suspend fun bestEffortPreferenceWrite(write: suspend () -> Unit) {
    try {
        write()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { Log.w(LOG_TAG, "preference write failed: ${e.javaClass.simpleName}") }
    }
}

/** [bestEffortPreferenceWrite] launched on [this] scope: a fire-and-forget write that cannot crash the app. */
fun CoroutineScope.launchPreferenceWrite(write: suspend () -> Unit): Job = launch { bestEffortPreferenceWrite(write) }
