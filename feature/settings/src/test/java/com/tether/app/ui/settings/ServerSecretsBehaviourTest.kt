package com.tether.app.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.tether.app.client.ServerSecretOutcome
import com.tether.app.client.ServerSetting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v144 (ta-j3h9): the write-only Authentication rows and their confirm dialog through the semantics tree. The words, fields,
 * enablement and focus are the web's (components/server-secret-row.tsx 7cc0f38c). Every secret is obviously fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ServerSecretsBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Advanced)
    private var binding by mutableStateOf(ServerSettingsBinding.None)
    private var shown by mutableStateOf(true)
    private val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
    private val current = "SENTINEL-current-aa11"
    private val fresh = "SENTINEL-fresh-bb22"

    private fun show(b: ServerSettingsBinding) {
        binding = b
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                if (shown) SettingsUnderTest(store.prefs, state, serverSettings = binding)
            }
        }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitForIdle()
    }

    private fun binding(
        writer: RecordingWriter = RecordingWriter(),
        view: com.tether.app.client.ServerSettingsView = ServerFixtures.view(),
        secrets: ServerSecretsState = ServerSecretsState(),
        refreshed: () -> Unit = {},
    ) = ServerFixtures.binding(view = view, writer = writer, secrets = secrets, scope = MainScope(), onPasswordChanged = refreshed)

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

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

    private fun allSemantics(): String {
        val out = StringBuilder()
        fun walk(node: SemanticsNode) {
            for ((key, value) in node.config) out.append(key.name).append('=').append(value).append('\n')
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out.toString()
    }

    private fun openPasswordDialog() {
        tag(ServerSecretTags.opener(ServerSetting.Password)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun openTokenDialog() {
        tag(ServerSecretTags.opener(ServerSetting.ProxyToken)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun type(f: SecretField, text: String) {
        tag(ServerSecretTags.field(f)).performTextInput(text)
        compose.waitForIdle()
    }

    private fun fillPassword(c: String = current, n: String = fresh, confirm: String = fresh) {
        type(SecretField.Current, c)
        type(SecretField.New, n)
        type(SecretField.Confirm, confirm)
    }

    // ---- the rows ---------------------------------------------------------------------------------------------

    @Test fun theRowsAreWriteOnlyStatusRowsWithNoFieldAndNoRevealEye() {
        show(binding())
        for (s in listOf(ServerSetting.Password, ServerSetting.ProxyToken)) {
            tag(ServerSettingsTags.row(s)).performScrollTo().assertExists()
            tag(ServerSettingsTags.input(s)).assertDoesNotExist()
        }
        val all = texts()
        assertTrue(all.contains(ServerSecretCopy.SECTION_CAPTION))
        assertTrue(all.contains("Login password for the web console. Changing it signs out every other session."))
        assertTrue(all.contains("SSO-header sign-in is on. Changes apply after a restart."))
        assertEquals(2, all.count { it == "Configured" })
        assertTrue(all.contains("Set new password…") && all.contains("Set new token…") && all.contains("Clear"))
        assertFalse(all.any { it.startsWith("Reveal") || it == "Login password" || it.startsWith("Reverse-proxy pre-auth token") })
        // The captions and tips are the web's: the tip names the immediate effect.
        assertTrue(all.contains(ServerSecretCopy.PASSWORD_TIP))
    }

    /** As the browser does for a `type=password` input: while masked, copy and cut (semantics actions and hardware keys) write and delete nothing, in every dialog field. */
    @Config(shadows = [DeviceKeyCharacterMap::class])
    @Test fun theDialogsMaskedFieldsCannotBeCopiedOrCut() {
        show(binding())
        openPasswordDialog()
        fillPassword()
        val typed = mapOf(SecretField.Current to current, SecretField.New to fresh, SecretField.Confirm to fresh)
        for ((f, text) in typed) {
            val field = tag(ServerSecretTags.field(f))
            assertTrue("masked", field.fetchSemanticsNode().config.contains(SemanticsProperties.Password))
            field.performClick()
            compose.waitForIdle()
            NoCopyProbe.seed()
            for (action in listOf(SemanticsActions.CopyText, SemanticsActions.CutText)) {
                field.performSemanticsAction(SemanticsActions.SetSelection) { it(0, text.length, false) }
                compose.waitForIdle()
                if (field.fetchSemanticsNode().config.contains(action)) field.performSemanticsAction(action)
                compose.waitForIdle()
            }
            for (keys in ClipKeys.entries) {
                field.selectAllAndPress(keys)
                compose.waitForIdle()
            }
            assertEquals("something reached the clipboard from $f", NoCopyProbe.MARKER, NoCopyProbe.clip())
            // The masked EditableText is dots; the test-side InputText property carries the raw typed text.
            assertEquals("a cut deleted the secret in $f", text, field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.InputText)?.text)
        }
    }

    @Test fun noPlaintextCredentialIsEverDrawnEvenFromAnOlderServersFrame() {
        show(binding(view = ServerFixtures.view(ServerFixtures.settingsJson(password = ServerFixtures.SENTINEL, proxyToken = ServerFixtures.TOKEN_SENTINEL))))
        tag(ServerSettingsTags.row(ServerSetting.Password)).performScrollTo()
        val semantics = allSemantics()
        assertFalse(semantics.contains(ServerFixtures.SENTINEL) || semantics.contains(ServerFixtures.TOKEN_SENTINEL))
    }

    @Test fun anEnvForcedCredentialHasALockTheForcedCaptionAndNoKeys() {
        show(binding(view = ServerFixtures.view(envForced = mapOf("password" to true, "proxyToken" to true))))
        tag(ServerSettingsTags.row(ServerSetting.Password)).performScrollTo()
        val all = texts()
        assertTrue(all.contains("Set by TETHER_PASSWORD in the environment — change it there and restart."))
        assertTrue(all.contains("Set by TETHER_PROXY_TOKEN in the environment — change it there and restart."))
        for (s in listOf(ServerSetting.Password, ServerSetting.ProxyToken)) {
            tag(ServerSecretTags.opener(s)).assertDoesNotExist()
        }
        tag(ServerSecretTags.clear(ServerSetting.ProxyToken)).assertDoesNotExist()
    }

    @Test fun withoutAPasswordNeitherRowHasAKeyAndTheTokenKeepsItsOwnStatus() {
        show(binding(view = ServerFixtures.view(secretsSet = mapOf("password" to false, "proxyToken" to true))))
        tag(ServerSettingsTags.row(ServerSetting.Password)).performScrollTo()
        val all = texts()
        assertTrue(all.contains("No login password is set. Set TETHER_PASSWORD and restart to manage it here."))
        assertTrue(all.contains("Not set"))
        assertTrue(all.contains("SSO-header sign-in is on. Changes apply after a restart."))
        for (s in listOf(ServerSetting.Password, ServerSetting.ProxyToken)) tag(ServerSecretTags.opener(s)).assertDoesNotExist()
        tag(ServerSecretTags.clear(ServerSetting.ProxyToken)).assertDoesNotExist()
    }

    @Test fun aTokenThatIsNotSetOffersNoClear() {
        show(binding(view = ServerFixtures.view(secretsSet = mapOf("password" to true, "proxyToken" to false))))
        tag(ServerSecretTags.opener(ServerSetting.ProxyToken)).performScrollTo().assertExists()
        tag(ServerSecretTags.clear(ServerSetting.ProxyToken)).assertDoesNotExist()
        assertTrue(texts().contains("SSO-header sign-in is off."))
    }

    @Test fun theStatusTagIsDrawnUppercaseAndNamedInItsOriginalCase() {
        show(binding())
        val tagNode = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(SettingsTags.StatusTag), useUnmergedTree = true)[0].fetchSemanticsNode()
        assertEquals("Configured", tagNode.config.getOrNull(SemanticsProperties.Text)?.single()?.text)
    }

    // ---- the dialog: opening and fields -----------------------------------------------------------------------

    @Test fun openingThePasswordDialogShowsEmptyFieldsASaveThatIsOffAndFocusOnCurrent() {
        show(binding())
        openPasswordDialog()
        tag(ServerSecretTags.Dialog).assertExists()
        val all = texts()
        for (w in listOf("Set a new password", "Every other signed-in browser and device is signed out when you save. This one stays signed in.",
            "Current password", "New password", "At least 12 characters.", "Confirm new password", "Cancel", "Save password")) {
            assertTrue("$w missing", all.contains(w))
        }
        tag(ServerSecretTags.Error).assertDoesNotExist()
        tag(ServerSecretTags.Submit).assertIsNotEnabled()
        tag(ServerSecretTags.field(SecretField.Current)).assertIsFocused()
    }

    @Test fun eachFieldIsAPasswordFieldNamedByItsLabelWithNoContentDescriptionMaskingTheValue() {
        show(binding())
        openPasswordDialog()
        // The merged tree is what a screen reader walks: one node per field, named by its visible label.
        fun merged(f: SecretField) = compose.onNodeWithTag(ServerSecretTags.field(f)).fetchSemanticsNode().config
        for (f in SecretField.entries) {
            val config = merged(f)
            assertTrue("$f is a password field", config.contains(SemanticsProperties.Password))
            assertEquals("$f carries no contentDescription (it would mask the value)", null, config.getOrNull(SemanticsProperties.ContentDescription))
            assertTrue(config.contains(SemanticsActions.SetText))
        }
        fun label(f: SecretField) = merged(f).getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertTrue(label(SecretField.Current).contains("Current password"))
        assertTrue(label(SecretField.New).contains("New password"))
        assertTrue(label(SecretField.Confirm).contains("Confirm new password"))
    }

    @Test fun theTokenAndClearDialogsHaveTheirOwnWordsAndFields() {
        show(binding())
        openTokenDialog()
        var all = texts()
        assertTrue(all.containsAll(listOf("Set a new proxy token", "New token", "At least 32 characters.", "Save token",
            "Your reverse proxy must send this exact value in its auth header. Takes effect after a restart.")))
        tag(ServerSecretTags.field(SecretField.Confirm)).assertDoesNotExist()
        tag(ServerSecretTags.Cancel).performClick()
        compose.waitForIdle()
        tag(ServerSecretTags.clear(ServerSetting.ProxyToken)).performScrollTo().performClick()
        compose.waitForIdle()
        all = texts()
        assertTrue(all.containsAll(listOf("Clear the proxy token?", "SSO-header sign-in turns off after the next restart. Password and passkey sign-in are unaffected.", "Clear token", "Current password")))
        tag(ServerSecretTags.field(SecretField.New)).assertDoesNotExist()
    }

    // ---- enablement through the UI ------------------------------------------------------------------------------

    @Test fun saveFollowsTheWebsCanSubmitAndTheMismatchNoteAppearsWhileTheConfirmationDiffers() {
        show(binding())
        openPasswordDialog()
        type(SecretField.Current, current)
        type(SecretField.New, "abc")
        tag(ServerSecretTags.Submit).assertIsNotEnabled()
        type(SecretField.Confirm, "ab")
        tag(ServerSecretTags.Mismatch).assertExists()
        assertTrue(texts().contains("Passwords do not match yet."))
        tag(ServerSecretTags.Submit).assertIsNotEnabled()
        type(SecretField.Confirm, "c")
        tag(ServerSecretTags.Mismatch).assertDoesNotExist()
        tag(ServerSecretTags.Submit).assertIsEnabled()
        // The mismatch note is plain text: not a live region (the web's `<p>`).
        type(SecretField.Confirm, "x")
        assertEquals(null, tag(ServerSecretTags.Mismatch).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
    }

    // ---- sending, the answers -----------------------------------------------------------------------------------

    @Test fun savingSendsTheFrameWhileSendingTheFieldsAreReadOnlyAndCancelIsOff() {
        val gate = CompletableDeferred<ServerSecretOutcome>()
        val writer = RecordingWriter(secretReply = { _, _ -> gate.await() })
        show(binding(writer))
        openPasswordDialog()
        fillPassword()
        tag(ServerSecretTags.Submit).performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple(ServerFixtures.json("""{"password":"$fresh"}"""), current, ServerFixtures.ORIGIN)), writer.secrets)
        assertTrue(texts().contains("Saving…"))
        tag(ServerSecretTags.Submit).assertIsNotEnabled()
        tag(ServerSecretTags.Cancel).assertIsNotEnabled()
        // Read-only, not disabled (no dimming, not announced as disabled).
        tag(ServerSecretTags.field(SecretField.New)).assertIsEnabled()
        assertEquals(null, tag(ServerSecretTags.field(SecretField.New)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled))
        gate.complete(ServerSecretOutcome.Refused("weak", "The new password must be at least 12 characters in production (got 21)."))
        compose.waitForIdle()
        assertTrue(texts().contains("The new password must be at least 12 characters in production (got 21)."))
        tag(ServerSecretTags.field(SecretField.New)).assertIsFocused()
        tag(ServerSecretTags.Submit).assertIsEnabled()
    }

    @Test fun aBadCurrentPasswordEmptiesAndFocusesCurrentAndKeepsTheRest() {
        val writer = RecordingWriter(secretReply = { _, _ -> ServerSecretOutcome.Refused("bad_current_password", "That isn't the current password. Nothing was changed.") })
        show(binding(writer))
        openTokenDialog()
        type(SecretField.Current, "wrong")
        type(SecretField.New, fresh)
        tag(ServerSecretTags.Submit).performClick()
        compose.waitForIdle()
        assertTrue(texts().contains("That isn't the current password. Nothing was changed."))
        assertEquals("", tag(ServerSecretTags.field(SecretField.Current)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
        tag(ServerSecretTags.field(SecretField.Current)).assertIsFocused()
        tag(ServerSecretTags.Submit).assertIsNotEnabled()
        // The box is an assertive live region (the web's role="alert").
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Assertive, tag(ServerSecretTags.Error).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test fun noReplyShowsTheClientsCopyAndKeepsTheDialogOpen() {
        val writer = RecordingWriter(secretReply = { _, _ -> ServerSecretOutcome.NoReply })
        show(binding(writer))
        openTokenDialog()
        type(SecretField.Current, current)
        type(SecretField.New, fresh)
        tag(ServerSecretTags.Submit).performClick()
        compose.waitForIdle()
        assertTrue(texts().contains("No reply from the server — the change may not have been saved. Reopen Settings to check."))
        tag(ServerSecretTags.Dialog).assertExists()
    }

    @Test fun successClosesTheDialogShowsTheSavedLineAndRefreshesSessionsForAPassword() {
        var refreshed = 0
        show(binding(refreshed = { refreshed++ }))
        openPasswordDialog()
        fillPassword()
        tag(ServerSecretTags.Submit).performClick()
        compose.waitForIdle()
        tag(ServerSecretTags.Dialog).assertDoesNotExist()
        tag(ServerSecretTags.saved(ServerSetting.Password)).performScrollTo().assertExists()
        assertTrue(texts().contains("Password changed. Every other session was signed out."))
        assertEquals(1, refreshed)
        // A plain status line: a polite live region, and no glyph.
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, tag(ServerSecretTags.saved(ServerSetting.Password)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test fun aSavedTokenSaysSoInTheCaptionAndUnderTheRow() {
        show(binding())
        openTokenDialog()
        type(SecretField.Current, current)
        type(SecretField.New, fresh)
        tag(ServerSecretTags.Submit).performClick()
        compose.waitForIdle()
        val all = texts()
        assertTrue(all.contains("Proxy token saved. It takes effect after a restart."))
        assertTrue(all.contains("Saved — takes effect after a restart."))
        assertFalse(all.contains("SSO-header sign-in is on. Changes apply after a restart."))
    }

    // ---- keyboard, scrim, closing -------------------------------------------------------------------------------

    @Test fun theKeyboardsDoneSubmitsOnlyWhenSaveIsEnabled() {
        val writer = RecordingWriter()
        show(binding(writer))
        openPasswordDialog()
        type(SecretField.Current, current)
        type(SecretField.New, fresh)
        type(SecretField.Confirm, "nope")
        tag(ServerSecretTags.field(SecretField.Confirm)).performImeAction()
        compose.waitForIdle()
        assertEquals("Done does nothing while Save is off", emptyList<Any>(), writer.secrets)
        tag(ServerSecretTags.Dialog).assertExists()
        // Make the confirmation match: replace it.
        tag(ServerSecretTags.field(SecretField.Confirm)).performTextReplacement(fresh)
        compose.waitForIdle()
        tag(ServerSecretTags.field(SecretField.Confirm)).performImeAction()
        compose.waitForIdle()
        assertEquals(1, writer.secrets.size)
    }

    @Test fun aTapOnTheScrimDoesNotCloseTheDialogAndTypedTextSurvives() {
        show(binding())
        openPasswordDialog()
        type(SecretField.Current, current)
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots[roots.fetchSemanticsNodes().size - 1].performTouchInput { click(Offset(2f, 2f)) }
        compose.waitForIdle()
        tag(ServerSecretTags.Dialog).assertExists()
        assertEquals(current.length, tag(ServerSecretTags.field(SecretField.Current)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text?.length)
    }

    @Test fun cancelClosesAndAReopenedDialogStartsEmpty() {
        show(binding())
        openPasswordDialog()
        fillPassword()
        tag(ServerSecretTags.Cancel).performClick()
        compose.waitForIdle()
        tag(ServerSecretTags.Dialog).assertDoesNotExist()
        openPasswordDialog()
        for (f in SecretField.entries) {
            assertEquals("", tag(ServerSecretTags.field(f)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
        }
        tag(ServerSecretTags.Error).assertDoesNotExist()
    }

    @Test fun theSavedLineSurvivesATabSwitch() {
        show(binding())
        openTokenDialog()
        type(SecretField.Current, current)
        type(SecretField.New, fresh)
        tag(ServerSecretTags.Submit).performClick()
        compose.waitForIdle()
        state.tab = SettingsTab.General
        compose.waitForIdle()
        state.tab = SettingsTab.Advanced
        compose.waitForIdle()
        tag(ServerSecretTags.saved(ServerSetting.ProxyToken)).performScrollTo().assertExists()
    }

    @Test fun noTypedValueReachesTheSavedInstanceStateOrTheSemanticsAsText() {
        show(binding())
        openPasswordDialog()
        fillPassword()
        val saved = registry.performSave().toString() +
            with(SettingsDialogState.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(state) }.toString()
        assertFalse(saved.contains(current) || saved.contains(fresh))
        val semantics = allSemantics()
        // `InputText` is Compose's test-side property (the raw typed text); the accessibility bridge exposes a password field's masked text.
        val leaked = semantics.lines().filter { !it.startsWith("InputText=") && (it.contains(current) || it.contains(fresh)) }.map { it.take(60) }
        assertEquals("a typed secret is drawn as dots, never as text", emptyList<String>(), leaked)
        assertNotEquals("", semantics)
    }

    @Test fun theCredentialStateIsDroppedWhenSettingsClosesOrTheServerChanges() {
        var held: ServerSecretsState? = null
        var origin by mutableStateOf<String?>("https://a.test")
        shown = true
        compose.setContent {
            if (shown) held = rememberServerSecretsState(origin)
        }
        compose.waitForIdle()
        val first = held!!
        first.open(SecretMode.TokenSet)
        first.saved[ServerSetting.ProxyToken] = SecretSaved.TokenSaved
        origin = "https://b.test"
        compose.waitForIdle()
        assertTrue("another server starts clean", held !== first && held!!.saved.isEmpty() && held!!.dialog == null)
        val second = held!!
        second.saved[ServerSetting.Password] = SecretSaved.PasswordChanged
        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()
        assertTrue("closing Settings wipes the saved lines", held !== second && held!!.saved.isEmpty())
    }
}

/**
 * A rotation (an activity recreation) keeps the open dialog and what is typed in it, in memory only (ta-coik.20).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ServerSecretsRecreationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun aRotationKeepsTheOpenDialogAndWhatIsTypedAndSavesNothing() {
        val writer = RecordingWriter()
        var registry: SaveableStateRegistry? = null
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            registry = LocalSaveableStateRegistry.current
            val secrets = rememberServerSecretsState(ServerFixtures.ORIGIN)
            val state = androidx.compose.runtime.saveable.rememberSaveable(saver = SettingsDialogState.Saver) { SettingsDialogState(SettingsTab.Advanced) }
            SettingsUnderTest(store.prefs, state, serverSettings = ServerFixtures.binding(writer = writer, secrets = secrets, scope = MainScope()))
        }
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
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
            compose.waitForIdle()
            fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
            tag(ServerSecretTags.opener(ServerSetting.Password)).performScrollTo().performClick()
            compose.waitForIdle()
            tag(ServerSecretTags.field(SecretField.Current)).performTextInput("SENTINEL-current-rot1")
            compose.waitForIdle()
            val saved = compose.runOnIdle { registry?.performSave()?.toString() }.orEmpty()
            assertFalse("the typed secret reached the saved state", saved.contains("SENTINEL-current-rot1"))
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { recreated.get() == 1 && compose.onAllNodes(androidx.compose.ui.test.hasTestTag(ServerSecretTags.Dialog), useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            assertEquals("•".repeat(21), tag(ServerSecretTags.field(SecretField.Current)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
            assertEquals(emptyList<Any>(), writer.secrets)
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}
