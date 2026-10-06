package com.tether.app.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.InMemorySettings
import com.tether.app.client.RealTetherClient
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.20: rotating the phone keeps what was typed on the sign-in screen, as a browser resize
 * keeps the page's inputs: the server URL (saved) and the password (in the activity's memory only;
 * it never reaches a saved-instance Bundle).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class LoginRecreationTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private fun field(description: String): SemanticsNodeInteraction =
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription(description)))

    private fun typed(description: String): String? =
        field(description).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    @Test fun aRotationKeepsTheTypedUrlAndPasswordAndNeverSavesThePassword() {
        val password = "hunter2-never-on-disk-5d1b"
        val url = "tether-box.example.test"
        val client = RealTetherClient(settings = InMemorySettings(), httpClient = OkHttpClient(), scope = scope)
        var registry: androidx.compose.runtime.saveable.SaveableStateRegistry? = null
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            registry = androidx.compose.runtime.saveable.LocalSaveableStateRegistry.current
            TetherTheme(TetherSkin.Studio.mode) { LoginScreen(client = client) }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val first = arrayOfNulls<Activity>(1)
        val recreated = AtomicInteger()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !== first[0] && savedInstanceState != null && activity is ComponentActivity) {
                    recreated.incrementAndGet()
                    activity.setContent(content = content)
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        rule.activityRule.scenario.onActivity { first[0] = it }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            rule.setContent(content)
            rule.waitForIdle()
            field("Server URL").performTextInput(url)
            field("Dashboard password").performTextInput(password)
            rule.waitForIdle()
            // What the screen asks the system to keep: the URL, never the password.
            val saved = rule.runOnIdle { registry?.performSave()?.toString() }.orEmpty()
            assertTrue("the URL is saved (the check can see strings)", saved.contains(url))
            assertFalse("the password reached the saved state", saved.contains(password))
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10_000) {
                recreated.get() == 1 && rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasContentDescription("Dashboard password"))).fetchSemanticsNodes().size == 1
            }
            // The field masks what it holds: one dot per typed character, so a retained value reads as its length.
            assertEquals("•".repeat(password.length), typed("Dashboard password"))
            assertEquals(url, typed("Server URL"))
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}

