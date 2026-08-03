package com.tether.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tether.app.push.TetherFcmService
import com.tether.app.ui.ClientLocator
import com.tether.app.ui.UiRoot

/**
 * Single-activity shell. The TetherClient is obtained through ClientLocator —
 * the integrator sets ClientLocator.factory to RealTetherClient before this
 * activity first resolves it (Application.onCreate is the natural spot).
 *
 * A push notification tap routes back here with the `tether.push.*` extras
 * (see [TetherFcmService]); [UiRoot] consumes them to auto-select a session.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val client = ClientLocator.obtain(applicationContext)
        setContent {
            UiRoot(client = client, pushIntent = intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
