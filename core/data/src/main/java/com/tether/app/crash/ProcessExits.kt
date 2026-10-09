package com.tether.app.crash

import android.app.ActivityManager
import android.content.Context

/**
 * ta-otgf: what the system says about this app's recent process exits (ANR, native crash, low
 * memory, killed while frozen, a Java crash, the user stopping it). Read-only: no handler and no
 * file, the system keeps the history. Newest first.
 */
object ProcessExits {
    const val DEFAULT_COUNT = 5

    /** Up to [max] exits, newest first; empty when the system cannot say. Call off the main thread. */
    fun read(context: Context, max: Int = DEFAULT_COUNT): List<ProcessExit> = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        manager.getHistoricalProcessExitReasons(context.packageName, 0, max).map {
            ProcessExit(
                timeMs = it.timestamp,
                reason = it.reason,
                importance = it.importance,
                status = it.status,
                pid = it.pid,
                description = it.description,
            )
        }
    }.getOrDefault(emptyList())
}
