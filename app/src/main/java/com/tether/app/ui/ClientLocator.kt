package com.tether.app.ui

import android.content.Context
import com.tether.app.client.TetherClient
import com.tether.app.ui.fake.FakeTetherClient

/**
 * The wiring seam: MainActivity obtains the process-wide client through this
 * locator. Defaults to the scripted [FakeTetherClient] so the UI build runs
 * standalone; the integrator points [factory] at RealTetherClient (e.g. in an
 * Application subclass or before setContent):
 *
 *   ClientLocator.factory = { context -> RealTetherClient(context) }
 */
object ClientLocator {
    var factory: (Context) -> TetherClient = { FakeTetherClient() }

    @Volatile
    private var cached: TetherClient? = null

    /** Memoized: one client per process, created on first use. */
    fun obtain(context: Context): TetherClient =
        cached ?: synchronized(this) {
            cached ?: factory(context.applicationContext).also { cached = it }
        }
}
