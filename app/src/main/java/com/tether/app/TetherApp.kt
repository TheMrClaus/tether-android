package com.tether.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.tether.app.client.AesGcmCredentialCipher
import com.tether.app.client.DataStoreSettings
import com.tether.app.client.KeystoreCredentialKeySource
import com.tether.app.client.RealTetherClient
import com.tether.app.net.AndroidLocalNetworkAccess
import com.tether.app.push.PushChannels
import com.tether.app.push.PushController
import com.tether.app.ui.ClientLocator
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** Points the UI's ClientLocator at the real protocol client. */
class TetherApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Notification channels must exist before any FCM message can arrive.
        PushChannels.ensure(this)

        // The handler is the last line of defence: an exception that escapes a
        // background job (push, sync) is dropped instead of crashing the process.
        // Only the exception type is logged, never its message, which may carry
        // server content.
        val appScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
                Log.w("TetherApp", "Background job failed: ${e.javaClass.simpleName}")
            },
        )
        // Credentials are sealed with a non-exportable Android Keystore AES-GCM key
        // (PLAN D8); a pre-T1.4 plaintext install is migrated on first load.
        val settings = DataStoreSettings.create(
            dir = filesDir,
            scope = appScope,
            cipher = AesGcmCredentialCipher(KeystoreCredentialKeySource()),
        )
        val httpClient = OkHttpClient()
        val prefs = UiPrefs(this)

        // Wire the push subsystem alongside the client. Observes prefs (enabled
        // / scope / sets) and settings.credential; a user logout unregisters
        // through the client's onLogout hook below.
        // Firebase is initialised from env-supplied values; when absent, the
        // subsystem reports "not configured" at runtime and the app still runs.
        val push = PushController.start(
            app = this,
            settings = settings,
            prefs = prefs,
            httpClient = httpClient,
            scope = appScope,
        )

        ClientLocator.factory = { context ->
            RealTetherClient(
                settings = settings,
                httpClient = httpClient,
                scope = appScope,
                // Android 17+: tells the client when the OS blocks a LAN server.
                localNetworkAccess = AndroidLocalNetworkAccess(context),
                // Logout forgets the credential first, so push is unregistered
                // with the one that was in force (device tokens only).
                onLogout = { baseUrl, credential -> push.unregisterAfterLogout(baseUrl, credential) },
            )
        }

        // Process foreground/background ≈ the web's visibilitychange: the client
        // re-checks the link on return and lets the socket go after a grace
        // period in the background (FCM covers the background).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> ClientLocator.obtain(this).setAppForeground(true)
                    Lifecycle.Event.ON_STOP -> ClientLocator.obtain(this).setAppForeground(false)
                    else -> Unit
                }
            },
        )

    }
}
