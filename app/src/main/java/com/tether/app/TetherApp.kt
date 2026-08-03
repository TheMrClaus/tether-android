package com.tether.app

import android.app.Application
import com.tether.app.client.DataStoreSettings
import com.tether.app.client.RealTetherClient
import com.tether.app.push.PushController
import com.tether.app.push.TetherFcmService
import com.tether.app.ui.ClientLocator
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** Points the UI's ClientLocator at the real protocol client. */
class TetherApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Notification channels must exist before any FCM message can arrive.
        TetherFcmService.ensureChannels(this)

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = DataStoreSettings.create(filesDir, appScope)
        val httpClient = OkHttpClient()
        val prefs = UiPrefs(this)

        ClientLocator.factory = { context ->
            RealTetherClient(
                settings = settings,
                httpClient = httpClient,
                scope = appScope,
            )
        }

        // Wire the push subsystem alongside the client. Observes prefs (enabled
        // / scope / sets) and settings.credential (logout → unregister).
        // Firebase is initialised from env-supplied values; when absent, the
        // subsystem reports "not configured" at runtime and the app still runs.
        PushController.start(
            app = this,
            settings = settings,
            prefs = prefs,
            httpClient = httpClient,
            scope = appScope,
        )
    }
}
