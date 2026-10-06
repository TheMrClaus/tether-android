package com.tether.app.ui.state

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.platform.testTag
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.20: a secret field survives a configuration change in the activity's memory and is never
 * written to the saved-instance-state Bundle; a non-secret field survives it through the Bundle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RetainedSecretTest {
    @Suppress("DEPRECATION")
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rule = compose

    private companion object {
        const val SECRET = "s3cr3t-pairing-9f2c"
        const val PUBLIC = "plain-user-name-77"
    }

    private fun text(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    /** Runs [content] in the first activity and again in every recreated one, as an app's onCreate does; returns the saved Bundles. */
    private fun recreateWith(content: @Composable () -> Unit, between: () -> Unit = {}): List<Bundle> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val first = arrayOfNulls<Activity>(1)
        val recreated = AtomicInteger()
        val bundles = CopyOnWriteArrayList<Bundle>()
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
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                bundles.add(outState)
            }
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        compose.activityRule.scenario.onActivity { first[0] = it }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            compose.setContent(content)
            compose.waitForIdle()
            between()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5_000) { recreated.get() == 1 && compose.onAllNodesWithTagCount("secret") == 1 }
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
        return bundles
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    @Composable
    private fun Form() {
        var secret by rememberRetained { "" }
        var visible by rememberSaveable { mutableStateOf("") }
        Column {
            BasicTextField(secret, { secret = it }, Modifier.testTag("secret"))
            BasicTextField(visible, { visible = it }, Modifier.testTag("visible"))
        }
    }

    @Test fun aSecretSurvivesARecreationAndAPlainFieldIsRestored() {
        val bundles = recreateWith({ Form() }) {
            compose.onNodeWithTag("secret", useUnmergedTree = true).performTextReplacement(SECRET)
            compose.onNodeWithTag("visible", useUnmergedTree = true).performTextReplacement(PUBLIC)
            compose.waitForIdle()
        }
        assertEquals("the secret is still typed", SECRET, text("secret"))
        assertEquals("the plain field is restored from the Bundle", PUBLIC, text("visible"))
        assertTrue("an activity state was saved", bundles.isNotEmpty())
    }

    /** What the registry would write into the instance-state Bundle: the plain field is in it, the secret is not. */
    @Test fun theSaveableRegistryHoldsThePlainFieldAndNeverTheSecret() {
        var registry: androidx.compose.runtime.saveable.SaveableStateRegistry? = null
        compose.setContent {
            Form()
            registry = androidx.compose.runtime.saveable.LocalSaveableStateRegistry.current
        }
        compose.onNodeWithTag("secret", useUnmergedTree = true).performTextReplacement(SECRET)
        compose.onNodeWithTag("visible", useUnmergedTree = true).performTextReplacement(PUBLIC)
        compose.waitForIdle()
        val saved = compose.runOnIdle { registry?.performSave()?.toString() }
        assertTrue("the plain field is saved (the check can see strings)", saved.orEmpty().contains(PUBLIC))
        assertFalse("the secret reached the saved state", saved.orEmpty().contains(SECRET))
    }

    @Test fun aSecretIsDroppedWhenItsFieldLeavesTheScreenForGood() {
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) {
                var secret by rememberRetained { "" }
                BasicTextField(secret, { secret = it }, Modifier.testTag("secret"))
            }
        }
        compose.onNodeWithTag("secret", useUnmergedTree = true).performTextReplacement(SECRET)
        compose.waitForIdle()
        assertEquals(SECRET, text("secret"))
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        assertEquals("a reopened form starts empty, as a plain remember did", "", text("secret"))
    }

    @Test fun aChangedInputStartsTheSecretOver() {
        var server by mutableStateOf("a")
        compose.setContent {
            var secret by rememberRetained(server) { "" }
            BasicTextField(secret, { secret = it }, Modifier.testTag("secret"))
        }
        compose.onNodeWithTag("secret", useUnmergedTree = true).performTextReplacement(SECRET)
        compose.waitForIdle()
        compose.runOnIdle { server = "b" }
        compose.waitForIdle()
        assertEquals("", text("secret"))
    }
}

/** ta-coik.20: the real instance-state Bundle of a real activity, and a new activity built from it (process death). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RetainedSecretBundleTest {
    @Test fun theSavedBundleHoldsThePlainFieldNotTheSecretAndAProcessDeathStartsTheSecretEmpty() {
        val secret = "s3cr3t-token-41ac"
        val plain = "plain-name-0d9e"
        val first = org.robolectric.Robolectric.buildActivity(FormActivity::class.java).setup()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        first.get().secret!!.value = secret
        first.get().visible!!.value = plain
        val out = Bundle()
        first.saveInstanceState(out)
        assertTrue("the plain field is in the Bundle (the check can see strings)", holds(out, plain))
        assertFalse("the secret reached the instance-state Bundle", holds(out, secret))
        // Process death: a new activity, in a new process, from that Bundle alone.
        val second = org.robolectric.Robolectric.buildActivity(FormActivity::class.java).setup(out)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(plain, second.get().visible!!.value)
        assertEquals("", second.get().secret!!.value)
    }
}

/** An activity that draws [FormHolder]'s form; its field states are reachable so a test can type without a rule. */
class FormActivity : ComponentActivity() {
    var secret: androidx.compose.runtime.MutableState<String>? = null
    var visible: androidx.compose.runtime.MutableState<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val s = rememberRetained { "" }
            val v = rememberSaveable { mutableStateOf("") }
            secret = s
            visible = v
        }
    }
}

private fun holds(bundle: Bundle, needle: String): Boolean = bundle.keySet().any { key ->
    @Suppress("DEPRECATION")
    holdsValue(bundle.get(key), needle)
}

private fun holdsValue(v: Any?, needle: String): Boolean = when (v) {
    null -> false
    is CharSequence -> v.toString().contains(needle)
    is Bundle -> holds(v, needle)
    is Iterable<*> -> v.any { holdsValue(it, needle) }
    is Array<*> -> v.any { holdsValue(it, needle) }
    is android.util.SparseArray<*> -> (0 until v.size()).any { holdsValue(v.valueAt(it), needle) }
    else -> v.toString().contains(needle)
}
