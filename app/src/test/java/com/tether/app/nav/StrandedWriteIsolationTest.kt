package com.tether.app.nav

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.serverOrigin
import com.tether.app.ui.prefs.UiPrefs
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-9tot: a write a composition issued whose main-looper dispatcher is never run again (a test tearing
 * its root down, the paused Robolectric looper reset) strands that DataStore's actor for good, because
 * DataStore runs the transform in the CALLER's context. The preferences store was a process singleton,
 * so the next test in the JVM could not write at all (`runPrefsWrite` timing out in ShareFlowTest and
 * LoginVariantPerServerTest, under load). It is one store per Application now (a test's own), so both
 * tests below, in whatever order they run, must be able to write BEFORE they strand the store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StrandedWriteIsolationTest {
    private object Never : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private fun writeThenStrand(view: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val origin = serverOrigin("https://share.example")
        runPrefsWrite(timeoutMs = 20_000, what = "the first write of a test") { UiPrefs(context).setLastView(origin, view) }
        assertEquals(view, runBlocking { UiPrefs(context).viewBoot(origin).storedView })
        val scope = CoroutineScope(SupervisorJob() + Never)
        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                UiPrefs(context).updatePreferences { it.copy(lastOpenedSession = null) }
            }
            // The actor now waits for a dispatch on `Never`, as one waits on a main looper nobody pumps.
            Thread.sleep(300)
        } finally {
            scope.cancel()
        }
    }

    @Test fun aTestThatStrandsTheStoreLeavesTheNextTestAbleToWriteOne() = writeThenStrand("sessions")

    @Test fun aTestThatStrandsTheStoreLeavesTheNextTestAbleToWriteTwo() = writeThenStrand("files")
}
