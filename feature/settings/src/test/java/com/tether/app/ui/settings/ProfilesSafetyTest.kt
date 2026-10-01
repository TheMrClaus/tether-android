package com.tether.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.ProvidersInFlight
import com.tether.app.client.ProvidersList
import com.tether.app.client.ProvidersRefusal
import com.tether.app.ui.settings.ProfileFixtures.ORIGIN
import com.tether.app.ui.settings.ProfileFixtures.SENTINEL
import com.tether.app.ui.settings.ProfileFixtures.WORK
import com.tether.app.ui.settings.ProfileFixtures.frame
import com.tether.app.ui.settings.ProfileFixtures.gemini
import com.tether.app.ui.settings.ProfileFixtures.profiles
import com.tether.app.ui.settings.ProfileFixtures.zai
import com.tether.app.ui.text.SafeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-q6p r2: the owner's decisions A (risky env keys) and B (Extends) through the semantics tree,
 * and the fixes of the r1 reviews: a reconnect closes a confirmation (security F1), a write while
 * another waits for its broadcast is refused (verifier F1), a refused write is never silent
 * (security F4), the switch sends the value asked for (F8), and an env name collision is refused
 * (F9). Every write is waited for on the writer and asserted as the exact frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProfilesSafetyTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Engines)
    private var providers by mutableStateOf(ProvidersBinding.None)

    private val withPath = gemini(extraEnv = ""","PATH":"/usr/bin"""")

    private fun answering() = RecordingProvidersWriter(newest = { providers.list }) { write ->
        val next = (providers.list?.generation ?: 0) + 1
        providers = providers.copy(list = ProfileFixtures.list(write.profiles, next, providers.list?.epoch ?: 0L))
    }

    private fun recording() = RecordingProvidersWriter(newest = { providers.list })

    private fun show(list: ProvidersList = ProfileFixtures.list(profiles(withPath, WORK, zai())), writer: ProvidersWriter) {
        providers = ProvidersBinding(list, ORIGIN, writer)
        compose.setContent { SettingsUnderTest(store.prefs, state, providers = providers) }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitForIdle()
    }

    private fun broadcast(profiles: String, epoch: Long = providers.list?.epoch ?: 0L) {
        val next = (providers.list?.generation ?: 0) + 1
        providers = providers.copy(list = ProfileFixtures.list(profiles, next, epoch))
        compose.waitForIdle()
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

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

    private fun textOf(t: String): String = tag(t).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun editable(t: String): String = tag(t).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun tap(t: String) {
        tag(t).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun typeAndDone(t: String, value: String) {
        val f = tag(t).performScrollTo()
        f.performTextReplacement(value)
        compose.waitForIdle()
        assertEquals("the edit reached the field", value, editable(t))
        f.performImeAction()
        compose.waitForIdle()
    }

    private fun confirm() {
        compose.mainClock.advanceTimeBy(CONFIRM_ARM_MS + 50)
        compose.waitForIdle()
        tag(ProfileTags.Confirm).performClick()
    }

    private fun waitForWrites(w: RecordingProvidersWriter, n: Int) = compose.waitUntil(5_000) { w.writes.size >= n }

    private fun flushWrites() = androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

    // ---- A: risky env keys ------------------------------------------------------------------------

    @Test fun aRiskyValueIsConfirmedWithItsValuesMaskedAndRevealable() {
        val w = answering()
        show(writer = w)
        tap(ProfileTags.envReveal("gemini", "PATH"))
        typeAndDone(ProfileTags.envInput("gemini", "PATH"), "/opt/bin")
        assertEquals("Done only asks", emptyList<Any>(), w.writes)
        tag(ProfileTags.ConfirmSheet).assertExists()
        assertTrue(texts().contains("Change what Gemini CLI runs?"))
        assertEquals("Change the value of PATH", SafeText.original(textOf(ProfileTags.ConfirmAction)))
        // Both values masked until their own reveal.
        tag(ProfileTags.ConfirmNewMasked).assertExists()
        tag(ProfileTags.ConfirmNowMasked).assertExists()
        assertFalse(exists(ProfileTags.ConfirmNewValue))
        tap(ProfileTags.ConfirmNewReveal)
        assertEquals("/opt/bin", SafeText.original(textOf(ProfileTags.ConfirmNewValue)))
        tap(ProfileTags.ConfirmNowReveal)
        assertEquals("/usr/bin", SafeText.original(textOf(ProfileTags.ConfirmNowValue)))
        confirm()
        waitForWrites(w, 1)
        assertEquals(frame(gemini(extraEnv = ""","PATH":"/opt/bin""""), WORK, zai()), w.frames().single())
        assertTrue(w.writes.single().first.isConfirmed)
    }

    @Test fun aNewValueStaysMaskedInTheConfirmation() {
        show(writer = recording())
        tap(ProfileTags.envReveal("gemini", "PATH"))
        typeAndDone(ProfileTags.envInput("gemini", "PATH"), SENTINEL)
        // The dialog draws a fixed mask; only the reveal inside it shows the value.
        val dialog = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().last()
        fun all(node: SemanticsNode): String = node.config.joinToString { "${it.key.name}=${it.value}" } + node.children.joinToString { all(it) }
        assertFalse(all(dialog).contains(SENTINEL))
        assertTrue(texts().contains("New value, hidden"))
    }

    @Test fun addingARiskyKeyInAnyCaseIsConfirmedAndCancelSendsNothing() {
        val w = answering()
        show(writer = w)
        tag(ProfileTags.envNewName("claude-work")).performScrollTo().performTextReplacement("path")
        tap(ProfileTags.envNewReveal("claude-work"))
        tag(ProfileTags.envNewInput("claude-work")).performTextReplacement("/tmp/evil")
        tap(ProfileTags.envAdd("claude-work"))
        tag(ProfileTags.ConfirmSheet).assertExists()
        assertEquals("Add path", SafeText.original(textOf(ProfileTags.ConfirmAction)))
        tag(ProfileTags.Cancel).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), w.writes)
        // The draft is kept; Add asks again, and the confirmation sends it.
        tap(ProfileTags.envAdd("claude-work"))
        confirm()
        waitForWrites(w, 1)
        assertEquals(frame(withPath, WORK.replace(",\"enabled\":true", ",\"enabled\":true,\"env\":{\"path\":\"/tmp/evil\"}"), zai()), w.frames().single())
    }

    @Test fun renamingOntoARiskyKeyIsConfirmedAndAFocusLossNeverSendsIt() {
        val w = answering()
        show(writer = w)
        val key = ProfileTags.envKey("zai", "ANTHROPIC_BASE_URL")
        tag(key).performScrollTo().performClick()
        tag(key).performTextReplacement("HOME")
        tag(ProfileTags.field("zai", ProfileTags.LABEL)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), w.writes)
        assertFalse(exists(ProfileTags.ConfirmSheet))
        tag(CommitFieldTags.note(key)).assertExists()
        assertTrue(texts().contains(CommitOutcome.REVIEW_ON_DONE))
        tag(key).performScrollTo().performImeAction()
        compose.waitForIdle()
        assertEquals("Rename ANTHROPIC_BASE_URL → HOME", SafeText.original(textOf(ProfileTags.ConfirmAction)))
        confirm()
        waitForWrites(w, 1)
        assertEquals(frame(withPath, WORK, zai().replace("\"ANTHROPIC_BASE_URL\":", "\"HOME\":")), w.frames().single())
    }

    @Test fun removingARiskyKeyIsConfirmed() {
        val w = answering()
        show(writer = w)
        tap(ProfileTags.envRemove("gemini", "PATH"))
        assertEquals(emptyList<Any>(), w.writes)
        assertEquals("Remove PATH", SafeText.original(textOf(ProfileTags.ConfirmAction)))
        tag(ProfileTags.ConfirmNowMasked).assertExists()
        confirm()
        waitForWrites(w, 1)
        assertEquals(frame(gemini(), WORK, zai()), w.frames().single())
    }

    @Test fun aRiskyKeyChangedElsewhereMidConfirmationClosesItAndSaysSo() {
        val w = recording()
        show(writer = w)
        tap(ProfileTags.envRemove("gemini", "PATH"))
        tag(ProfileTags.ConfirmSheet).assertExists()
        broadcast(profiles(gemini(extraEnv = ""","PATH":"/usr/local/bin""""), WORK, zai()))
        assertFalse(exists(ProfileTags.ConfirmSheet))
        tag(ProfileTags.notice("gemini")).assertExists()
        assertTrue(texts().contains(ProfileRows.CHANGED_WHILE_CONFIRMING))
        assertEquals(emptyList<Any>(), w.writes)
    }

    // ---- B: Extends ---------------------------------------------------------------------------------

    @Test fun anotherEngineIsConfirmedShowingWhatItWillRun() {
        val w = answering()
        show(writer = w)
        compose.onNodeWithContentDescription("gemini extends").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("claude").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), w.writes)
        assertTrue(texts().contains("Change the Gemini CLI engine?"))
        assertEquals("claude", SafeText.original(textOf(ProfileTags.ConfirmNew)))
        assertEquals("acp", SafeText.original(textOf(ProfileTags.ConfirmNow)))
        // Claude hides the command in the editor, but the server uses it: shown here.
        assertEquals(listOf("gemini", "--experimental-acp"), SafeText.original(textOf(ProfileTags.ConfirmCommand)).split('\n'))
        assertEquals("/srv/homes/gemini", SafeText.original(textOf(ProfileTags.ConfirmHome)))
        assertTrue(texts().any { it.contains("For Claude, the command's first part is the CLI path") })
        confirm()
        waitForWrites(w, 1)
        assertEquals(frame(gemini(extraEnv = ""","PATH":"/usr/bin"""", extends = "claude"), WORK, zai()), w.frames().single())
    }

    @Test fun anEngineChangedElsewhereMidConfirmationClosesIt() {
        val w = recording()
        show(writer = w)
        compose.onNodeWithContentDescription("claude-work extends").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("codex").performClick()
        compose.waitForIdle()
        tag(ProfileTags.ConfirmSheet).assertExists()
        broadcast(profiles(withPath, WORK.replace("\"extends\":\"claude\"", "\"extends\":\"pi\""), zai()))
        assertFalse(exists(ProfileTags.ConfirmSheet))
        tag(ProfileTags.notice("claude-work")).assertExists()
        assertEquals(emptyList<Any>(), w.writes)
    }

    // ---- security F1: a reconnect ------------------------------------------------------------------

    @Test fun aReconnectClosesAConfirmationAndNothingIsSent() {
        val w = recording()
        show(writer = w)
        typeAndDone(ProfileTags.field("gemini", ProfileTags.COMMAND), "/opt/g")
        tag(ProfileTags.ConfirmSheet).assertExists()
        // The same registry again, but from a new socket.
        broadcast(profiles(withPath, WORK, zai()), epoch = 1)
        assertFalse(exists(ProfileTags.ConfirmSheet))
        tag(ProfileTags.notice("gemini")).assertExists()
        assertEquals(emptyList<Any>(), w.writes)
    }

    // ---- verifier F1: a write in flight ----------------------------------------------------------------

    /** The verifier's probe: a plain write before the confirmed write's broadcast would put the old command back. */
    @Test fun aSecondWriteBeforeTheBroadcastIsRefusedAndSaysSo() {
        val w = recording()
        show(writer = w)
        typeAndDone(ProfileTags.field("gemini", ProfileTags.COMMAND), "/opt/g")
        confirm()
        waitForWrites(w, 1)
        compose.waitForIdle()
        typeAndDone(ProfileTags.field("claude-work", ProfileTags.LABEL), "Work")
        assertEquals(1, w.writes.size)
        tag(CommitFieldTags.note(ProfileTags.field("claude-work", ProfileTags.LABEL))).assertExists()
        assertTrue(texts().contains(ProfileRows.NOT_SAVED_IN_FLIGHT))
        // Once the broadcast lands, Done sends it, built on the new command.
        broadcast(profiles(gemini(command = """["/opt/g"]""", extraEnv = ""","PATH":"/usr/bin""""), WORK, zai()))
        tag(ProfileTags.field("claude-work", ProfileTags.LABEL)).performImeAction()
        waitForWrites(w, 2)
        assertEquals(frame(gemini(command = """["/opt/g"]""", extraEnv = ""","PATH":"/usr/bin""""), WORK.replace("Claude Code (work)", "Work"), zai()), w.frames()[1])
    }

    /** The verifier's probe: two edits in one frame never undo each other. */
    @Test fun twoEditsInOneFrameNeverUndoEachOther() {
        val w = recording()
        show(writer = w)
        val a = tag(ProfileTags.switch("zai")).performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val b = tag(ProfileTags.switch("gemini")).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            a()
            b()
            flushWrites()
        }
        compose.waitForIdle()
        assertEquals(1, w.writes.size)
        tag(ProfileTags.notice("gemini")).assertExists()
    }

    @Test fun anUnconfirmedWriteSaysSoAfterTheTimeout() {
        val w = recording()
        show(writer = w)
        tap(ProfileTags.switch("zai"))
        assertEquals(1, w.writes.size)
        assertFalse(exists(ProfileTags.Notice))
        compose.mainClock.advanceTimeBy(ProvidersInFlight.TIMEOUT_MS + 100)
        compose.waitForIdle()
        tag(ProfileTags.Notice).assertExists()
        assertTrue(texts().contains(ProfileRows.UNCONFIRMED))
    }

    // ---- security F4, F8, F9 ------------------------------------------------------------------------

    @Test fun aRefusedWriteIsNeverSilent() {
        val w = recording()
        w.refuseWith = ProvidersRefusal.Stale
        show(writer = w)
        tap(ProfileTags.switch("zai"))
        tag(ProfileTags.notice("zai")).assertExists()
        assertTrue(texts().contains(ProfileRows.NOT_SAVED_CHANGED))
        // A field keeps the text, says why, and Done tries again.
        typeAndDone(ProfileTags.field("zai", ProfileTags.LABEL), "GLM")
        assertEquals("GLM", editable(ProfileTags.field("zai", ProfileTags.LABEL)))
        tag(CommitFieldTags.note(ProfileTags.field("zai", ProfileTags.LABEL))).assertExists()
        w.refuseWith = null
        tag(ProfileTags.field("zai", ProfileTags.LABEL)).performImeAction()
        waitForWrites(w, 1)
        assertEquals(frame(withPath, WORK, zai().replace("Z.AI GLM", "GLM")), w.frames().single())
        compose.waitForIdle()
        assertFalse(exists(ProfileTags.notice("zai")))
        // A refused confirmation says so too.
        w.refuseWith = ProvidersRefusal.InFlight
        typeAndDone(ProfileTags.field("gemini", ProfileTags.COMMAND), "/opt/g")
        confirm()
        compose.waitForIdle()
        tag(ProfileTags.notice("gemini")).assertExists()
        assertTrue(texts().contains(ProfileRows.NOT_SAVED_IN_FLIGHT))
    }

    /** F8: the switch sends the value the user flipped to (here off), whatever the newest list says. */
    @Test fun theSwitchSendsTheIntendedValue() {
        val w = recording()
        show(writer = w)
        tap(ProfileTags.switch("gemini"))
        waitForWrites(w, 1)
        assertEquals(frame(withPath.replace("\"enabled\":true", "\"enabled\":false"), WORK, zai()), w.frames().single())
    }

    @Test fun anEnvNameCollisionIsRefusedAndSaid() {
        val w = recording()
        show(writer = w)
        val key = ProfileTags.envKey("zai", "ANTHROPIC_BASE_URL")
        typeAndDone(key, "ANTHROPIC_AUTH_TOKEN")
        tag(CommitFieldTags.note(key)).assertExists()
        assertTrue(texts().contains(ProfileRows.NOT_SAVED_COLLISION))
        tag(ProfileTags.envNewName("zai")).performScrollTo().performTextReplacement(" ANTHROPIC_AUTH_TOKEN ")
        tap(ProfileTags.envAdd("zai"))
        tag(ProfileTags.envNewNote("zai")).assertExists()
        assertEquals(emptyList<Any>(), w.writes)
    }

    /** F4 for slice 3's rows (the shared field): a server-settings write that is refused says so and keeps the text. */
    @Test fun aRefusedServerSettingsWriteIsNotSilentEither() {
        val refusing = object : ServerSettingsWriter {
            override fun patch(patch: kotlinx.serialization.json.JsonObject, origin: String) = false
            override fun cliVersion(message: com.tether.app.protocol.ClientMessage.SetAdvancedSettings, origin: String) = false
            override fun detectEngines(origin: String) = false
            override fun confirmed(write: com.tether.app.client.ConfirmedEngineWrite, origin: String) = false
        }
        state.tab = SettingsTab.Advanced
        compose.setContent { SettingsUnderTest(store.prefs, state, serverSettings = ServerFixtures.binding(writer = refusing)) }
        compose.waitUntil(5_000) { state.draft != null }
        val host = ServerSettingsTags.input(com.tether.app.client.ServerSetting.Host)
        typeAndDone(host, "127.0.0.1")
        assertEquals("127.0.0.1", editable(host))
        tag(CommitFieldTags.note(host)).assertExists()
        assertTrue(texts().contains(CommitOutcome.NOT_CONNECTED))
    }
}
