package com.tether.app

import android.app.ActivityManager
import android.content.Context
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
import java.lang.ref.WeakReference

/**
 * Single-activity shell. The TetherClient is obtained through ClientLocator —
 * the integrator sets ClientLocator.factory to RealTetherClient before this
 * activity first resolves it (Application.onCreate is the natural spot).
 *
 * Every link reaches the app here (T4.4): a `tether://session/<id>` link, a
 * notification tap, an explicit http(s) link. [UiRoot] hands each intent to the
 * navigator once (nav/DeepLinkIntents). A recreation (rotation, process
 * restore) or a relaunch from Recents does not replay the intent that first
 * started the activity.
 *
 * One instance, always the root of Tether's own task. The manifest makes it
 * `singleTop`, not `singleTask`: a launcher relaunch then brings the task
 * forward as it was, so a Custom Tab, a permission dialog or a document picker
 * opened above it survives (singleTask would clear them). An instance created
 * anywhere else is a duplicate and never builds a UI, a view model or a client:
 * - above something in Tether's task, or inside another app's task (a caller
 *   that did not ask for a new task): it forwards a cleaned copy of its intent
 *   to the root with NEW_TASK | CLEAR_TOP | SINGLE_TOP, the flags the
 *   notification taps already use, so the root gets it through [onNewIntent]
 *   and the link wins over what sat above it;
 * - as the root of a second Tether task (a caller forcing MULTIPLE_TASK): it
 *   hands the intent to the live instance in-process, brings that task to the
 *   front and removes its own.
 */
class MainActivity : ComponentActivity() {
    private var launchIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val existing = live?.get()?.takeIf { it !== this && !it.isFinishing && !it.isDestroyed }
        if (existing != null && isTaskRoot) {
            existing.route(forwardIntent(this, intent))
            bringToFront(existing.taskId)
            finishAndRemoveTask()
            return
        }
        if (!isTaskRoot) {
            startActivity(forwardIntent(this, intent))
            finish()
            return
        }
        live = WeakReference(this)
        launchIntent = intentToRoute(intent, savedInstanceState)
        val client = ClientLocator.obtain(applicationContext)
        setContent {
            UiRoot(client = client, launchIntent = launchIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    override fun onDestroy() {
        if (live?.get() === this) live = null
        super.onDestroy()
    }

    private fun route(intent: Intent) {
        launchIntent = intent
    }

    private fun bringToFront(taskId: Int) {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        try {
            manager.appTasks.firstOrNull { it.taskInfo?.taskId == taskId }?.moveToFront()
        } catch (_: RuntimeException) {
            // The task went away meanwhile: the in-process hand-off already happened.
        }
    }

    companion object {
        /** The instance that owns the UI (the task root), if one is alive. Main thread only. */
        private var live: WeakReference<MainActivity>? = null

        /** Extras a duplicate carries over: the notification kind and tag, read as strings only. */
        private val FORWARDED_EXTRAS = listOf(PushDeepLink.EXTRA_KIND, PushDeepLink.EXTRA_TAG, "kind")

        /** The starting intent to route, or null when it was routed before (recreation, Recents). */
        internal fun intentToRoute(intent: Intent?, savedInstanceState: Bundle?): Intent? {
            val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
            return intent.takeIf { savedInstanceState == null && !fromHistory }
        }

        /**
         * What a duplicate hands to the root: the action, the data URI and the notification's
         * string extras, nothing else (no other extras, clip data, selector or URI grants), with
         * flags of our own choosing.
         */
        internal fun forwardIntent(context: Context, intent: Intent?): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = intent?.action
                data = intent?.data
                for (key in FORWARDED_EXTRAS) intent?.stringExtraOrNull(key)?.let { putExtra(key, it) }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        /** A non-String or unparcelable extra (a hostile sender can put anything there) reads as absent. */
        private fun Intent.stringExtraOrNull(name: String): String? = try {
            getStringExtra(name)
        } catch (_: RuntimeException) {
            null
        }
    }
}
