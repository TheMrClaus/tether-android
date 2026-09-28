package com.tether.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.ui.ClientLocator
import com.tether.app.ui.UiRoot

/**
 * Single-activity shell. The TetherClient is obtained through ClientLocator —
 * the integrator sets ClientLocator.factory to RealTetherClient before this
 * activity first resolves it (Application.onCreate is the natural spot).
 *
 * Every link reaches the app here (T4.4): a `tether://session/<id>` link, a
 * notification tap, an explicit http(s) link. The manifest makes this activity
 * `singleTask`, so a link from another app reaches the one existing instance
 * through [onNewIntent] instead of stacking a second copy of the app in the
 * caller's task. [UiRoot] hands each intent to the navigator once
 * (nav/DeepLinkIntents). A recreation (rotation, process restore) or a relaunch
 * from Recents does not replay the intent that first started the activity.
 */
class MainActivity : ComponentActivity() {
    private var launchIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        launchIntent = intentToRoute(intent, savedInstanceState)
        val client = ClientLocator.obtain(applicationContext)
        setContent {
            UiRoot(client = client, launchIntent = launchIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchIntent = intent
    }

    companion object {
        /** The starting intent to route, or null when it was routed before (recreation, Recents). */
        internal fun intentToRoute(intent: Intent?, savedInstanceState: Bundle?): Intent? {
            val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
            return intent.takeIf { savedInstanceState == null && !fromHistory }
        }
    }
}
