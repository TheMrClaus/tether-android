package com.tether.app.ui.settings

import android.content.ClipboardManager
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.AppSignIn
import com.tether.app.client.DeviceRevoked
import com.tether.app.client.DevicesList
import com.tether.app.client.SignInHandle
import com.tether.app.client.withHandle
import com.tether.app.client.PasskeyPolicySource
import com.tether.app.client.PasswordPolicy
import com.tether.app.client.SecurityResult
import com.tether.app.client.SessionsRevoked
import com.tether.app.ui.settings.DevicesFixtures.APP
import com.tether.app.ui.settings.DevicesFixtures.BROWSER
import com.tether.app.ui.settings.DevicesFixtures.LAPTOP_KEY
import com.tether.app.ui.settings.DevicesFixtures.NOW
import com.tether.app.ui.settings.DevicesFixtures.ORIGIN
import com.tether.app.ui.settings.DevicesFixtures.OTHER_ORIGIN
import com.tether.app.ui.settings.DevicesFixtures.PHONE
import com.tether.app.ui.settings.DevicesFixtures.SENTINEL
import com.tether.app.ui.settings.DevicesFixtures.TABLET
import com.tether.app.ui.settings.DevicesFixtures.ok
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * T10.4: Settings > Devices through the semantics tree, against a source whose every call waits for
 * the test's answer: the web's order and words; the opening reads (three, once); the web's
 * confirmations (revoke a device, remove a passkey, sign out everywhere else) plus the app's two
 * that sign this phone out (revoking it, revoking every device), each saying so; the owner-grade
 * 403 shown once and never retried by itself; every answer bound to its server; and the pairing
 * code under the secret rules: masked by default, the sentinel in no semantics node, log line,
 * preference, saved-state value or clipboard while masked, copied only onto a sensitive clip that
 * is cleared again, gone on close.
 *
 * The v2 rule (ta-b72): the controller resumes on the composition's dispatcher (the test's here),
 * and every read after a tap waits on the screen or the source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DevicesBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Devices)
    private var origin by mutableStateOf<String?>(ORIGIN)
    private var shown by mutableStateOf(true)
    private val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
    private val source = RecordingSecuritySource()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clipboard = AndroidPairingClipboard(context)
    private val systemClipboard = context.getSystemService(ClipboardManager::class.java)

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry, LocalConfirmArmMs provides 0L) {
                if (shown) {
                    val controller = rememberDevicesController(source, origin, clipboard = clipboard, now = { NOW })
                    SettingsUnderTest(store.prefs, state, devices = DevicesBinding(controller, now = { NOW }))
                }
            }
        }
        compose.waitUntil(5_000) { state.draft != null }
        waitFor(DevicesTags.Paired)
    }

    /** Shown, its three opening reads answered. */
    private fun opened(signIn: AppSignIn = AppSignIn.SessionCookie, devices: List<com.tether.app.client.PairedDevice> = DevicesFixtures.DEVICES, sessions: List<com.tether.app.client.SecuritySession> = DevicesFixtures.SESSIONS) {
        show()
        waitCalls(3)
        source.answerReads(signIn, devices = devices, sessions = sessions)
        waitFor(DevicesTags.Pair)
        compose.waitUntil(5_000) { exists(DevicesTags.SignOutOthers) && !exists(DevicesTags.DevicesChecking) && !exists(DevicesTags.PasskeysChecking) }
    }

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String) = compose.waitUntil(5_000) { exists(t) }
    private fun waitGone(t: String) = compose.waitUntil(5_000) { !exists(t) }
    private fun waitText(text: String) = compose.waitUntil(5_000) { texts().any { it.contains(text) } }
    private fun waitCalls(n: Int) {
        try {
            compose.waitUntil(5_000) { source.calls.size == n }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("expected $n calls, saw ${source.calls}", e)
        }
    }
    private fun enabled(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
        ?.config?.contains(SemanticsProperties.Disabled) == false

    private fun tap(t: String) {
        compose.waitUntil(5_000) { enabled(t) }
        tag(t).performScrollTo().performClick()
    }

    /** A key in the confirmation (its own window: no scroll). */
    private fun tapInDialog(t: String) {
        waitFor(t)
        tag(t).performClick()
    }

    private fun allSemantics(): String {
        val out = StringBuilder()
        fun walk(node: SemanticsNode) {
            for ((key, value) in node.config) out.append(key.name).append('=').append(value).append('\n')
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out.toString()
    }

    private fun texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private fun description(t: String): String? = tag(t).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    private fun savedState(): String = registry.performSave().toString() +
        with(SettingsDialogState.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(state) }.toString()

    private fun clipText(): String? = systemClipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    private fun assertNowhere(leak: String, clipboardToo: Boolean = true) {
        assertFalse("semantics holds the code", allSemantics().contains(leak))
        assertFalse("saved state holds the code", savedState().contains(leak))
        assertFalse("a log line holds the code", ShadowLog.getLogs().any { "${it.tag} ${it.msg} ${it.throwable}".contains(leak) })
        assertFalse("the preference store holds the code", store.stored().toString().contains(leak))
        if (clipboardToo) assertFalse("the clipboard holds the code", clipText()?.contains(leak) == true)
    }

    private fun mint(code: String = SENTINEL) {
        val before = source.calls.size
        tap(DevicesTags.Pair)
        waitCalls(before + 1)
        source.answer("pair", ok(DevicesFixtures.code(code)))
        waitFor(DevicesTags.CodeCard)
    }

    // ---- order, words, opening reads ----------------------------------------------------------

    @Test fun theTabIsDrawnInTheWebsOrderAndReadsOnceOnOpening() {
        opened()
        assertEquals(listOf("devices", "passkeys", "sessions"), source.names().sorted())
        assertTrue(source.calls.all { it.origin == ORIGIN })
        val all = texts()
        fun at(s: String) = all.indexOfFirst { it == s }.also { assertTrue("missing: $s", it >= 0) }
        val order = listOf("Notifications", "Passkeys", "Laptop", "YubiKey", "Password sign-in", "Signed-in sessions", "Password", "Android app (passkey)", "Paired devices", "Pixel 8", "Galaxy Tab", DevicesCopy.PAIR).map(::at)
        assertEquals(order.sorted(), order)
        assertTrue(all.contains(DevicesCopy.SYNCED))
        assertTrue(all.any { it.startsWith("added ") && it.endsWith(" · last used 2h") })
        assertTrue(all.any { it.endsWith("never used") })
        // The current session carries "This device" and has no Sign out; the other does.
        assertTrue(exists(DevicesTags.sessionCurrent(APP.id)))
        assertFalse(exists(DevicesTags.signOut(APP.id)))
        assertTrue(exists(DevicesTags.signOut(BROWSER.id)))
        // Named for what they act on (the web's aria-labels).
        assertEquals("Revoke Pixel 8", description(DevicesTags.revoke(PHONE.id)))
        assertEquals("Rename Laptop", description(DevicesTags.rename(LAPTOP_KEY.id)))
        assertEquals("Remove Laptop", description(DevicesTags.remove(LAPTOP_KEY.id)))
        // One unclaimed code from earlier.
        assertTrue(all.contains(DevicesCopy.pairHint(1)))
        // A cookie sign-in: no device is this phone.
        assertFalse(exists(DevicesTags.deviceSelf(PHONE.id)))
        // T10.5: no passkey prompt on this controller (PasskeyAuthenticator.None): Add a passkey is
        // drawn off, with why (DevicesPasskeyBehaviourTest covers the ceremony).
        assertFalse(enabled(DevicesTags.AddPasskey))
        assertTrue(all.contains(DevicesCopy.PASSKEY_UNAVAILABLE))
        // A tab change and back reads nothing more.
        state.tab = SettingsTab.General
        compose.waitForIdle()
        state.tab = SettingsTab.Devices
        waitFor(DevicesTags.Pair)
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        assertEquals(3, source.calls.size)
    }

    @Test fun serverTextIsDrawnSafely() {
        show()
        waitCalls(3)
        val hostile = com.tether.app.client.PairedDevice("d1", "Pix‮el​ 8\nPhone", NOW - 60_000, NOW - 60_000)
        source.answerReads(devices = listOf(hostile))
        waitFor(DevicesTags.device("d1"))
        val drawn = texts().joinToString("\n")
        assertTrue(drawn.contains("Pixel 8 Phone"))
        assertFalse(drawn.contains("‮") || drawn.contains("​"))
        assertEquals("Revoke Pixel 8 Phone", description(DevicesTags.revoke("d1")))
    }

    @Test fun signedOutNothingIsAskedOrDrawnButTheHeadings() {
        origin = null
        show()
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        assertTrue(source.calls.isEmpty())
        assertFalse(exists(DevicesTags.Pair))
        assertTrue(texts().contains("Paired devices"))
    }

    // ---- the owner-grade refusal (tether #236 not deployed) ------------------------------------

    @Test fun anOwnerSignInRefusalIsExplainedOnceAndNeverRetriedByItself() {
        show()
        waitCalls(3)
        for (name in listOf("devices", "passkeys", "sessions")) source.answer(name, SecurityResult.OwnerSignInNeeded(ORIGIN))
        waitFor(DevicesTags.ownerNote(DevicesArea.Devices))
        waitFor(DevicesTags.ownerNote(DevicesArea.Security))
        assertTrue(texts().contains(DevicesCopy.OWNER_NEEDED))
        // The screen stays usable: Notifications is there, the write keys are off (they would be refused).
        assertTrue(texts().contains("Notifications"))
        assertFalse(enabled(DevicesTags.Pair))
        assertFalse(enabled(DevicesTags.SignOutOthers))
        // Nothing is asked again by itself, however long it waits.
        compose.mainClock.advanceTimeBy(120_000)
        compose.waitForIdle()
        assertEquals(3, source.calls.size)
        // Check again asks once more, and a deployed server's answer clears the note.
        tap(DevicesTags.checkAgain(DevicesArea.Devices))
        waitCalls(6)
        source.answerReads()
        waitGone(DevicesTags.ownerNote(DevicesArea.Devices))
        compose.waitUntil(5_000) { enabled(DevicesTags.Pair) }
    }

    @Test fun aRefusedWriteShowsTheServersWordsCleaned() {
        opened()
        tap(DevicesTags.PasswordToggle)
        waitCalls(4)
        assertEquals("false", source.calls.last().arg)
        source.answer("policy", SecurityResult.Refused(409, "Sign in with a passkey first,‮ then turn password sign-in off.", ORIGIN))
        waitText("Sign in with a passkey first, then turn password sign-in off.")
        assertFalse(texts().any { it.contains("‮") })
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        assertEquals("never retried", 4, source.calls.size)
    }

    // ---- the pairing code: secret rules -------------------------------------------------------

    @Test fun aMintedCodeIsMaskedAndInNoSemanticsLogPreferenceSavedStateOrClipboard() {
        ShadowLog.clear()
        opened()
        mint()
        // Pair a device has no confirmation (the web), and goes once.
        assertEquals(1, source.writes.size)
        assertEquals(DevicesCopy.CODE_HIDDEN, description(DevicesTags.CodeMasked))
        assertFalse(exists(DevicesTags.CodeRevealed))
        assertNowhere(SENTINEL)
        assertTrue(texts().any { it.startsWith("Expires in 4m 59s") })
        // Its re-read of the list followed; the shown code is matched out of the unclaimed count.
        waitCalls(5)
        assertEquals("devices", source.calls.last().name)
    }

    @Test fun revealDrawsTheCodeReadLetterByLetterAndHideMasksItAgain() {
        opened()
        mint()
        tap(DevicesTags.CodeReveal)
        waitFor(DevicesTags.CodeRevealed)
        // Control: once revealed the probe finds it (so its absence elsewhere means something).
        assertEquals(DevicesCopy.codeSpoken(SENTINEL), description(DevicesTags.CodeRevealed))
        assertTrue(allSemantics().contains(SENTINEL.toCharArray().joinToString(" ")))
        assertFalse(savedState().contains(SENTINEL))
        tap(DevicesTags.CodeReveal)
        waitFor(DevicesTags.CodeMasked)
        assertNowhere(SENTINEL)
    }

    @Test fun copyPutsASensitiveClipThatIsClearedShortlyAfter() {
        opened()
        mint()
        tap(DevicesTags.CodeCopy)
        waitText(DevicesCopy.COPIED)
        assertEquals(SENTINEL, clipText())
        val description = systemClipboard.primaryClipDescription!!
        assertEquals(PairingClipboard.CLIP_LABEL, description.label.toString())
        assertTrue("marked sensitive", description.extras!!.getBoolean(PairingClipboard.EXTRA_IS_SENSITIVE))
        // Still masked on screen: copying is not revealing.
        assertFalse(allSemantics().contains(SENTINEL))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PairingClipboard.CLEAR_AFTER_MS + 1_000))
        assertFalse("cleared after the delay", clipText()?.contains(SENTINEL) == true)
    }

    @Test fun somethingCopiedSinceIsLeftAlone() {
        opened()
        mint()
        tap(DevicesTags.CodeCopy)
        waitText(DevicesCopy.COPIED)
        systemClipboard.setPrimaryClip(android.content.ClipData.newPlainText("note", "the operator's own text"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PairingClipboard.CLEAR_AFTER_MS + 1_000))
        assertEquals("the operator's own text", clipText())
    }

    @Test fun closingSettingsDropsTheCodeAndItsCopy() {
        opened()
        mint()
        tap(DevicesTags.CodeCopy)
        waitText(DevicesCopy.COPIED)
        shown = false
        compose.waitForIdle()
        assertFalse("the copy is cleared on close", clipText()?.contains(SENTINEL) == true)
        shown = true
        waitFor(DevicesTags.Paired)
        waitCalls(8)
        source.answerReads()
        waitFor(DevicesTags.Pair)
        assertFalse(exists(DevicesTags.CodeCard))
        assertNowhere(SENTINEL)
    }

    @Test fun aTabChangeKeepsTheCodeButMasksIt() {
        opened()
        mint()
        tap(DevicesTags.CodeReveal)
        waitFor(DevicesTags.CodeRevealed)
        state.tab = SettingsTab.Advanced
        compose.waitForIdle()
        state.tab = SettingsTab.Devices
        waitFor(DevicesTags.CodeMasked)
        assertFalse(allSemantics().contains(SENTINEL))
    }

    /** The app going to the background (ON_STOP) masks the code again: no Recents snapshot or glance holds it. Kept for when the operator comes back. */
    @Test fun stoppingTheAppMasksTheCode() {
        val owner = TestOwner()
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner, LocalConfirmArmMs provides 0L) {
                val controller = rememberDevicesController(source, ORIGIN, clipboard = clipboard, now = { NOW })
                SettingsUnderTest(store.prefs, state, devices = DevicesBinding(controller, now = { NOW }))
            }
        }
        waitCalls(3)
        source.answerReads()
        mint()
        tap(DevicesTags.CodeReveal)
        waitFor(DevicesTags.CodeRevealed)
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        waitFor(DevicesTags.CodeMasked)
        assertFalse(allSemantics().contains(SENTINEL))
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(DevicesCopy.CODE_HIDDEN, description(DevicesTags.CodeMasked))
        assertTrue(exists(DevicesTags.CodeCopy))
    }

    @Test fun anExpiredCodeIsDroppedAndItsCopyCleared() {
        // A clock the test moves: the code expires in 5 s.
        var clock = NOW
        compose.setContent {
            CompositionLocalProvider(LocalConfirmArmMs provides 0L) {
                val controller = rememberDevicesController(source, ORIGIN, clipboard = clipboard, now = { clock })
                SettingsUnderTest(store.prefs, state, devices = DevicesBinding(controller, now = { clock }))
            }
        }
        waitCalls(3)
        source.answerReads()
        tap(DevicesTags.Pair)
        waitCalls(4)
        source.answer("pair", ok(DevicesFixtures.code(expiresAt = NOW + 5_000)))
        waitFor(DevicesTags.CodeCopy)
        tag(DevicesTags.CodeCopy).performScrollTo().performClick()
        compose.waitUntil(5_000) { clipText() == SENTINEL }
        clock = NOW + 6_000
        compose.mainClock.advanceTimeBy(6_000)
        waitText(DevicesCopy.EXPIRED)
        assertFalse(exists(DevicesTags.CodeReveal))
        assertFalse(exists(DevicesTags.CodeCopy))
        assertFalse(clipText()?.contains(SENTINEL) == true)
    }

    /** r2 (security F7): the code expires in the controller, so its plaintext and copy go even while another tab is shown. */
    @Test fun aCodeExpiresWhileItsCardIsOffScreen() {
        var clock = NOW
        compose.setContent {
            CompositionLocalProvider(LocalConfirmArmMs provides 0L) {
                val controller = rememberDevicesController(source, ORIGIN, clipboard = clipboard, now = { clock })
                SettingsUnderTest(store.prefs, state, devices = DevicesBinding(controller, now = { clock }))
            }
        }
        waitCalls(3)
        source.answerReads()
        tap(DevicesTags.Pair)
        waitCalls(4)
        source.answer("pair", ok(DevicesFixtures.code(expiresAt = NOW + 5_000)))
        waitFor(DevicesTags.CodeCopy)
        tag(DevicesTags.CodeCopy).performScrollTo().performClick()
        compose.waitUntil(5_000) { clipText() == SENTINEL }
        state.tab = SettingsTab.General
        waitGone(DevicesTags.CodeCard)
        clock = NOW + 20_000
        compose.mainClock.advanceTimeBy(20_000)
        compose.waitUntil(5_000) { clipText()?.contains(SENTINEL) != true }
        state.tab = SettingsTab.Devices
        waitText(DevicesCopy.EXPIRED)
        assertFalse(exists(DevicesTags.CodeCopy))
    }

    @Test fun aDoubleTapOnPairMintsOnce() {
        opened()
        tap(DevicesTags.Pair)
        waitText(DevicesCopy.PAIRING)
        tag(DevicesTags.Pair).performClick()
        compose.waitForIdle()
        assertEquals(1, source.writes.size)
    }

    /** Two writes asked in one frame (before the key can draw itself off): the controller starts one, per area. */
    @Test fun twoWritesInOneFrameStartOnePerArea() {
        var captured: DevicesController? = null
        compose.setContent {
            CompositionLocalProvider(LocalConfirmArmMs provides 0L) {
                val controller = rememberDevicesController(source, ORIGIN, clipboard = clipboard, now = { NOW })
                captured = controller
                SettingsUnderTest(store.prefs, state, devices = DevicesBinding(controller, now = { NOW }))
            }
        }
        waitCalls(3)
        source.answerReads()
        waitFor(DevicesTags.Pair)
        compose.runOnIdle {
            val c = captured!!
            assertTrue(c.pair())
            assertFalse(c.pair())
            assertFalse(c.revoke(TABLET, SelfMatch.No))
            assertTrue(c.setPasswordLogin(false))
            assertFalse(c.setPasswordLogin(true))
            assertFalse(c.revokeOtherSessions())
        }
        waitCalls(5)
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertEquals(listOf("pair", "policy"), source.writes.map { it.name }.sorted())
    }

    // ---- confirmations --------------------------------------------------------------------------

    @Test fun revokingAnotherDeviceAsksFirstAsOnTheWebAndCancelSendsNothing() {
        opened(AppSignIn.SessionCookie)
        tap(DevicesTags.revoke(TABLET.id))
        waitFor(DevicesTags.ConfirmSheet)
        assertTrue(texts().contains(DevicesCopy.REVOKE_TITLE))
        assertTrue(texts().contains(DevicesCopy.revokeBody("Galaxy Tab")))
        // A cookie sign-in: revoking a device never signs this phone out, so the dialog does not say it does.
        assertFalse(texts().any { it == DevicesCopy.SELF_SIGNS_OUT || it == DevicesCopy.MAYBE_SELF })
        tapInDialog(DevicesTags.ConfirmCancel)
        waitGone(DevicesTags.ConfirmSheet)
        assertTrue(source.writes.isEmpty())
        tap(DevicesTags.revoke(TABLET.id))
        tapInDialog(DevicesTags.ConfirmGo)
        waitCalls(4)
        assertEquals("revokeDevice", source.calls.last().name)
        assertEquals(TABLET.id, source.calls.last().arg)
        source.answer("revokeDevice", ok(DeviceRevoked(1)))
        waitText(DevicesCopy.revoked(1))
        // The web re-lists after a revoke.
        waitCalls(5)
        assertEquals("devices", source.calls.last().name)
    }

    @Test fun revokingThisPhoneSaysItSignsOutAndTheSignOutIsHandledCleanly() {
        // Signed in with a device token, the only paired device: it is this phone.
        opened(AppSignIn.DeviceToken, devices = listOf(PHONE))
        waitFor(DevicesTags.deviceSelf(PHONE.id))
        tap(DevicesTags.revoke(PHONE.id))
        waitFor(DevicesTags.ConfirmSheet)
        assertTrue(texts().contains(DevicesCopy.SELF_SIGNS_OUT))
        tapInDialog(DevicesTags.ConfirmGo)
        waitCalls(4)
        val handle = SignInHandle(Any())
        source.answer("revokeDevice", ok(DeviceRevoked(1), signIn = AppSignIn.DeviceToken).withHandle(handle))
        waitText(DevicesCopy.SIGNED_OUT_HERE)
        // r2 (security F2): the client is told at once that this credential is dead.
        assertEquals(listOf(handle), source.rejected.toList())
        // No re-read of a list it may no longer read, no write key left.
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        assertEquals(4, source.calls.size)
        assertFalse(exists(DevicesTags.Pair))
        // The client then signs out (the server closed the socket, 4001): the panel holds nothing, asks nothing.
        origin = null
        waitGone(DevicesTags.line(DevicesArea.Devices))
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        assertEquals(4, source.calls.size)
    }

    @Test fun amongSeveralDevicesATokenSignInIsToldItMayBeThisPhone() {
        opened(AppSignIn.DeviceToken)
        assertFalse(exists(DevicesTags.deviceSelf(PHONE.id)))
        tap(DevicesTags.revoke(PHONE.id))
        waitFor(DevicesTags.ConfirmSheet)
        assertTrue(texts().contains(DevicesCopy.MAYBE_SELF))
    }

    /** Settings closed after a cancelled confirmation and opened again: nothing pending survives, the panel reads afresh, nothing is sent. */
    @Test fun reopeningAfterACancelledConfirmationReadsAfreshAndSendsNothing() {
        opened(AppSignIn.DeviceToken)
        tap(DevicesTags.revoke(PHONE.id))
        waitFor(DevicesTags.ConfirmSheet)
        tapInDialog(DevicesTags.ConfirmCancel)
        waitGone(DevicesTags.ConfirmSheet)
        shown = false
        compose.waitForIdle()
        shown = true
        // Wait on the screen first (a frame must run for the reopened panel to compose and read).
        waitFor(DevicesTags.Paired)
        waitCalls(6)
        assertEquals(listOf("devices", "passkeys", "sessions"), source.calls.drop(3).map { it.name }.sorted())
        assertFalse(exists(DevicesTags.ConfirmSheet))
        assertTrue(source.writes.isEmpty())
    }

    /** r2 (security F2): after revoking a device that may have been this phone, Tether's own 401 on the re-read means it was. */
    @Test fun aFourOhOneRightAfterRevokingWhatMayBeThisPhoneSignsOutAndDropsTheCredential() {
        opened(AppSignIn.DeviceToken)
        tap(DevicesTags.revoke(PHONE.id))
        tapInDialog(DevicesTags.ConfirmGo)
        waitCalls(4)
        source.answer("revokeDevice", ok(DeviceRevoked(1), signIn = AppSignIn.DeviceToken))
        waitCalls(5)
        assertEquals("devices", source.calls.last().name)
        assertTrue("not yet: it may have been another device", source.rejected.isEmpty())
        val handle = SignInHandle(Any())
        source.answer("devices", SecurityResult.SignedOut(ORIGIN).withHandle(handle))
        waitText(DevicesCopy.SIGNED_OUT_HERE)
        assertEquals(listOf(handle), source.rejected.toList())
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        assertEquals(5, source.calls.size)
    }

    @Test fun aRevokedDeviceThatWasNotThisPhoneKeepsTheSignIn() {
        opened(AppSignIn.DeviceToken)
        tap(DevicesTags.revoke(TABLET.id))
        tapInDialog(DevicesTags.ConfirmGo)
        waitCalls(4)
        source.answer("revokeDevice", ok(DeviceRevoked(1), signIn = AppSignIn.DeviceToken).withHandle(SignInHandle(Any())))
        waitCalls(5)
        source.answer("devices", SecurityResult.Ok(DevicesList(listOf(PHONE), emptyList()), ORIGIN, AppSignIn.DeviceToken).withHandle(SignInHandle(Any())))
        waitGone(DevicesTags.device(TABLET.id))
        assertTrue(source.rejected.isEmpty())
        assertTrue(exists(DevicesTags.Pair))
    }

    /** tether #240 (unmerged): when the server marks the caller's own entry, it alone is "This device", exactly. */
    @Test fun theServersCurrentFlagMarksThisDeviceExactly() {
        opened(AppSignIn.DeviceToken, devices = listOf(PHONE.copy(current = true), TABLET))
        waitFor(DevicesTags.deviceSelf(PHONE.id))
        assertFalse(exists(DevicesTags.deviceSelf(TABLET.id)))
        tap(DevicesTags.revoke(TABLET.id))
        waitFor(DevicesTags.ConfirmSheet)
        assertFalse("the other device is known not to be this phone", texts().any { it == DevicesCopy.SELF_SIGNS_OUT || it == DevicesCopy.MAYBE_SELF })
        tapInDialog(DevicesTags.ConfirmCancel)
        waitGone(DevicesTags.ConfirmSheet)
        tap(DevicesTags.revoke(PHONE.id))
        waitFor(DevicesTags.ConfirmSheet)
        assertTrue(texts().contains(DevicesCopy.SELF_SIGNS_OUT))
    }

    // ---- ids the server sent that no route may name (r2, verifier F1/F2) -------------------------

    @Test fun aRowWhoseIdCannotBeNamedIsListedButNothingCanBeSentForIt() {
        var captured: DevicesController? = null
        compose.setContent {
            CompositionLocalProvider(LocalConfirmArmMs provides 0L) {
                val controller = rememberDevicesController(source, ORIGIN, clipboard = clipboard, now = { NOW })
                captured = controller
                SettingsUnderTest(store.prefs, state, devices = DevicesBinding(controller, now = { NOW }))
            }
        }
        waitCalls(3)
        val long = PHONE.copy(id = "x".repeat(1400), label = "Too long", actionable = false)
        val sibling = TABLET.copy(id = "pairings", label = "Sibling", actionable = false)
        val key = LAPTOP_KEY.copy(id = "policy", actionable = false)
        val session = BROWSER.copy(id = "sessions", actionable = false)
        source.answerReads(devices = listOf(long, sibling), passkeys = DevicesFixtures.PASSKEYS.copy(passkeys = listOf(key)), sessions = listOf(session, APP))
        waitFor(DevicesTags.device(long.id))
        assertTrue(exists(DevicesTags.device(sibling.id)))
        assertFalse(enabled(DevicesTags.revoke(long.id)))
        assertFalse(enabled(DevicesTags.revoke(sibling.id)))
        assertFalse(enabled(DevicesTags.remove(key.id)))
        assertFalse(enabled(DevicesTags.rename(key.id)))
        assertFalse(enabled(DevicesTags.signOut(session.id)))
        compose.runOnIdle {
            val c = captured!!
            assertFalse(c.revoke(long, SelfMatch.No))
            assertFalse(c.revoke(sibling, SelfMatch.No))
            assertFalse(c.removePasskey(key))
            assertFalse(c.renamePasskey(key, "New name"))
            assertFalse(c.revokeSession(session))
        }
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertTrue(source.writes.isEmpty())
    }

    // ---- the owner refusal is per area (r2, verifier F3) ----------------------------------------

    @Test fun ownerNoteSurvivesTheOtherAreasSuccess() {
        show()
        waitCalls(3)
        source.answer("passkeys", SecurityResult.OwnerSignInNeeded(ORIGIN))
        source.answer("sessions", SecurityResult.OwnerSignInNeeded(ORIGIN))
        waitFor(DevicesTags.ownerNote(DevicesArea.Security))
        source.answer("devices", ok(DevicesList(DevicesFixtures.DEVICES, emptyList())))
        waitFor(DevicesTags.device(PHONE.id))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("the Security owner note is still shown", exists(DevicesTags.ownerNote(DevicesArea.Security)))
        assertFalse(exists(DevicesTags.ownerNote(DevicesArea.Devices)))
        assertTrue("devices stay usable", enabled(DevicesTags.Pair))
        assertFalse("security writes stay off", enabled(DevicesTags.SignOutOthers))
    }

    @Test fun theDevicesOwnerNoteSurvivesSecuritysSuccess() {
        show()
        waitCalls(3)
        source.answer("devices", SecurityResult.OwnerSignInNeeded(ORIGIN))
        waitFor(DevicesTags.ownerNote(DevicesArea.Devices))
        source.answer("passkeys", ok(DevicesFixtures.PASSKEYS))
        source.answer("sessions", ok(DevicesFixtures.SESSIONS))
        waitFor(DevicesTags.passkey(LAPTOP_KEY.id))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(exists(DevicesTags.ownerNote(DevicesArea.Devices)))
        assertFalse(exists(DevicesTags.ownerNote(DevicesArea.Security)))
        assertFalse(enabled(DevicesTags.Pair))
    }

    // ---- the password switch from a device token (r2, security F6) -----------------------------

    @Test fun aDeviceTokenSignInIsNotOfferedTurningThePasswordOff() {
        var captured: DevicesController? = null
        compose.setContent {
            CompositionLocalProvider(LocalConfirmArmMs provides 0L) {
                val controller = rememberDevicesController(source, ORIGIN, clipboard = clipboard, now = { NOW })
                captured = controller
                SettingsUnderTest(store.prefs, state, devices = DevicesBinding(controller, now = { NOW }))
            }
        }
        waitCalls(3)
        source.answerReads(AppSignIn.DeviceToken)
        waitFor(DevicesTags.PasswordToggle)
        compose.waitUntil(5_000) { texts().contains(DevicesCopy.PASSWORD_NEEDS_PASSKEY_SIGN_IN) }
        assertFalse(enabled(DevicesTags.PasswordToggle))
        compose.runOnIdle { assertFalse(captured!!.setPasswordLogin(false)) }
        compose.waitForIdle()
        assertTrue(source.writes.isEmpty())
    }

    @Test fun removingAPasskeyAsksAndRenamingDoesNot() {
        opened()
        tap(DevicesTags.remove(LAPTOP_KEY.id))
        waitFor(DevicesTags.ConfirmSheet)
        assertTrue(texts().contains(DevicesCopy.removePasskeyBody("Laptop")))
        tapInDialog(DevicesTags.ConfirmGo)
        waitCalls(4)
        assertEquals("removePasskey", source.calls.last().name)
        source.answer("removePasskey", ok(Unit))
        waitText(DevicesCopy.PASSKEY_REMOVED)
        waitCalls(6)
        source.answerReads()
        tap(DevicesTags.rename(LAPTOP_KEY.id))
        waitFor(DevicesTags.renameField(LAPTOP_KEY.id))
        tag(DevicesTags.renameField(LAPTOP_KEY.id)).performTextReplacement("  Work laptop ")
        tag(DevicesTags.renameField(LAPTOP_KEY.id)).performImeAction()
        waitCalls(7)
        assertEquals("rename", source.calls.last().name)
        assertEquals("${LAPTOP_KEY.id}=Work laptop", source.calls.last().arg)
        assertFalse(exists(DevicesTags.ConfirmSheet))
    }

    @Test fun anUnchangedRenameSendsNothing() {
        opened()
        tap(DevicesTags.rename(LAPTOP_KEY.id))
        waitFor(DevicesTags.renameField(LAPTOP_KEY.id))
        tag(DevicesTags.renameField(LAPTOP_KEY.id)).performImeAction()
        waitGone(DevicesTags.renameField(LAPTOP_KEY.id))
        assertTrue(source.writes.isEmpty())
    }

    @Test fun sessionsSignOutAtOnceButEverywhereElseAsks() {
        opened()
        tap(DevicesTags.signOut(BROWSER.id))
        waitCalls(4)
        assertEquals("revokeSession", source.calls.last().name)
        assertEquals(BROWSER.id, source.calls.last().arg)
        source.answer("revokeSession", ok(Unit))
        waitText(DevicesCopy.SESSION_SIGNED_OUT)
        waitCalls(6)
        source.answerReads()
        tap(DevicesTags.SignOutOthers)
        waitFor(DevicesTags.ConfirmSheet)
        assertTrue(texts().contains(DevicesCopy.SIGN_OUT_OTHERS_BODY))
        tapInDialog(DevicesTags.ConfirmGo)
        waitCalls(7)
        assertEquals("revokeOthers", source.calls.last().name)
        source.answer("revokeOthers", ok(SessionsRevoked(1)))
        waitText(DevicesCopy.othersSignedOut(1))
    }

    @Test fun thePasswordSwitchSendsAtOnceAndShowsTheServersPolicy() {
        opened()
        tap(DevicesTags.PasswordToggle)
        waitCalls(4)
        assertEquals("policy", source.calls.last().name)
        assertFalse(exists(DevicesTags.ConfirmSheet))
        source.answer("policy", ok(PasswordPolicy(false, PasskeyPolicySource.Stored)))
        compose.waitUntil(5_000) { tag(DevicesTags.PasswordToggle).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState) == androidx.compose.ui.state.ToggleableState.Off }
    }

    // ---- bound to the server the screen was drawn from -----------------------------------------

    @Test fun aServerSwitchDropsTheOldServersAnswersAndCodes() {
        opened()
        tap(DevicesTags.Pair)
        waitCalls(4)
        // Signed in to another server while A mints.
        origin = OTHER_ORIGIN
        waitCalls(7)
        assertEquals(listOf(OTHER_ORIGIN, OTHER_ORIGIN, OTHER_ORIGIN), source.calls.drop(4).map { it.origin })
        // A's code lands: not B's to show.
        source.answer("pair", ok(DevicesFixtures.code(), origin = ORIGIN))
        compose.waitForIdle()
        assertFalse(exists(DevicesTags.CodeCard))
        assertNowhere(SENTINEL)
        // An answer tagged with A arriving for B's read is dropped too.
        source.answer("devices", SecurityResult.Ok(DevicesList(listOf(TABLET), emptyList()), ORIGIN, AppSignIn.SessionCookie))
        compose.waitForIdle()
        assertFalse(exists(DevicesTags.device(TABLET.id)))
        assertTrue(exists(DevicesTags.DevicesChecking))
        // B's own answers are shown.
        source.answerReads(origin = OTHER_ORIGIN)
        waitFor(DevicesTags.passkey(LAPTOP_KEY.id))
    }
}

