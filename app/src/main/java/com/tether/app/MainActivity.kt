package com.tether.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.push.PushDeepLink
import com.tether.app.ui.ClientLocator
import com.tether.app.ui.UiRoot

/**
 * Single-activity shell. The TetherClient is obtained through ClientLocator —
 * the integrator sets ClientLocator.factory to RealTetherClient before this
 * activity first resolves it (Application.onCreate is the natural spot).
 *
 * A notification tap routes back here (see [PushDeepLink]); [UiRoot] consumes
 * it once. A tap while the activity is alive arrives through [onNewIntent]. A
 * recreation (rotation, process restore) or a relaunch from Recents does not
 * replay the tap that first started the activity.
 */
class MainActivity : ComponentActivity() {
    private var pushIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        pushIntent = intent.takeIf { savedInstanceState == null && !fromHistory }
        val client = ClientLocator.obtain(applicationContext)
        setContent {
            UiRoot(client = client, pushIntent = pushIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pushIntent = intent
    }
}
