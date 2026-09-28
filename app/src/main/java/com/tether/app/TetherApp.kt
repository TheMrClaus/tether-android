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
import com.tether.app.client.sync.REDUCER_VERSION
import com.tether.app.mirror.AndroidMirrorDbFactory
import com.tether.app.mirror.JournalMirror
import com.tether.app.mirror.MirrorKeyStore
import com.tether.app.net.AndroidLocalNetworkAccess
import com.tether.app.push.PushChannels
import com.tether.app.push.PushController
import com.tether.app.ui.ClientLocator
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import okhttp3.OkHttpClient

/** Points the UI's ClientLocator at the real protocol client. */
class TetherApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Notification channels must exist before any FCM message can arrive.
        PushChannels.ensure(this)

        // Fail-fast for the settings store and the client; push alone gets a
        // child scope that contains and logs (class name only) what escapes it.
        val appScope = AppScopes.app()
        val pushScope = AppScopes.push(appScope)
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
            scope = pushScope,
        )

        // T13.1 journal mirror (SYNC_DESIGN §2, §8): per-row AES-GCM under a data key wrapped by
        // its own non-exportable Keystore key; the DB lives in databases/ and the wrapped key in
        // noBackupFilesDir, both excluded from backup and transfer.
        val mirrorDbs = AndroidMirrorDbFactory(this)
        val mirrorKeys = MirrorKeyStore(
            file = File(noBackupFilesDir, MirrorKeyStore.KEY_FILE),
            kek = AesGcmCredentialCipher(MirrorKeyStore.keystoreKek()),
        )
        val mirror = if (BuildConfig.MIRROR_ENABLED) {
            JournalMirror(
                dbFactory = mirrorDbs,
                keyStore = mirrorKeys,
                reducerVersion = REDUCER_VERSION,
                scope = appScope,
                // Exception types only (JournalMirror never passes content here).
                log = { Log.w("TetherMirror", it) },
            )
        } else {
            appScope.launch(Dispatchers.IO) { runCatching { JournalMirror.purge(mirrorDbs, mirrorKeys) } }
            null
        }

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
                mirror = mirror,
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