/** T10.4: a rotation drops the code and the reveal, saves nothing of it, and sends no write. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DevicesRotationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun aRotationDropsTheCodeAndSendsNoWrite() {
        val source = RecordingSecuritySource()
        val restoration = StateRestorationTester(compose)
        var saved: String? = null
        restoration.setContent {
            val state = androidx.compose.runtime.saveable.rememberSaveable(saver = SettingsDialogState.Saver) { SettingsDialogState(SettingsTab.Devices) }
            val controller = rememberDevicesController(source, ORIGIN, now = { NOW })
            SettingsUnderTest(store.prefs, state, devices = DevicesBinding(controller, now = { NOW }))
            val registry = LocalSaveableStateRegistry.current
            androidx.compose.runtime.SideEffect { saved = registry?.performSave()?.toString() }
        }
        fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        compose.waitUntil(5_000) { source.calls.size == 3 }
        source.answerReads()
        compose.waitUntil(5_000) { exists(DevicesTags.Pair) }
        compose.onNodeWithTag(DevicesTags.Pair, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5_000) { source.pending("pair") }
        source.answer("pair", ok(DevicesFixtures.code()))
        compose.waitUntil(5_000) { exists(DevicesTags.CodeReveal) }
        compose.onNodeWithTag(DevicesTags.CodeReveal, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5_000) { exists(DevicesTags.CodeRevealed) }
        val writes = source.writes.size
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { exists(DevicesTags.Paired) }
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        assertFalse(exists(DevicesTags.CodeCard))
        assertFalse("the saved state holds the code", saved.orEmpty().contains(SENTINEL))
        assertEquals("no write sent by the rotation", writes, source.writes.size)
        // Only the opening reads again (the panel reads when it opens).
        assertTrue(source.calls.drop(4).all { it.name in RecordingSecuritySource.READS })
    }
}

/** T10.4: a real activity recreation sends no write and starts without the code. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DevicesRecreationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun recreatingTheActivitySendsNoWriteAndDropsTheCode() {
        val source = RecordingSecuritySource()
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            val state = androidx.compose.runtime.saveable.rememberSaveable(saver = SettingsDialogState.Saver) { SettingsDialogState(SettingsTab.Devices) }
            val controller = rememberDevicesController(source, ORIGIN, now = { NOW })
            SettingsUnderTest(store.prefs, state, devices = DevicesBinding(controller, now = { NOW }))
        }
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val first = arrayOfNulls<android.app.Activity>(1)
        val recreated = java.util.concurrent.atomic.AtomicInteger()
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                if (activity !== first[0] && savedInstanceState != null && activity is androidx.activity.ComponentActivity) {
                    recreated.incrementAndGet()
                    activity.setContent(content = content)
                }
            }
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        }
        compose.activityRule.scenario.onActivity { first[0] = it }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            compose.setContent(content)
            fun count(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().size
            compose.waitUntil(5_000) { source.calls.size == 3 }
            source.answerReads()
            compose.waitUntil(5_000) { count(DevicesTags.Pair) == 1 }
            compose.onNodeWithTag(DevicesTags.Pair, useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(5_000) { source.pending("pair") }
            source.answer("pair", ok(DevicesFixtures.code()))
            compose.waitUntil(5_000) { count(DevicesTags.CodeCard) == 1 }
            val writes = source.writes.size
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5_000) { recreated.get() == 1 && count(DevicesTags.Paired) == 1 }
            compose.waitUntil(5_000) { source.calls.count { it.name == "devices" } >= 3 }
            source.answerReads()
            compose.waitUntil(5_000) { count(DevicesTags.Pair) == 1 }
            assertEquals(0, count(DevicesTags.CodeCard))
            compose.waitForIdle()
            assertEquals("no write sent by the recreation", writes, source.writes.size)
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}
