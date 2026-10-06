package com.tether.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import com.tether.app.client.ModelList
import com.tether.app.client.ProfileEdit
import com.tether.app.client.ProvidersList
import com.tether.app.client.ProvidersPatch
import com.tether.app.client.SecretText
import com.tether.app.ui.settings.ProfileFixtures.FAKE_KEY
import com.tether.app.ui.settings.ProfileFixtures.ORIGIN
import com.tether.app.ui.settings.ProfileFixtures.SENTINEL
import com.tether.app.ui.settings.ProfileFixtures.SENTINEL_2
import com.tether.app.ui.settings.ProfileFixtures.WORK
import com.tether.app.ui.settings.ProfileFixtures.frame
import com.tether.app.ui.settings.ProfileFixtures.gemini
import com.tether.app.ui.settings.ProfileFixtures.profiles
import com.tether.app.ui.settings.ProfileFixtures.zai
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.tokensFor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
import org.robolectric.shadows.ShadowLog

/**
 * ta-q6p: Settings > Engines > Custom providers (settings-dialog.tsx 887c222 `ProfilesEditor`)
 * through the semantics tree: the web's order and words; every write the exact whole list, waited
 * for on the writer; a write built from the newest list (a concurrent broadcast mid-edit is never
 * overwritten). ta-coik.5, as on the web (90fbb9f :359-437, :696-740): the command, home and engine
 * write at once, as every other field's blur does; env names and values are plain fields (a copy
 * marked sensitive), never in a log, the preference store or saved state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProfilesBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Engines)
    private var providers by mutableStateOf(ProvidersBinding.None)
    private var shown by mutableStateOf(true)
    private val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })

    /** A writer whose every write the "server" applies and broadcasts back (the next generation). */
    private fun answering() = RecordingProvidersWriter(newest = { providers.list }) { write ->
        val next = (providers.list?.generation ?: 0) + 1
        providers = providers.copy(list = ProfileFixtures.list(write.profiles, next))
    }

    /** A writer that records only (no broadcast). */
    private fun recording() = RecordingProvidersWriter(newest = { providers.list })

    private fun show(list: ProvidersList? = ProfileFixtures.list(), writer: ProvidersWriter = ProvidersWriter.None, origin: String? = ORIGIN, menus: MenuSpies? = null) {
        providers = ProvidersBinding(list, origin, writer)
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                WithMenuSpies(menus) {
                    if (shown) SettingsUnderTest(store.prefs, state, providers = providers)
                }
            }
        }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitForIdle()
    }

    /** A broadcast from another client: the next generation of the list. */
    private fun broadcast(profiles: String) {
        val next = (providers.list?.generation ?: 0) + 1
        providers = providers.copy(list = ProfileFixtures.list(profiles, next))
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

    /** EVERY semantics property of every node in every root (text, editable AND raw input text, labels, actions…). */
    private fun allSemantics(): String {
        val out = StringBuilder()
        fun walk(node: SemanticsNode) {
            for ((key, value) in node.config) out.append(key.name).append('=').append(value).append('\n')
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out.toString()
    }

    private fun savedState(): String = registry.performSave().toString() +
        with(SettingsDialogState.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(state) }.toString()

    private fun assertNowhere(vararg leaks: String) {
        val semantics = allSemantics()
        val saved = savedState()
        val logs = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        val prefs = store.stored().toString()
        for (leak in leaks) {
            assertFalse("semantics holds $leak", semantics.contains(leak))
            assertFalse("saved state holds $leak", saved.contains(leak))
            assertFalse("a log line holds $leak", logs.contains(leak))
            assertFalse("the preference store holds $leak", prefs.contains(leak))
        }
    }

    private fun textOf(t: String): String = tag(t).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun editable(t: String): String = tag(t).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun field(id: String, what: String) = ProfileTags.field(id, what)

    /** Type [value] into [t] and press Done; the edit is asserted to have reached the field. */
    private fun typeAndDone(t: String, value: String) {
        val f = tag(t).performScrollTo()
        f.performTextReplacement(value)
        compose.waitForIdle()
        assertEquals("the edit reached the field", value, editable(t))
        f.performImeAction()
        compose.waitForIdle()
    }

    /** Flip through the semantics action (the toggleable's OnClick), as TalkBack would. */
    private fun flip(node: SemanticsNodeInteraction) {
        node.performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun tap(t: String) {
        tag(t).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun flushWrites() = androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

    private fun waitForWrites(w: RecordingProvidersWriter, n: Int) = compose.waitUntil(5_000) { w.writes.size >= n }


    // ---- order and words -------------------------------------------------------------------------

    @Test fun theCardsFollowTheWebsOrderAndWords() {
        show()
        val all = texts()
        val order = listOf(
            ProfileRows.TITLE, "Gemini CLI", "Label", "ID", "Extends", "Command", "Home", "Env", "Drop env prefixes", "Disallowed tools", "Order",
            "Verified through", "Models", "Additional models", "Remove", "Claude Code (work)", "Z.AI GLM", ProfileRows.ADD,
        )
        val at = order.map { text -> all.indexOf(text).also { assertTrue("$text missing", it >= 0) } }
        assertEquals(at.sorted(), at)
        assertTrue(all.any { it.startsWith("Declarative profiles extending the built-in engines") && it.contains("providers.json") && it.contains("(0600)") })
        for (text in listOf(ProfileRows.LABEL_CAPTION, ProfileRows.ID_CAPTION, ProfileRows.EXTENDS_CAPTION, ProfileRows.ENV_CAPTION, ProfileRows.ORDER_CAPTION,
            ProfileRows.MODELS_CAPTION, ProfileRows.ADDITIONAL_CAPTION, ProfileRows.HOME_ACP, "Enable Gemini CLI", "gemini label", "gemini command", "gemini home")) {
            assertTrue(text, all.contains(text))
        }
        assertTrue(all.any { it.startsWith("Binary + arguments (space-separated), e.g. gemini --acp.") })
        assertTrue(all.contains(ProfileRows.toolsCaption("acp")))
        assertTrue(all.contains(ProfileRows.toolsCaption("claude")))
        // The head: `extends · command`, the switch.
        assertEquals("acp · gemini --experimental-acp", textOf(ProfileTags.subtitle("gemini")))
        assertEquals("claude", textOf(ProfileTags.subtitle("claude-work")))
        tag(ProfileTags.switch("gemini")).assertIsOn()
        tag(ProfileTags.switch("zai")).assertIsOff()
        // The fields hold the server's values.
        assertEquals("gemini --experimental-acp", editable(field("gemini", ProfileTags.COMMAND)))
        assertEquals("/srv/homes/gemini", editable(field("gemini", ProfileTags.HOME)))
        assertEquals("GEMINI", editable(field("gemini", ProfileTags.DROP_ENV)))
        assertEquals("1", editable(field("gemini", ProfileTags.ORDER)))
        assertEquals("0.43.0", editable(field("gemini", ProfileTags.VERIFIED)))
        assertEquals("claude-opus-4", editable(ProfileTags.modelId("claude-work", ModelList.Models, 0)))
        assertEquals("Sonnet", editable(ProfileTags.modelLabel("claude-work", ModelList.Models, 1)))
        tag(ProfileTags.modelDefault("claude-work", ModelList.Models, 0)).assertIsSelected()
        // Claude has no command field and no watermark; only Claude takes disallowed tools.
        assertFalse(exists(ProfileTags.row("claude-work", ProfileTags.COMMAND)))
        assertFalse(exists(ProfileTags.row("claude-work", ProfileTags.VERIFIED)))
        tag(field("gemini", ProfileTags.TOOLS)).assertIsNotEnabled()
        tag(field("claude-work", ProfileTags.TOOLS)).assertIsEnabled()
    }

    @Test fun beforeTheServerRepliesTheSectionWaitsAndSignedOutNothingIsDrawn() {
        show(list = null)
        tag(ProfileTags.Section).assertExists()
        tag(ProfileTags.Loading).assertExists()
        assertFalse(exists(ProfileTags.Add))
        providers = ProfileFixtures.binding(origin = null)
        compose.waitForIdle()
        assertFalse(exists(ProfileTags.card("gemini")))
        tag(ProfileTags.Loading).assertExists()
    }

    @Test fun anEmptyRegistrySaysSoAndAddSendsTheWebsNewProfile() {
        val w = answering()
        show(ProfileFixtures.list("[]"), w)
        assertTrue(texts().contains(ProfileRows.EMPTY))
        tap(ProfileTags.Add)
        waitForWrites(w, 1)
        assertEquals(listOf(frame("""{"id":"profile","extends":"claude","label":"profile","enabled":true}""")), w.frames())
        compose.waitForIdle()
        tag(ProfileTags.card("profile")).assertExists()
        // A second Add picks the next free id.
        tap(ProfileTags.Add)
        waitForWrites(w, 2)
        assertEquals(
            frame("""{"id":"profile","extends":"claude","label":"profile","enabled":true}""", """{"id":"profile-2","extends":"claude","label":"profile-2","enabled":true}"""),
            w.frames()[1],
        )
        assertTrue(w.writes.all { it.second == ORIGIN })
    }

    // ---- the plain edits (the web's handlers) -----------------------------------------------------

    @Test fun theSwitchRemoveAndExtendsWriteTheWholeList() {
        val w = answering()
        show(writer = w)
        flip(tag(ProfileTags.switch("zai")))
        waitForWrites(w, 1)
        assertEquals(frame(gemini(), WORK, zai().replace("\"enabled\":false", "\"enabled\":true")), w.frames()[0])
        tag(ProfileTags.switch("zai")).assertIsOn()
        tap(ProfileTags.remove("claude-work"))
        waitForWrites(w, 2)
        assertEquals(frame(gemini(), zai().replace("\"enabled\":false", "\"enabled\":true")), w.frames()[1])
        compose.waitForIdle()
        assertFalse(exists(ProfileTags.card("claude-work")))
        compose.onNodeWithContentDescription("zai extends").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("codex").performClick()
        compose.waitForIdle()
        // :696: another engine is written at once.
        waitForWrites(w, 3)
        assertEquals(frame(gemini(), zai().replace("\"enabled\":false", "\"enabled\":true").replace("\"extends\":\"claude\"", "\"extends\":\"codex\"")), w.frames()[2])
    }

    @Test fun aTextFieldWritesOnDoneAsTheWebsBlur() {
        val w = answering()
        show(writer = w)
        typeAndDone(field("claude-work", ProfileTags.LABEL), "  Work  ")
        waitForWrites(w, 1)
        assertEquals(frame(gemini(), WORK.replace("\"label\":\"Claude Code (work)\"", "\"label\":\"Work\""), zai()), w.frames()[0])
        typeAndDone(field("gemini", ProfileTags.DROP_ENV), "GEMINI, GOOGLE")
        waitForWrites(w, 2)
        assertEquals(
            frame(gemini().replace("[\"GEMINI\"]", "[\"GEMINI\",\"GOOGLE\"]"), WORK.replace("\"label\":\"Claude Code (work)\"", "\"label\":\"Work\""), zai()),
            w.frames()[1],
        )
        typeAndDone(field("gemini", ProfileTags.ID), "gemini-cli")
        waitForWrites(w, 3)
        compose.waitForIdle()
        tag(ProfileTags.card("gemini-cli")).assertExists()
        assertEquals(3, w.writes.size)
    }

    /** :801-813 `<input type="number">`: what a browser's number input takes (digits, `+ - . e E`), then `Number(raw)`; ta-coik.17: no cap. */
    @Test fun theOrderFieldTakesWhatTheWebsNumberInputTakes() {
        val w = answering()
        show(writer = w)
        val order = field("gemini", ProfileTags.ORDER)
        tag(order).performScrollTo().performTextReplacement("1a")
        compose.waitForIdle()
        assertEquals("not a number key", "1", editable(order))
        typeAndDone(order, "6e4")
        waitForWrites(w, 1)
        assertEquals(frame(gemini().replace("\"order\":1", "\"order\":60000"), WORK, zai()), w.frames()[0])
        typeAndDone(order, "-5")
        waitForWrites(w, 2)
        assertEquals(frame(gemini().replace("\"order\":1", "\"order\":-5"), WORK, zai()), w.frames()[1])
        typeAndDone(order, "12345678")
        waitForWrites(w, 3)
        assertEquals(frame(gemini().replace("\"order\":1", "\"order\":12345678"), WORK, zai()), w.frames()[2])
    }

    @Test fun anEditLeftInAFieldIsSentWhenSettingsClosesLikeTheWebsBlur() {
        val w = recording()
        show(writer = w)
        val f = tag(field("zai", ProfileTags.LABEL)).performScrollTo()
        f.performClick()
        f.performTextReplacement("GLM")
        compose.waitForIdle()
        assertEquals(0, w.writes.size)
        shown = false
        compose.waitForIdle()
        assertEquals(frame(gemini(), WORK, zai().replace("\"label\":\"Z.AI GLM\"", "\"label\":\"GLM\"")), w.frames().single())
    }

    // ---- concurrent edits ------------------------------------------------------------------------

    /** A broadcast mid-edit (another client toggled a profile): the write applies to it, never undoes it. */
    @Test fun aBroadcastMidEditIsKeptByTheWrite() {
        val w = recording()
        show(writer = w)
        val label = field("claude-work", ProfileTags.LABEL)
        tag(label).performScrollTo().performTextReplacement("Work")
        compose.waitForIdle()
        broadcast(profiles(gemini().replace("\"enabled\":true", "\"enabled\":false"), WORK, zai()))
        // The other field's edit is untouched by a broadcast that did not change it.
        assertEquals("Work", editable(label))
        tag(label).performImeAction()
        waitForWrites(w, 1)
        assertEquals(frame(gemini().replace("\"enabled\":true", "\"enabled\":false"), WORK.replace("\"label\":\"Claude Code (work)\"", "\"label\":\"Work\""), zai()), w.frames().single())
        assertEquals(2L, w.writes.single().first.generation)
    }

    /** A broadcast that changes the very value being edited refills the field: nothing stale is sent. */
    @Test fun aBroadcastOfTheSameFieldRefillsItAndSendsNothing() {
        val w = recording()
        show(writer = w)
        val label = field("claude-work", ProfileTags.LABEL)
        tag(label).performScrollTo().performTextReplacement("Mine")
        compose.waitForIdle()
        broadcast(profiles(gemini(), WORK.replace("Claude Code (work)", "Theirs"), zai()))
        assertEquals("Theirs", editable(label))
        tag(label).performImeAction()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), w.writes)
    }

    /** ta-coik.17 r2: the client rebuilds a write built from a list older than its newest one (the race the binding cannot see) on the newest; never refused. */
    @Test fun aWriteFromAnOlderListIsRebuiltOnTheNewest() {
        var clientNewest = ProfileFixtures.list(generation = 1)
        val w = RecordingProvidersWriter(newest = { clientNewest })
        show(ProfileFixtures.list(generation = 1), w)
        // The client already holds generation 2 (a broadcast the composition has not drawn yet), and
        // the binding reads the composed list (no fresh hook): the edit goes out on generation 2, keeping its switch.
        clientNewest = ProfileFixtures.list(profiles(gemini(), WORK, zai().replace("\"enabled\":false", "\"enabled\":true")), generation = 2)
        typeAndDone(field("claude-work", ProfileTags.LABEL), "Work")
        waitForWrites(w, 1)
        assertEquals(frame(gemini(), WORK.replace("Claude Code (work)", "Work"), zai().replace("\"enabled\":false", "\"enabled\":true")), w.frames().single())
        assertEquals(emptyList<Any>(), w.refused)
        assertFalse(exists(CommitFieldTags.note(field("claude-work", ProfileTags.LABEL))))
        assertFalse(texts().any { it.startsWith("Not saved") })
    }

    // ---- what a profile runs: written as the web's blur writes it (ta-coik.5) ----------------------

    /** :713-717: split at whitespace, written on Done (the web's Enter blurs). */
    @Test fun aCommandIsWrittenOnDoneAtOnceLikeTheWeb() {
        val w = answering()
        show(writer = w)
        typeAndDone(field("gemini", ProfileTags.COMMAND), "/opt/gemini/bin/gemini   --experimental-acp -v")
        waitForWrites(w, 1)
        compose.waitForIdle()
        assertEquals(frame(gemini(command = """["/opt/gemini/bin/gemini","--experimental-acp","-v"]"""), WORK, zai()), w.frames().single())
        assertFalse("no confirmation", texts().any { it.startsWith("Change the ") })
        assertEquals("/opt/gemini/bin/gemini --experimental-acp -v", editable(field("gemini", ProfileTags.COMMAND)))
    }

    @Test fun aHomeIsWrittenAtOnceAndAnEmptiedOneIsDropped() {
        val w = answering()
        show(writer = w)
        typeAndDone(field("claude-work", ProfileTags.HOME), "")
        waitForWrites(w, 1)
        assertEquals(frame(gemini(), WORK.replace(",\"homeDir\":\"/srv/homes/claude-work\"", ""), zai()), w.frames().single())
        compose.waitForIdle()
        typeAndDone(field("zai", ProfileTags.HOME), "  /srv/homes/zai ")
        waitForWrites(w, 2)
        assertEquals(
            frame(gemini(), WORK.replace(",\"homeDir\":\"/srv/homes/claude-work\"", ""), zai().replace(",\"enabled\":false", ",\"enabled\":false,\"homeDir\":\"/srv/homes/zai\"")),
            w.frames()[1],
        )
    }

    /** :706-710 `onBlur`: focus moving away writes the command. */
    @Test fun aCommandIsSentOnAFocusLossLikeTheWebsBlur() {
        val w = recording()
        show(writer = w)
        val command = tag(field("gemini", ProfileTags.COMMAND)).performScrollTo()
        command.performClick()
        command.performTextReplacement("/tmp/next")
        tag(field("gemini", ProfileTags.LABEL)).performScrollTo().performClick()
        waitForWrites(w, 1)
        assertEquals(frame(gemini(command = """["/tmp/next"]"""), WORK, zai()), w.frames().single())
    }

    @Test fun aHomeLeftInItsFieldIsSentWhenSettingsCloses() {
        val w = recording()
        show(writer = w)
        tag(field("gemini", ProfileTags.HOME)).performScrollTo().performTextReplacement("/tmp/home")
        compose.waitForIdle()
        assertEquals(0, w.writes.size)
        shown = false
        compose.waitForIdle()
        assertEquals(frame(gemini().replace("/srv/homes/gemini", "/tmp/home"), WORK, zai()), w.frames().single())
    }

    @Test fun aSpoofedCommandIsSentExactly() {
        val w = answering()
        show(writer = w)
        // A bidi override and a zero-width space are not JavaScript whitespace: they stay in their part.
        // A no-break space IS (the web's `/\s+/`): it splits.
        val typed = "/srv/\u202Egnp.exe\u200B/gemini --x\u00A0y"
        typeAndDone(field("gemini", ProfileTags.COMMAND), typed)
        waitForWrites(w, 1)
        assertEquals(frame(gemini(command = """["/srv/\u202Egnp.exe\u200B/gemini","--x","y"]"""), WORK, zai()), w.frames().single())
    }

    /** ta-coik.17: a command past the server's limits is typed and sent as on the web (the server refuses it; its error is shown). */
    @Test fun aCommandPastTheServersLimitIsSentAsTheWebSendsIt() {
        val w = answering()
        show(writer = w)
        val command = field("gemini", ProfileTags.COMMAND)
        typeAndDone(command, List(33) { "p" }.joinToString(" "))
        waitForWrites(w, 1)
        assertEquals(frame(gemini(command = List(33) { "\"p\"" }.joinToString(",", "[", "]")), WORK, zai()), w.frames()[0])
        typeAndDone(field("gemini", ProfileTags.HOME), "h".repeat(5000))
        waitForWrites(w, 2)
        assertEquals(5000, ((w.frames()[1]["profiles"] as JsonArray)[0] as kotlinx.serialization.json.JsonObject)["homeDir"]!!.let { (it as JsonPrimitive).content.length })
    }

    /** ta-coik.5: every edit, what the profile runs included, is a plain whole-list write (no confirmed-only path). */
    @Test fun everyEditIsAPlainWholeListWrite() {
        val list = ProfileFixtures.list()
        val edits = listOf(
            ProfileEdit.Enabled("gemini", false), ProfileEdit.Label("gemini", "G"), ProfileEdit.Rename("gemini", "g2"),
            ProfileEdit.Extends("gemini", "claude"), ProfileEdit.Command("gemini", "/opt/g --x"), ProfileEdit.Home("gemini", "/tmp/h"),
            ProfileEdit.EnvKey("gemini", "GEMINI_API_KEY", "PATH"), ProfileEdit.EnvValue("gemini", "GEMINI_API_KEY", SecretText("v")),
            ProfileEdit.EnvRemove("gemini", "GEMINI_API_KEY"), ProfileEdit.EnvAdd("gemini", "LD_PRELOAD", SecretText("/tmp/x.so")), ProfileEdit.DropEnv("gemini", "X"),
            ProfileEdit.DisallowedTools("claude-work", "Task"), ProfileEdit.Order("gemini", "3"), ProfileEdit.VerifiedThrough("gemini", "1.0"),
            ProfileEdit.Model("claude-work", ModelList.Models, com.tether.app.client.ModelOp.Remove(0, "claude-opus-4")), ProfileEdit.Add, ProfileEdit.Remove("zai"),
        )
        for (edit in edits) {
            val write = ProvidersPatch.write(list, edit)
            assertTrue("$edit", write != null)
            assertEquals("$edit", null, ProvidersPatch.refusal(write!!, list))
        }
        val command = ProvidersPatch.write(list, ProfileEdit.Command("gemini", "/opt/g --x"))!!
        assertEquals(JsonArray(listOf(JsonPrimitive("/opt/g"), JsonPrimitive("--x"))), command.profiles.first { (it["id"] as JsonPrimitive).content == "gemini" }["command"])
        // ta-coik.17 r2: a write built from an older list is rebuilt on the client's newest, not refused.
        val w = RecordingProvidersWriter(newest = { list })
        val stale = ProvidersPatch.write(ProfileFixtures.list(generation = 0), ProfileEdit.Command("gemini", "/opt/ok"))!!
        assertEquals(null, w.setProviders(stale, ORIGIN))
        assertEquals(1, w.writes.size)
    }

    // ---- the env values: the web's plain fields (ta-coik.5) --------------------------------------

    private fun secretList() = ProfileFixtures.list(profiles(gemini(env = SENTINEL), WORK, zai(token = SENTINEL_2)))

    private fun systemClipboard() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        .getSystemService(android.content.ClipboardManager::class.java)

    /** :393-397: the value is drawn as it is (`defaultValue={value}`), and goes to no log, preference or saved state. */
    @Test fun envValuesAreShownAsTheWebsAndInNoLogPreferenceOrSavedState() {
        ShadowLog.clear()
        show(secretList())
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performScrollTo()
        assertEquals(SENTINEL, editable(ProfileTags.envInput("gemini", "GEMINI_API_KEY")))
        assertFalse(texts().any { it.startsWith("Reveal ") || it.endsWith(", hidden") })
        assertEquals("GEMINI_API_KEY", editable(ProfileTags.envKey("gemini", "GEMINI_API_KEY")))
        val logs = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        for (leak in listOf(SENTINEL, SENTINEL_2)) {
            assertFalse(savedState().contains(leak))
            assertFalse(logs.contains(leak))
            assertFalse(store.stored().toString().contains(leak))
        }
    }

    /** :398-402: a value's blur writes it exactly (not trimmed); Done too. */
    @Test fun aValueIsSentOnBlurAndOnDoneExactly() {
        val w = answering()
        show(secretList(), w)
        val input = tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performScrollTo()
        input.performClick()
        input.performTextReplacement("FAKE-half")
        tag(field("gemini", ProfileTags.LABEL)).performScrollTo().performClick()
        waitForWrites(w, 1)
        assertEquals(frame(gemini(env = "FAKE-half"), WORK, zai(token = SENTINEL_2)), w.frames()[0])
        compose.waitForIdle()
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performScrollTo().performTextReplacement(" FAKE-new ")
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performImeAction()
        waitForWrites(w, 2)
        assertEquals(frame(gemini(env = " FAKE-new "), WORK, zai(token = SENTINEL_2)), w.frames()[1])
    }

    @Test fun aValueLeftInItsFieldIsSentWhenSettingsCloses() {
        val w = recording()
        show(secretList(), w)
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performScrollTo().performTextReplacement("FAKE-ha")
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), w.writes)
        shown = false
        compose.waitForIdle()
        assertEquals(frame(gemini(env = "FAKE-ha"), WORK, zai(token = SENTINEL_2)), w.frames().single())
    }

    /** As the web's plain input: copy and cut work; the clip is marked `EXTRA_IS_SENSITIVE`. */
    @Test fun anEnvValueIsCopiedAndCutOntoASensitiveClip() {
        show(secretList())
        val f = tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performScrollTo()
        f.performClick()
        NoCopyProbe.seed()
        f.performSemanticsAction(SemanticsActions.SetSelection) { it(0, SENTINEL.length, false) }
        compose.waitForIdle()
        f.performSemanticsAction(SemanticsActions.CopyText)
        compose.waitForIdle()
        assertEquals("the copy reached the clipboard", SENTINEL, NoCopyProbe.clip())
        assertTrue("marked sensitive", systemClipboard().primaryClipDescription?.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true)
        NoCopyProbe.seed()
        f.performSemanticsAction(SemanticsActions.SetSelection) { it(0, SENTINEL.length, false) }
        compose.waitForIdle()
        f.performSemanticsAction(SemanticsActions.CutText)
        compose.waitForIdle()
        assertEquals("the cut reached the clipboard", SENTINEL, NoCopyProbe.clip())
        assertEquals("", editable(ProfileTags.envInput("gemini", "GEMINI_API_KEY")))
    }

    /** The Add row's value (:427-434, a plain input): copyable onto a sensitive clip, sent only by Add. */
    @Test fun aNewValueIsAPlainFieldCopiedOntoASensitiveClipAndSentOnlyByAdd() {
        ShadowLog.clear()
        val w = recording()
        show(secretList(), w)
        tag(ProfileTags.envNewName("claude-work")).performScrollTo().performTextReplacement("  ANTHROPIC_API_KEY ")
        val f = tag(ProfileTags.envNewInput("claude-work")).performScrollTo()
        f.performTextReplacement(SENTINEL)
        f.performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), w.writes)
        assertEquals(SENTINEL, editable(ProfileTags.envNewInput("claude-work")))
        NoCopyProbe.seed()
        f.performSemanticsAction(SemanticsActions.SetSelection) { it(0, SENTINEL.length, false) }
        compose.waitForIdle()
        f.performSemanticsAction(SemanticsActions.CopyText)
        compose.waitForIdle()
        assertEquals(SENTINEL, NoCopyProbe.clip())
        assertTrue(systemClipboard().primaryClipDescription?.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true)
        assertFalse(savedState().contains(SENTINEL))
        assertEquals(emptyList<Any>(), w.writes)
        tap(ProfileTags.envAdd("claude-work"))
        waitForWrites(w, 1)
        assertEquals(frame(gemini(env = SENTINEL), WORK.replace(",\"enabled\":true", ",\"enabled\":true,\"env\":{\"ANTHROPIC_API_KEY\":\"$SENTINEL\"}"), zai(token = SENTINEL_2)), w.frames().single())
        // Sent once, the draft is cleared.
        assertEquals("", editable(ProfileTags.envNewName("claude-work")))
        assertFalse(ShadowLog.getLogs().any { "${it.tag} ${it.msg}".contains(SENTINEL) })
    }

    @Test fun anEnvKeyRenamesAndRemovesAsTheWebDoes() {
        val w = answering()
        show(writer = w)
        typeAndDone(ProfileTags.envKey("zai", "ANTHROPIC_BASE_URL"), "ANTHROPIC_URL")
        waitForWrites(w, 1)
        assertEquals(
            frame(gemini(), WORK, zai().replace(",\"ANTHROPIC_BASE_URL\":\"https://api.z.ai/api/anthropic\"}", ",\"ANTHROPIC_URL\":\"https://api.z.ai/api/anthropic\"}")),
            w.frames()[0],
        )
        compose.waitForIdle()
        tap(ProfileTags.envRemove("zai", "ANTHROPIC_AUTH_TOKEN"))
        waitForWrites(w, 2)
        assertEquals(frame(gemini(), WORK, """{"id":"zai","extends":"claude","label":"Z.AI GLM","env":{"ANTHROPIC_URL":"https://api.z.ai/api/anthropic"},"dropEnv":["ANTHROPIC"],"enabled":false}"""), w.frames()[1])
    }

    @Test fun theGoldenValueIsObviouslyFake() {
        assertTrue(FAKE_KEY.startsWith("FAKE-"))
    }

    // ---- the model lists (issue #107) --------------------------------------------------------------

    @Test fun anUntouchedModelDraftIsNeverSent() {
        val w = recording()
        show(writer = w)
        tap(ProfileTags.modelAdd("claude-work", ModelList.Additional))
        tag(ProfileTags.draftId("claude-work", ModelList.Additional)).assertExists()
        assertEquals("model", editable(ProfileTags.draftId("claude-work", ModelList.Additional)))
        tag(ProfileTags.draftId("claude-work", ModelList.Additional)).performImeAction()
        compose.waitForIdle()
        assertFalse(exists(ProfileTags.draftId("claude-work", ModelList.Additional)))
        // Discard sends nothing.
        tap(ProfileTags.modelAdd("claude-work", ModelList.Additional))
        tag(ProfileTags.draftId("claude-work", ModelList.Additional)).performTextReplacement("real-id")
        tap(ProfileTags.draftDiscard("claude-work", ModelList.Additional))
        assertFalse(exists(ProfileTags.draftId("claude-work", ModelList.Additional)))
        assertEquals(emptyList<Any>(), w.writes)
        // ta-coik.17: any id other than the draft's own placeholder is committed, as the web's finalizeDraft
        // does (:491-497), a placeholder-shaped one without a label included (the server judges it).
        tap(ProfileTags.modelAdd("claude-work", ModelList.Additional))
        typeAndDone(ProfileTags.draftId("claude-work", ModelList.Additional), "model-7")
        waitForWrites(w, 1)
        assertEquals(frame(gemini(), WORK.replace(",\"enabled\":true", ",\"enabled\":true,\"additionalModels\":[{\"id\":\"model-7\"}]"), zai()), w.frames().single())
    }

    @Test fun aNamedModelDraftJoinsTheListAndTheRowsEditAsTheWebs() {
        val w = answering()
        show(writer = w)
        tap(ProfileTags.modelAdd("claude-work", ModelList.Models))
        tag(ProfileTags.draftId("claude-work", ModelList.Models)).performTextReplacement("claude-haiku-4")
        tag(ProfileTags.draftLabel("claude-work", ModelList.Models)).performTextReplacement("Haiku")
        tag(ProfileTags.draftLabel("claude-work", ModelList.Models)).performImeAction()
        waitForWrites(w, 1)
        val rows = """[{"id":"claude-opus-4","isDefault":true},{"id":"claude-sonnet-4","label":"Sonnet"}]"""
        assertEquals(frame(gemini(), WORK.replace(rows, """[{"id":"claude-opus-4","isDefault":true},{"id":"claude-sonnet-4","label":"Sonnet"},{"id":"claude-haiku-4","label":"Haiku"}]"""), zai()), w.frames()[0])
        compose.waitForIdle()
        flip(tag(ProfileTags.modelDefault("claude-work", ModelList.Models, 2)))
        waitForWrites(w, 2)
        assertEquals(frame(gemini(), WORK.replace(rows, """[{"id":"claude-opus-4"},{"id":"claude-sonnet-4","label":"Sonnet"},{"id":"claude-haiku-4","label":"Haiku","isDefault":true}]"""), zai()), w.frames()[1])
        compose.waitForIdle()
        tag(ProfileTags.modelDefault("claude-work", ModelList.Models, 2)).assertIsSelected()
        tap(ProfileTags.modelRemove("claude-work", ModelList.Models, 0))
        waitForWrites(w, 3)
        assertEquals(frame(gemini(), WORK.replace(rows, """[{"id":"claude-sonnet-4","label":"Sonnet"},{"id":"claude-haiku-4","label":"Haiku","isDefault":true}]"""), zai()), w.frames()[2])
    }

    // ---- ta-coik.17: any list is edited, as on the web ---------------------------------------------

    /** A list the server's validator would refuse is edited all the same (no read-only state, no web-console pointer): the write carries every entry as it came. */
    @Test fun aListTheAppCannotFullyReadIsEditedLikeTheWebs() {
        val w = recording()
        val odd = """{"id":"Bad_ID","extends":"codex","label":"Odd","enabled":true}"""
        show(ProfileFixtures.list("""[${gemini()},$odd]"""), w)
        assertFalse(exists("profiles-read-only"))
        assertFalse(texts().any { it.contains("web console") })
        tag(ProfileTags.Add).assertExists()
        tag(ProfileTags.remove("gemini")).assertExists()
        tag(ProfileTags.switch("gemini")).assertIsEnabled()
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).assertIsEnabled()
        typeAndDone(field("gemini", ProfileTags.LABEL), "G")
        waitForWrites(w, 1)
        assertEquals(frame(gemini().replace("\"label\":\"Gemini CLI\"", "\"label\":\"G\""), odd), w.frames()[0])
    }

    @Test fun serverTextIsDrawnSafely() {
        show(ProfileFixtures.list(profiles(gemini(command = """["gem\u202Eini"]"""), WORK, zai())))
        assertFalse(textOf(ProfileTags.subtitle("gemini")).contains('\u202E'))
        assertEquals("gemini", editable(field("gemini", ProfileTags.COMMAND)))
    }

    /** ta-coik.45: a profile's glyph is the harness it extends — its mark in its brand tile where verified. */
    @Test fun aProfileShowsTheMarkAndBrandTileOfTheHarnessItExtends() {
        show()
        fun colours(t: String): Map<Color, Int> {
            val px = tag(t).performScrollTo().captureToImage().toPixelMap()
            val out = HashMap<Color, Int>()
            for (x in 0 until px.width) for (y in 0 until px.height) out.merge(px[x, y], 1, Int::plus)
            return out
        }
        val work = colours(ProfileTags.glyph("claude-work"))
        assertTrue("extends claude: the terracotta tile", (work[Color(0xFFD97757)] ?: 0) > 1000)
        assertTrue("with the paper mark", (work[Color(0xFFFFFFFF)] ?: 0) > 50)
        // extends acp: no verified mark, so the web's letter on the neutral tile.
        val acp = colours(ProfileTags.glyph("gemini"))
        assertEquals(0, (acp[Color(0xFFD97757)] ?: 0) + (acp[Color(0xFF0D0D0D)] ?: 0))
        assertTrue("the raised neutral tile", (acp[tokensFor(TetherSkin.Studio).graphiteRaised] ?: 0) + (acp[tokensFor(TetherSkin.StudioDark).graphiteRaised] ?: 0) > 1000)
        compose.onNodeWithTag(ProfileTags.glyph("gemini"), useUnmergedTree = true).onChildren().filterToOne(hasText("A")).assertExists()
    }
}

