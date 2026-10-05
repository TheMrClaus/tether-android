package com.tether.app.nav

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.tether.app.MainActivity
import com.tether.app.client.TetherClient
import com.tether.app.nav.NavTestClient.Companion.LISTED
import com.tether.app.push.PushDeepLink
import com.tether.app.ui.ClientLocator
import com.tether.app.ui.TetherViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * T4.4 round 2: one MainActivity, the root of Tether's task, and never a second UI, view model or
 * client. Robolectric does not model launch modes or task stacks, so the platform half (singleTop
 * keeps what was opened above the root on a launcher relaunch; CLEAR_TOP | SINGLE_TOP delivers to
 * the root) is pinned by the manifest test; these tests pin the app's half: what a duplicate does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityInstanceTest {

    private val client = NavTestClient()
    private var obtained = 0
    private lateinit var savedFactory: (Context) -> TetherClient
    private val launched = mutableListOf<ActivityController<MainActivity>>()
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        savedFactory = ClientLocator.factory
        ClientLocator.installForTest(null)
        ClientLocator.factory = { obtained++; client }
        forgetRememberedChat()
    }

    @After
    fun tearDown() {
        for (controller in launched) if (!controller.get().isDestroyed) controller.pause().stop().destroy()
        idle()
        MainActivity.forwardObserver = null
        ClientLocator.factory = savedFactory
        ClientLocator.installForTest(null)
        assertEquals("a link changed server state", emptyList<String>(), client.stateChanges.toList())
    }

    private fun idle() = repeat(5) { shadowOf(Looper.getMainLooper()).idle() }

    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClass(app, MainActivity::class.java)
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(app, MainActivity::class.java)

    /** A fully started root: the instance that owns the UI. */
    private fun root(intent: Intent): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, intent).also {
            launched += it
            shadowOf(it.get()).setIsTaskRoot(true)
            it.setup()
            idle()
        }

    /** A second instance, created only (a duplicate finishes inside onCreate). */
    private fun duplicate(intent: Intent, isTaskRoot: Boolean): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, intent).also {
            launched += it
            shadowOf(it.get()).setIsTaskRoot(isTaskRoot)
            it.create()
            idle()
        }

    private val ActivityController<MainActivity>.vm: TetherViewModel
        get() = ViewModelProvider(get())[TetherViewModel::class.java]

    private fun ActivityController<MainActivity>.builtNoUi(): Boolean =
        get().findViewById<ViewGroup>(android.R.id.content).childCount == 0

    private val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    @Test
    fun aCopyAboveTheRootForwardsTheLinkToTheRootAndBuildsNothing() {
        val main = root(launcher())
        val copy = duplicate(view("tether://session/$LISTED"), isTaskRoot = false)
        assertTrue(copy.get().isFinishing)
        assertTrue(copy.builtNoUi())
        val forwarded = shadowOf(copy.get()).nextStartedActivity
        assertEquals(MainActivity::class.java.name, forwarded.component?.className)
        assertEquals(Intent.ACTION_VIEW, forwarded.action)
        assertEquals("tether://session/$LISTED", forwarded.dataString)
        assertEquals(expectedFlags, forwarded.flags)
        assertNull(shadowOf(copy.get()).nextStartedActivity)
        // CLEAR_TOP | SINGLE_TOP delivers it to the root: the session opens exactly once.
        main.newIntent(forwarded)
        idle()
        assertEquals(LISTED, main.vm.selectedSessionId.value)
        assertEquals(1, client.attached.count { it == LISTED })
        assertEquals("one client for the process", 1, obtained)
    }

    @Test
    fun theCopyIsAlreadyFinishingWhenItsForwardStarts() {
        // CLEAR_TOP | SINGLE_TOP delivers to the topmost MainActivity that is not finishing. A copy
        // still alive at that instant would receive its own link; the root would never see it.
        val finishingAtStart = mutableListOf<Boolean>()
        MainActivity.forwardObserver = { copy, _ -> finishingAtStart += copy.isFinishing }
        root(launcher())
        duplicate(view("tether://session/$LISTED"), isTaskRoot = false)
        assertEquals(listOf(true), finishingAtStart)
    }

    @Test
    fun aCopyInAnotherAppsTaskBeforeTetherRunsBecomesTheRootThroughTheForward() {
        val copy = duplicate(view("tether://session/$LISTED"), isTaskRoot = false)
        assertTrue(copy.get().isFinishing)
        assertEquals("the copy never obtains a client", 0, obtained)
        val forwarded = shadowOf(copy.get()).nextStartedActivity
        // NEW_TASK starts Tether's own task; its root routes the link.
        val main = root(forwarded)
        assertEquals(LISTED, main.vm.selectedSessionId.value)
        assertEquals(1, client.attached.count { it == LISTED })
        assertEquals(1, obtained)
    }

    @Test
    fun aSecondRootHandsTheLinkToTheLiveInstanceAndRemovesItsTask() {
        val main = root(launcher())
        val second = duplicate(view("tether://session/$LISTED"), isTaskRoot = true)
        assertTrue(second.get().isFinishing)
        assertTrue(second.builtNoUi())
        assertNull("handed off in-process, not re-launched", shadowOf(second.get()).nextStartedActivity)
        idle()
        assertEquals(LISTED, main.vm.selectedSessionId.value)
        assertEquals(1, client.attached.count { it == LISTED })
        assertEquals(1, obtained)
    }

    @Test
    fun aLauncherRelaunchOfTheRootStartsAndFinishesNothing() {
        val main = root(launcher())
        // Something opened above the root (a Custom Tab from a chat link).
        main.get().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")))
        shadowOf(main.get()).nextStartedActivity
        main.newIntent(launcher())
        idle()
        assertNull(shadowOf(main.get()).nextStartedActivity)
        assertFalse(main.get().isFinishing)
        // ta-coik.41: nothing is opened by the relaunch (the web's one-time pick may hold the first chat).
        assertNull(main.vm.pendingSessionId.value)
    }

    /**
     * ta-coik.39: every open of a chat attaches it (as the web's ChatView mount does), so only an
     * open may: a configuration change or the activity leaving and re-entering the foreground
     * recomposes the chat without opening it again.
     */
    @Test
    fun onlyAnOpenAttachesNotARecreationOrALifecycleReEntry() {
        val main = root(launcher())
        main.vm.selectSession(LISTED) // the drawer's tap
        idle()
        assertEquals(1, client.attached.count { it == LISTED })
        main.recreate()
        idle()
        main.pause().stop()
        idle()
        main.start().resume()
        idle()
        main.recreate()
        idle()
        assertEquals(LISTED, main.vm.selectedSessionId.value)
        assertEquals("one open, one attach", 1, client.attached.count { it == LISTED })
        main.vm.selectSession(LISTED) // opened again: attached again (the client decides what goes out)
        idle()
        assertEquals(2, client.attached.count { it == LISTED })
    }

    @Test
    fun aRecreatedRootStaysTheRoot() {
        val main = root(view("tether://session/$LISTED"))
        main.recreate()
        idle()
        assertFalse(main.get().isFinishing)
        assertFalse(main.builtNoUi())
        assertEquals(1, obtained)
        assertEquals(1, client.attached.count { it == LISTED })
    }

    @Test
    fun theForwardCarriesOnlyTheActionTheDataAndTheNotificationStrings() {
        val hostile = Intent(PushDeepLink.ACTION_OPEN, Uri.parse("tether://session/s1"))
            .putExtra(PushDeepLink.EXTRA_KIND, "approval")
            .putExtra(PushDeepLink.EXTRA_TAG, "tether-approval-x")
            .putExtra("kind", "question")
            .putExtra("tether.push.sessionId", "s2")
            .putExtra("url", "/?session=s2")
            .putExtra("nested", Bundle().apply { putString("a", "b") })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        hostile.clipData = ClipData.newRawUri("x", Uri.parse("content://evil/x"))
        hostile.selector = Intent(Intent.ACTION_VIEW, Uri.parse("tether://session/s3"))
        val forwarded = MainActivity.forwardIntent(app, hostile)
        assertEquals(PushDeepLink.ACTION_OPEN, forwarded.action)
        assertEquals("tether://session/s1", forwarded.dataString)
        assertEquals(setOf(PushDeepLink.EXTRA_KIND, PushDeepLink.EXTRA_TAG, "kind"), forwarded.extras?.keySet())
        assertNull(forwarded.clipData)
        assertNull(forwarded.selector)
        assertEquals(expectedFlags, forwarded.flags)
        // A non-String kind reads as absent.
        val odd = MainActivity.forwardIntent(app, Intent(PushDeepLink.ACTION_OPEN).putExtra(PushDeepLink.EXTRA_KIND, 7))
        assertNull(odd.extras)
    }
}
