package com.tether.app.nav

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-19vj: the reset used to deadlock the main thread when the app had a preferences write in flight
 * from a main-looper coroutine (DataStore runs each transform in its caller's context).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RememberedChatResetTest {

    @Test
    fun resetsWhileAMainLooperWriteIsStillInFlight() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UiPrefs(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var where = ""
        val transformReached = CompletableDeferred<Unit>()
        try {
            // A write from the main looper (as a composition's scope would): its transform is
            // dispatched back to the main looper (UNDISPATCHED: it starts now, with no looper pass that
            // could also run the transform), which nobody runs until the reset pumps it.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                prefs.updatePreferences {
                    where = Thread.currentThread().name; transformReached.complete(Unit)
                    it.copy(lastOpenedSession = com.tether.app.ui.prefs.LastOpenedSession("/w", "s-left-over", null))
                }
            }
            // Let the DataStore's IO actor reach that transform and post it to the (paused) main looper
            // BEFORE the reset starts, which is the race the gate lost now and then.
            Thread.sleep(500)
            org.junit.Assert.assertFalse("the main-looper transform ran early on $where", transformReached.isCompleted)
            // The pre-fix helper blocks main from here; the fixed one must return.
            forgetRememberedChat(timeoutMs = 20_000)
        } finally {
            scope.cancel()
        }
        assertNull(runBlocking { prefs.preferences.first().lastOpenedSession })
    }
}