/**
 * ta-q6p: a real activity recreation (a configuration change) commits nothing: not a half-typed
 * label, env value or command still focused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProfilesRecreationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val list = ProfileFixtures.list(profiles(gemini(env = SENTINEL), WORK, zai()))
    private val writer = RecordingProvidersWriter(newest = { list })

    private fun showIt() {
        val state = SettingsDialogState(SettingsTab.Engines)
        val binding = ProfileFixtures.binding(list, writer)
        compose.setContent { SettingsUnderTest(store.prefs, state, providers = binding) }
        compose.waitUntil(5_000) { state.draft != null }
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun recreate() {
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.writes)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), writer.writes)
    }

    /** An env value half typed and still focused. */
    @Test fun aHalfTypedValueIsNotSent() {
        showIt()
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performScrollTo().performClick()
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performTextReplacement("FAKE-ha")
        recreate()
    }

    /** A label half typed and still focused. */
    @Test fun aHalfTypedLabelIsNotSent() {
        showIt()
        tag(ProfileTags.field("zai", ProfileTags.LABEL)).performScrollTo().performClick()
        tag(ProfileTags.field("zai", ProfileTags.LABEL)).performTextReplacement("Hal")
        recreate()
    }

    /** A model draft with a real id typed, still focused. */
    @Test fun aModelDraftIsNotSent() {
        showIt()
        tag(ProfileTags.modelAdd("claude-work", ModelList.Models)).performScrollTo().performClick()
        compose.waitForIdle()
        tag(ProfileTags.draftId("claude-work", ModelList.Models)).performTextReplacement("claude-x")
        recreate()
    }

    /** A command half typed and still focused. */
    @Test fun aHalfTypedCommandIsNotSent() {
        showIt()
        tag(ProfileTags.field("gemini", ProfileTags.COMMAND)).performScrollTo().performClick()
        tag(ProfileTags.field("gemini", ProfileTags.COMMAND)).performTextReplacement("/tmp/x")
        recreate()
    }
}

