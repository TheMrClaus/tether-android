package com.tether.app.nav

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.tether.app.MainActivity
import com.tether.app.client.ConnectionState
import com.tether.app.nav.NavTestClient.Companion.LISTED
import com.tether.app.nav.NavTestClient.Companion.OTHER_LISTED
import com.tether.app.nav.NavTestClient.Companion.PAIRED
import com.tether.app.push.PushDeepLink
import com.tether.app.push.PushKind
import com.tether.app.push.PushMessage
import com.tether.app.ui.ClientLocator
import com.tether.app.ui.TetherViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.robolectric.shadows.ShadowLog

/**
 * T4.4 end to end through the real [MainActivity] and UiRoot: a cold start with a link, a warm
 * link through onNewIntent, a link while signed out, a notification tap, a stale id, recreation.
 * Every test also checks that a link changed nothing on the server and signed nobody in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityDeepLinkTest {

    private lateinit var client: NavTestClient
    private val launched = mutableListOf<ActivityController<MainActivity>>()

    @Before
    fun setUp() {
        // "Remove animations": the status indicators draw a static frame, so the looper idles.
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        ShadowLog.clear()
        forgetRememberedChat()
    }

    @After
    fun tearDown() {
        // Destroyed explicitly: a live composition keeps real-time delays (the toast's auto-dismiss,
        // the input guard) scheduled, and they would resume inside a later test.
        for (controller in launched) controller.pause().stop().destroy()
        idle()
        ClientLocator.installForTest(null)
        assertEquals("a link changed server state", emptyList<String>(), client.stateChanges.toList())
    }

    private fun install(c: NavTestClient): NavTestClient = c.also {
        client = it
        ClientLocator.installForTest(it)
    }

    /**
     * ta-coik.41: no link selected anything. On Sessions with nothing selected the web's one-time
     * pick (dashboard.tsx 90fbb9f :752-763) chooses the workspace's first chat; that is a pick, never
     * a link's pending target, and never [linked].
     */
    private fun assertNoLinkSelection(vm: TetherViewModel, linked: String = LISTED) {
        assertFalse("a link's target was selected", vm.selectionPending.value)
        assertNotEquals(linked, vm.selectedSessionId.value)
    }

    private fun idle() = repeat(5) { shadowOf(Looper.getMainLooper()).idle() }

    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)

    private fun launch(intent: Intent): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().also {
            launched += it
            idle()
        }

    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)

    private val ActivityController<MainActivity>.vm: TetherViewModel
        get() = ViewModelProvider(get())[TetherViewModel::class.java]

    @Test
    fun coldStartOpensTheLinkedSession() {
        install(NavTestClient())
        val activity = launch(view("tether://session/$LISTED"))
        assertEquals(LISTED, activity.vm.selectedSessionId.value)
        assertTrue(client.attached.toString(), LISTED in client.attached)
        assertNull(activity.vm.activeToast.value)
    }

    @Test
    fun coldStartWithTheWebsShapeOnThePairedOrigin() {
        install(NavTestClient())
        val activity = launch(view("$PAIRED/?session=$LISTED"))
        assertEquals(LISTED, activity.vm.selectedSessionId.value)
    }

    @Test
    fun warmLinkArrivesThroughOnNewIntent() {
        install(NavTestClient())
        val activity = launch(launcher())
        assertNoLinkSelection(activity.vm)
        activity.newIntent(view("tether://session/$LISTED"))
        idle()
        assertEquals(LISTED, activity.vm.selectedSessionId.value)
        // A second link replaces it; the same activity instance handles both.
        activity.newIntent(view("tether://session/$OTHER_LISTED"))
        idle()
        assertEquals(OTHER_LISTED, activity.vm.selectedSessionId.value)
        // Relaunching from the launcher selects nothing new.
        activity.newIntent(launcher())
        idle()
        assertEquals(OTHER_LISTED, activity.vm.selectedSessionId.value)
    }

    @Test
    fun aLinkToAnotherHostNeverSwitchesServers() {
        install(NavTestClient())
        val activity = launch(launcher())
        activity.newIntent(view("https://evil.example/?session=$LISTED"))
        idle()
        assertNoLinkSelection(activity.vm)
        assertFalse(LISTED in client.attached)
        assertEquals(DeepLinkNavigator.OTHER_SERVER, activity.vm.activeToast.value)
        assertEquals(PAIRED, client.serverUrl.value)
    }

    @Test
    fun aStaleIdLandsOnTheCurrentScreenWithAToast() {
        install(NavTestClient())
        val activity = launch(view("tether://session/gone-1"))
        assertNoLinkSelection(activity.vm, linked = "gone-1")
        assertEquals(DeepLinkNavigator.SESSION_GONE, activity.vm.activeToast.value)
        // A malformed one too, without a crash.
        activity.newIntent(view("tether://session/..%2F..%2Fetc"))
        idle()
        assertEquals(DeepLinkNavigator.CANNOT_OPEN, activity.vm.activeToast.value)
    }

    @Test
    fun signedOutTheLinkWaitsForSignInToTheSameServer() {
        install(NavTestClient(configured = false, connection = ConnectionState.AuthRequired))
        val activity = launch(view("tether://session/$LISTED"))
        assertNull(activity.vm.selectedSessionId.value)
        assertTrue(client.attached.isEmpty())
        // The login screen is untouched: no sign-in attempt, the stored server unchanged.
        assertEquals(PAIRED, client.serverUrl.value)
        client.signIn(PAIRED)
        idle()
        assertEquals(LISTED, activity.vm.selectedSessionId.value)
    }

    @Test
    fun anExpiredSignInHoldsTheLinkUntilTheServerAcceptsItAgain() {
        // The credential is still stored (configured) but the server ended the sign-in: the login
        // screen shows, so the link must wait even though the session is listed.
        install(NavTestClient(configured = true, connection = ConnectionState.AuthRequired))
        val activity = launch(view("tether://session/$LISTED"))
        assertNull(activity.vm.selectedSessionId.value)
        assertFalse(LISTED in client.attached)
        client.connectionFlow.value = ConnectionState.Connecting
        client.connectionFlow.value = ConnectionState.Connected
        idle()
        assertEquals(LISTED, activity.vm.selectedSessionId.value)
    }

    @Test
    fun signedOutASignInToAnotherServerDoesNotOpenTheLink() {
        install(NavTestClient(configured = false, connection = ConnectionState.AuthRequired))
        val activity = launch(view("tether://session/$LISTED"))
        client.signIn("https://other.example")
        idle()
        assertNoLinkSelection(activity.vm)
        assertFalse(LISTED in client.attached)
        assertEquals(DeepLinkNavigator.OTHER_SERVER, activity.vm.activeToast.value)
    }

    @Test
    fun signedOutAnHttpLinkForAnotherHostIsDroppedAndPrefillsNothing() {
        install(NavTestClient(configured = false, connection = ConnectionState.AuthRequired))
        val activity = launch(view("https://evil.example/?session=$LISTED"))
        assertEquals(PAIRED, client.serverUrl.value)
        client.signIn(PAIRED)
        idle()
        assertNoLinkSelection(activity.vm)
        assertFalse(LISTED in client.attached)
    }

    @Test
    fun aLinkArrivingBeforeTheStoredSettingsLoadIsNotMistakenForSignedOut() {
        install(NavTestClient(configured = false, connection = ConnectionState.Disconnected, serverUrl = null, loaded = false))
        val activity = launch(view("tether://session/$LISTED"))
        assertNull(activity.vm.selectedSessionId.value)
        // The store is read: the app was signed in to the paired server all along.
        client.serverUrlFlow.value = PAIRED
        client.configuredFlow.value = true
        client.loadedFlow.value = true
        idle()
        assertEquals(LISTED, activity.vm.selectedSessionId.value)
        assertNull(activity.vm.activeToast.value)
    }

    @Test
    fun aNotificationTapOpensTheAppAndSelectsNoSession() {
        install(NavTestClient())
        val tap = PushDeepLink.intentFor(ApplicationProvider.getApplicationContext(), PushMessage.Visible(PushKind.Approval, "T", "B", "tether-approval-x"))
            .putExtra("tether.push.sessionId", LISTED)
            .setData(Uri.parse("tether://session/$LISTED"))
        val activity = launch(tap)
        assertNoLinkSelection(activity.vm)
        assertFalse(LISTED in client.attached)
        activity.newIntent(Intent(PushDeepLink.ACTION_SDK_CLICK).putExtra("kind", "question").putExtra("url", "/?session=$LISTED"))
        idle()
        assertNoLinkSelection(activity.vm)
        assertNull(activity.vm.activeToast.value)
    }

    @Test
    fun aRecreationDoesNotReplayTheLink() {
        install(NavTestClient())
        val activity = launch(view("tether://session/$LISTED"))
        activity.vm.selectSession(OTHER_LISTED)
        idle()
        activity.recreate()
        idle()
        assertEquals(OTHER_LISTED, activity.vm.selectedSessionId.value)
    }

    @Test
    fun aLinkWaitingBehindSignInSurvivesRotation() {
        install(NavTestClient(configured = false, connection = ConnectionState.AuthRequired))
        val activity = launch(view("tether://session/$LISTED"))
        activity.recreate()
        idle()
        client.signIn(PAIRED)
        idle()
        assertEquals(LISTED, activity.vm.selectedSessionId.value)
    }

    @Test
    fun linksNeverReachTheLog() {
        install(NavTestClient())
        val activity = launch(view("tether://session/$LISTED"))
        for (link in listOf("https://evil.example/?session=secret-id-1", "tether://session/secret-id-2", "javascript:alert('secret-3')")) {
            activity.newIntent(view(link))
            idle()
        }
        val logged = ShadowLog.getLogs().filter { it.type >= Log.VERBOSE }.joinToString("\n") { "${it.tag}: ${it.msg}" }
        for (needle in listOf(LISTED, "secret", "evil.example", "tether://")) {
            assertFalse("log mentions $needle:\n$logged", needle in logged)
        }
    }
}
