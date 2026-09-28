package com.tether.app

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * The process-wide coroutine scopes wired in [TetherApp.onCreate].
 *
 * Only push is allowed to fail quietly. The settings store and the protocol
 * client run on [app], which has no exception handler: an exception that
 * escapes one of their jobs reaches the thread's uncaught-exception handler and
 * crashes the app, as before T12.1. A dead 'configured' collector must not
 * leave the signed-in UI up after a logout.
 */
internal object AppScopes {

    /** Fail-fast: an uncaught exception in a job here is a crash. */
    fun app(dispatcher: CoroutineDispatcher = Dispatchers.Default): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Push's own child of [parent]. Push is best-effort, so an exception that
     * escapes a push job is dropped here instead of crashing the process (a
     * malformed server reply would otherwise crash every start). Only the
     * exception's class name is logged, never its message, which may carry
     * server content. A failed push job never cancels [parent] or another push
     * job; cancelling [parent] cancels push.
     */
    fun push(parent: CoroutineScope, log: (String) -> Unit = { Log.w(TAG, it) }): CoroutineScope =
        CoroutineScope(
            parent.coroutineContext +
                SupervisorJob(parent.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> log("Background push job failed: ${e.javaClass.simpleName}") },
        )

    const val TAG = "TetherApp"
}