/** A rotation (saved-instance-state restore) keeps the tab and draws the server's value again; nothing is saved of it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProfilesRotationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun aRotationDrawsTheServersValueAgain() {
        val restoration = StateRestorationTester(compose)
        val binding = ProfileFixtures.binding(ProfileFixtures.list(profiles(gemini(env = SENTINEL), WORK, zai())))
        restoration.setContent {
            val state = androidx.compose.runtime.saveable.rememberSaveable(saver = SettingsDialogState.Saver) { SettingsDialogState(SettingsTab.Engines) }
            SettingsUnderTest(store.prefs, state, providers = binding)
        }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsDialogTags.panel(SettingsTab.Engines), useUnmergedTree = true).assertExists()
        assertEquals(SENTINEL, compose.onNodeWithTag(ProfileTags.envInput("gemini", "GEMINI_API_KEY"), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
}

/**
 * ta-coik.20: a rotation keeps what was typed in a profile: a label (saved) and the add-env row's name
 * (saved); the add-env row's value, a secret, is in no saved state (it rides the activity's memory; see
 * [NodesRecreationTest] for the recreation that keeps such a value).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProfilesTypedStateRotationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun aRotationKeepsTheTypedLabelAndEnvNameAndSavesNoSecret() {
        val restoration = StateRestorationTester(compose)
        val binding = ProfileFixtures.binding(ProfileFixtures.list(profiles(gemini(env = SENTINEL), WORK, zai())))
        var saved: String? = null
        restoration.setContent {
            val state = androidx.compose.runtime.saveable.rememberSaveable(saver = SettingsDialogState.Saver) { SettingsDialogState(SettingsTab.Engines) }
            SettingsUnderTest(store.prefs, state, providers = binding)
            val registry = LocalSaveableStateRegistry.current
            androidx.compose.runtime.SideEffect { saved = registry?.performSave()?.toString() }
        }
        fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
        fun typed(t: String) = tag(t).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ProfileTags.field("zai", ProfileTags.LABEL), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tag(ProfileTags.field("zai", ProfileTags.LABEL)).performScrollTo().performTextReplacement("Hal-label")
        tag(ProfileTags.envNewName("gemini")).performScrollTo().performTextReplacement("MY_ENV")
        tag(ProfileTags.envNewInput("gemini")).performScrollTo().performTextReplacement("FAKE-new-secret-61f0")
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("Hal-label", typed(ProfileTags.field("zai", ProfileTags.LABEL)))
        assertEquals("MY_ENV", typed(ProfileTags.envNewName("gemini")))
        assertTrue("the saved state holds a typed secret", !saved.orEmpty().contains("FAKE-new-secret-61f0") && !saved.orEmpty().contains(SENTINEL))
        assertTrue("the label is in the saved state (the check can see strings)", saved.orEmpty().contains("Hal-label"))
    }
}
