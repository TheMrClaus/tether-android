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
import com.tether.app.client.ProvidersWriteStatus
import com.tether.app.client.ProvidersList
import com.tether.app.client.ProvidersRefusal
import com.tether.app.ui.settings.ProfileFixtures.ORIGIN
import com.tether.app.ui.settings.ProfileFixtures.SENTINEL
import com.tether.app.ui.settings.ProfileFixtures.WORK
import com.tether.app.ui.settings.ProfileFixtures.frame
import com.tether.app.ui.settings.ProfileFixtures.gemini
import com.tether.app.ui.settings.ProfileFixtures.profiles
import com.tether.app.ui.settings.ProfileFixtures.zai
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
 * ta-q6p r2, through the semantics tree: a write while another waits for its broadcast is refused
 * (verifier F1), a refused write is never silent (security F4), the switch sends the value asked
 * for (F8). ta-coik.5: as on the web (settings-dialog.tsx 90fbb9f :359-437, :696), every env key
 * (whatever its name) and the engine are written at once, with no confirmation, and an env name the
 * profile already has is overwritten. Every write is waited for on the writer and asserted as the
 * exact frame.
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

    private fun waitForWrites(w: RecordingProvidersWriter, n: Int) = compose.waitUntil(5_000) { w.writes.size >= n }

    private fun flushWrites() = androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

    // ---- every env key and the engine, at once (ta-coik.5) ----------------------------------------

    /** :393-401: a value's blur writes it, whatever the key; nothing asks first. */
    @Test fun aValueOfAKeyThatChangesWhatRunsIsWrittenAtOnceLikeTheWeb() {
        val w = answering()
        show(writer = w)
        assertEquals("/usr/bin", editable(ProfileTags.envInput("gemini", "PATH")))
        typeAndDone(ProfileTags.envInput("gemini", "PATH"), "/opt/bin")
        waitForWrites(w, 1)
        assertEquals(frame(gemini(extraEnv = ""","PATH":"/opt/bin""""), WORK, zai()), w.frames().single())
        assertFalse("no confirmation", texts().any { it.startsWith("Change what ") })
    }

    @Test fun addingAKeyInAnyCaseIsWrittenAtOnce() {
        val w = answering()
        show(writer = w)
        tag(ProfileTags.envNewName("claude-work")).performScrollTo().performTextReplacement("path")
        tag(ProfileTags.envNewInput("claude-work")).performScrollTo().performTextReplacement("/tmp/evil")
        tap(ProfileTags.envAdd("claude-work"))
        waitForWrites(w, 1)
        assertEquals(frame(withPath, WORK.replace(",\"enabled\":true", ",\"enabled\":true,\"env\":{\"path\":\"/tmp/evil\"}"), zai()), w.frames().single())
        // The draft clears once sent, as the web's Add.
        compose.waitForIdle()
        assertEquals("", editable(ProfileTags.envNewName("claude-work")))
        assertEquals("", editable(ProfileTags.envNewInput("claude-work")))
    }

    /** :376-391: a name's blur renames it, onto any name. */
    @Test fun renamingOntoAKeyThatChangesWhatRunsIsWrittenOnBlur() {
        val w = answering()
        show(writer = w)
        val key = ProfileTags.envKey("zai", "ANTHROPIC_BASE_URL")
        tag(key).performScrollTo().performClick()
        tag(key).performTextReplacement("HOME")
        tag(ProfileTags.field("zai", ProfileTags.LABEL)).performScrollTo().performClick()
        waitForWrites(w, 1)
        assertEquals(frame(withPath, WORK, zai().replace("\"ANTHROPIC_BASE_URL\":", "\"HOME\":")), w.frames().single())
    }

    @Test fun removingAKeyIsWrittenAtOnce() {
        val w = answering()
        show(writer = w)
        tap(ProfileTags.envRemove("gemini", "PATH"))
        waitForWrites(w, 1)
        assertEquals(frame(gemini(), WORK, zai()), w.frames().single())
    }

    /** :696: another engine is written at once. */
    @Test fun anotherEngineIsWrittenAtOnce() {
        val w = answering()
        show(writer = w)
        compose.onNodeWithContentDescription("gemini extends").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("claude").performClick()
        waitForWrites(w, 1)
        assertEquals(frame(gemini(extraEnv = ""","PATH":"/usr/bin"""", extends = "claude"), WORK, zai()), w.frames().single())
        assertFalse("no confirmation", texts().contains("Change the Gemini CLI engine?"))
    }

    // ---- verifier F1: a write in flight ----------------------------------------------------------------

    /** The verifier's probe: a write before the last write's broadcast would put the old command back. */
    @Test fun aSecondWriteBeforeTheBroadcastIsRefusedAndSaysSo() {
        val w = recording()
        show(writer = w)
        typeAndDone(ProfileTags.field("gemini", ProfileTags.COMMAND), "/opt/g")
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
        // A refused command says so too, under its field.
        w.refuseWith = ProvidersRefusal.InFlight
        typeAndDone(ProfileTags.field("gemini", ProfileTags.COMMAND), "/opt/g")
        tag(CommitFieldTags.note(ProfileTags.field("gemini", ProfileTags.COMMAND))).assertExists()
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

    /**
     * ta-coik.4 / ta-coik.5: a name the profile already has is overwritten, as the web's rename (:386-389:
     * `delete next[key]; next[nextKey] = value`) and Add (:369: `{ ...env, [key]: draftValue }`) do.
     */
    @Test fun anEnvNameCollisionOverwritesAsTheWeb() {
        val w = answering()
        show(writer = w)
        val key = ProfileTags.envKey("zai", "ANTHROPIC_BASE_URL")
        typeAndDone(key, "ANTHROPIC_AUTH_TOKEN")
        waitForWrites(w, 1)
        val renamed = zai().replace(""""ANTHROPIC_AUTH_TOKEN":"FAKE-demo-token-1111","ANTHROPIC_BASE_URL":""", """"ANTHROPIC_AUTH_TOKEN":""")
        assertEquals(frame(withPath, WORK, renamed), w.frames().single())
        compose.waitForIdle()
        tag(ProfileTags.envNewName("zai")).performScrollTo().performTextReplacement(" ANTHROPIC_AUTH_TOKEN ")
        tag(ProfileTags.envNewInput("zai")).performScrollTo().performTextReplacement("FAKE-new")
        tap(ProfileTags.envAdd("zai"))
        waitForWrites(w, 2)
        assertEquals(frame(withPath, WORK, zai(token = "FAKE-new").replace(""","ANTHROPIC_BASE_URL":"https://api.z.ai/api/anthropic"""", "")), w.frames()[1])
        assertFalse(exists(ProfileTags.envNewNote("zai")))
    }

    /** F4 for slice 3's rows (the shared field): a server-settings write that is refused says so and keeps the text. */
    @Test fun aRefusedServerSettingsWriteIsNotSilentEither() {
        val refusing = object : ServerSettingsWriter {
            override fun patch(patch: kotlinx.serialization.json.JsonObject, origin: String) = false
            override fun cliVersion(message: com.tether.app.protocol.ClientMessage.SetAdvancedSettings, origin: String) = false
            override fun detectEngines(origin: String) = false
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

    // ---- r3 ------------------------------------------------------------------------------------------

    /**
     * ta-coik.4: a key holding `=` is any name the web's env editor takes (settings-dialog.tsx:366-372),
     * and (ta-coik.5) it is written at once, exactly as typed.
     */
    @Test fun anEqualsSignInANewKeyIsWrittenAsTyped() {
        val w = answering()
        show(writer = w)
        tag(ProfileTags.envNewName("claude-work")).performScrollTo().performTextReplacement("LD_PRELOAD=/tmp/x.so:")
        tag(ProfileTags.envNewInput("claude-work")).performScrollTo().performTextReplacement("v")
        tap(ProfileTags.envAdd("claude-work"))
        assertFalse("no shape refusal", texts().any { it.startsWith("Not saved: use letters") })
        waitForWrites(w, 1)
        assertTrue(w.frames().single().toString().contains("LD_PRELOAD=/tmp/x.so:"))
    }

    /** A key the server already holds that is not a plain name is removed at once like any other. */
    @Test fun anExistingKeyThatIsNotAPlainNameIsRemovedAtOnce() {
        val w = answering()
        show(ProfileFixtures.list(profiles(gemini(extraEnv = ""","A-B":"v""""), WORK, zai())), w)
        tap(ProfileTags.envRemove("gemini", "A-B"))
        waitForWrites(w, 1)
        assertEquals(frame(gemini(), WORK, zai()), w.frames().single())
    }

    /** Item 3 (the verifier's probe): another client's list landing before ours keeps the guard up. */
    @Test fun anotherClientsListDoesNotLiftTheGuard() {
        val w = recording()
        show(writer = w)
        tap(ProfileTags.switch("zai"))
        assertEquals(1, w.writes.size)
        // Theirs: built before ours, it lacks our switch.
        broadcast(profiles(withPath, WORK.replace("Claude Code (work)", "Theirs"), zai()))
        typeAndDone(ProfileTags.field("gemini", ProfileTags.LABEL), "G")
        assertEquals(1, w.writes.size)
        assertTrue(texts().contains(ProfileRows.NOT_SAVED_IN_FLIGHT))
        // Ours lands: Done sends, on top of both.
        broadcast(profiles(withPath, WORK.replace("Claude Code (work)", "Theirs"), zai().replace("\"enabled\":false", "\"enabled\":true")))
        tag(ProfileTags.field("gemini", ProfileTags.LABEL)).performImeAction()
        waitForWrites(w, 2)
        assertEquals(
            frame(withPath.replace("\"label\":\"Gemini CLI\"", "\"label\":\"G\""), WORK.replace("Claude Code (work)", "Theirs"), zai().replace("\"enabled\":false", "\"enabled\":true")),
            w.frames()[1],
        )
    }

    // ---- r4: one in-flight guard, and it recovers from a refused write ------------------------------

    /** The server refuses W1 (an error, no broadcast): after the timeout and the re-request's reply, the next edit IS sent, and the notice says W1 was not saved. */
    @Test fun aRefusedWriteRecoversAfterTheTimeoutAndSaysItWasNotSaved() {
        val w = recording()
        show(writer = w)
        tap(ProfileTags.switch("zai"))
        assertEquals(1, w.writes.size)
        typeAndDone(ProfileTags.field("gemini", ProfileTags.LABEL), "G")
        assertEquals(1, w.writes.size)
        assertTrue(texts().contains(ProfileRows.NOT_SAVED_IN_FLIGHT))
        // The timeout passes: the client asks for the registry again; the section says it is unconfirmed.
        w.now += ProvidersInFlight.TIMEOUT_MS
        w.tick()
        assertEquals(1, w.reRequests)
        compose.mainClock.advanceTimeBy(ProvidersInFlight.TIMEOUT_MS + 100)
        compose.waitForIdle()
        assertTrue(texts().contains(ProfileRows.UNCONFIRMED))
        // The reply: the registry as it was (the server did not take W1).
        broadcast(profiles(withPath, WORK, zai()))
        assertTrue(texts().contains(ProfileRows.LAST_NOT_SAVED))
        // The next edit is sent.
        tag(ProfileTags.field("gemini", ProfileTags.LABEL)).performImeAction()
        waitForWrites(w, 2)
        assertEquals(frame(withPath.replace("\"label\":\"Gemini CLI\"", "\"label\":\"G\""), WORK, zai()), w.frames()[1])
    }

    /** The app's editor has no guard of its own: the client's refusal is the only one (a fresh binding never refuses by itself). */
    @Test fun theEditorDefersToTheClientsGuard() {
        var clientSays: ProvidersRefusal? = null
        val list = ProfileFixtures.list(profiles(withPath, WORK, zai()))
        val client = object : ProvidersWriter {
            val sent = mutableListOf<com.tether.app.client.ProvidersWrite>()
            override fun setProviders(write: com.tether.app.client.ProvidersWrite, origin: String): ProvidersRefusal? = clientSays ?: null.also { sent += write }
        }
        val b = ProvidersBinding(list, ORIGIN, client)
        assertEquals(ProvidersSend.Sent, b.send(com.tether.app.client.ProfileEdit.Label("zai", "A")))
        // A second write from the same (not yet updated) list: the editor sends it on; only the client decides.
        assertEquals(ProvidersSend.Sent, b.send(com.tether.app.client.ProfileEdit.Label("zai", "B")))
        clientSays = ProvidersRefusal.InFlight
        assertEquals(ProvidersSend.Refused(ProvidersRefusal.InFlight), b.send(com.tether.app.client.ProfileEdit.Label("zai", "C")))
        assertEquals(2, client.sent.size)
    }
}
