package com.tether.app.ui.settings

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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
 * overwritten); the owner's rules: a command or home only through a confirmation that shows it, and
 * env values masked, revealed per row, never in the semantics tree, a log, the preference store or
 * saved state while masked, re-masked on close, tab change, server switch, rotation and ON_STOP,
 * no copy or cut, sent only on Done.
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

    private fun show(list: ProvidersList? = ProfileFixtures.list(), writer: ProvidersWriter = ProvidersWriter.None, origin: String? = ORIGIN) {
        providers = ProvidersBinding(list, origin, writer)
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                if (shown) SettingsUnderTest(store.prefs, state, providers = providers)
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

    private fun arm() {
        compose.mainClock.advanceTimeBy(CONFIRM_ARM_MS + 50)
        compose.waitForIdle()
    }

    private fun confirm() {
        arm()
        tag(ProfileTags.Confirm).performClick()
    }

    private fun waitForWrites(w: RecordingProvidersWriter, n: Int) = compose.waitUntil(5_000) { w.writes.size >= n }

    private fun parts(t: String) = SafeText.original(textOf(t)).split('\n')

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
        // No slot is left on this tab.
        assertEquals(0, compose.onAllNodesWithTag(SettingsTags.ComingSoon).fetchSemanticsNodes().size)
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

    @Test fun theOrderFieldTakesOnlyWholeNumbers() {
        val w = recording()
        show(writer = w)
        val order = field("gemini", ProfileTags.ORDER)
        tag(order).performScrollTo().performTextReplacement("6e4")
        compose.waitForIdle()
        assertEquals("1", editable(order))
        tag(order).performTextReplacement("-5")
        compose.waitForIdle()
        assertEquals("1", editable(order))
        typeAndDone(order, "12")
        waitForWrites(w, 1)
        assertEquals(frame(gemini().replace("\"order\":1", "\"order\":12"), WORK, zai()), w.frames().single())
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

    /** The client refuses a write built from a list older than its newest one (the race the binding cannot see). */
    @Test fun aWriteFromAnOlderListIsRefused() {
        var clientNewest = ProfileFixtures.list(generation = 1)
        val w = RecordingProvidersWriter(newest = { clientNewest })
        show(ProfileFixtures.list(generation = 1), w)
        // The client already holds generation 2 (a broadcast the composition has not drawn yet), and
        // the binding reads the composed list (no fresh hook): the write is refused, nothing sent.
        clientNewest = ProfileFixtures.list(profiles(gemini(), WORK, zai().replace("\"enabled\":false", "\"enabled\":true")), generation = 2)
        typeAndDone(field("claude-work", ProfileTags.LABEL), "Work")
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), w.writes)
        assertEquals(listOf("built from an older list"), w.refused)
    }

    // ---- what a profile runs: confirmed, never sent otherwise -------------------------------------

    @Test fun aCommandIsSentOnlyAfterItsConfirmation() {
        val w = answering()
        show(writer = w)
        typeAndDone(field("gemini", ProfileTags.COMMAND), "/opt/gemini/bin/gemini   --experimental-acp -v")
        assertEquals("Done only asks", emptyList<Any>(), w.writes)
        tag(ProfileTags.ConfirmSheet).assertExists()
        assertTrue(texts().contains("Change the Gemini CLI command?"))
        assertEquals(listOf("/opt/gemini/bin/gemini", "--experimental-acp", "-v"), parts(ProfileTags.ConfirmNew))
        assertEquals(listOf("gemini", "--experimental-acp"), parts(ProfileTags.ConfirmNow))
        // The double space was read as one break: disclosed.
        tag(ProfileTags.ConfirmNote).assertExists()
        assertTrue(texts().contains(ProfileRows.SPLIT))
        confirm()
        waitForWrites(w, 1)
        compose.waitForIdle()
        assertEquals(frame(gemini(command = """["/opt/gemini/bin/gemini","--experimental-acp","-v"]"""), WORK, zai()), w.frames().single())
        assertTrue(w.writes.single().first.isConfirmed)
        assertFalse(exists(ProfileTags.ConfirmSheet))
        assertEquals("/opt/gemini/bin/gemini --experimental-acp -v", editable(field("gemini", ProfileTags.COMMAND)))
    }

    @Test fun aHomeIsConfirmedTooAndAnEmptiedOneIsDropped() {
        val w = answering()
        show(writer = w)
        typeAndDone(field("claude-work", ProfileTags.HOME), "")
        assertTrue(texts().contains("Change the Claude Code (work) home?"))
        assertEquals(ProfileRows.emptyValue(ProfileRunsReview("claude-work", "", "claude", true, emptyList(), emptyList(), false)), textOf(ProfileTags.ConfirmNew))
        assertEquals("/srv/homes/claude-work", SafeText.original(textOf(ProfileTags.ConfirmNow)))
        confirm()
        waitForWrites(w, 1)
        assertEquals(frame(gemini(), WORK.replace(",\"homeDir\":\"/srv/homes/claude-work\"", ""), zai()), w.frames().single())
        typeAndDone(field("zai", ProfileTags.HOME), "  /srv/homes/zai ")
        tag(ProfileTags.ConfirmNote).assertExists()
        assertEquals(listOf("/srv/homes/zai"), parts(ProfileTags.ConfirmNew))
        confirm()
        waitForWrites(w, 2)
        assertEquals(
            frame(gemini(), WORK.replace(",\"homeDir\":\"/srv/homes/claude-work\"", ""), zai().replace(",\"enabled\":false", ",\"enabled\":false,\"homeDir\":\"/srv/homes/zai\"")),
            w.frames()[1],
        )
    }

    @Test fun cancelBackAndATapOutsideSendNothing() {
        val w = recording()
        show(writer = w)
        val command = field("gemini", ProfileTags.COMMAND)
        typeAndDone(command, "/tmp/other")
        tag(ProfileTags.Cancel).performClick()
        compose.waitForIdle()
        assertFalse(exists(ProfileTags.ConfirmSheet))
        assertEquals("/tmp/other", editable(command))
        tag(ProfileTags.unsaved("gemini", ProfileTags.COMMAND)).assertExists()
        tag(command).performImeAction()
        compose.waitForIdle()
        tag(ProfileTags.ConfirmSheet).assertExists()
        Espresso.pressBack()
        compose.waitForIdle()
        assertFalse(exists(ProfileTags.ConfirmSheet))
        tag(command).performImeAction()
        compose.waitForIdle()
        tag(ProfileTags.ConfirmSheet).assertExists()
        compose.onAllNodes(isRoot())[1].performTouchInput { click(Offset(4f, 4f)) }
        compose.waitForIdle()
        assertFalse(exists(ProfileTags.ConfirmSheet))
        shown = false
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), w.writes)
    }

    @Test fun aTapBeforeTheConfirmationArmsSendsNothing() {
        val w = answering()
        show(writer = w)
        typeAndDone(field("gemini", ProfileTags.COMMAND), "/opt/g")
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(CONFIRM_ARM_MS - 150)
        tag(ProfileTags.Confirm).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnUiThread { flushWrites() }
        compose.mainClock.advanceTimeBy(32)
        assertEquals(emptyList<Any>(), w.writes)
        tag(ProfileTags.ConfirmSheet).assertExists()
        compose.mainClock.advanceTimeBy(200)
        tag(ProfileTags.Confirm).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnUiThread { flushWrites() }
        compose.mainClock.autoAdvance = true
        waitForWrites(w, 1)
        assertEquals(frame(gemini(command = """["/opt/g"]"""), WORK, zai()), w.frames().single())
    }

    @Test fun aDoubleTapOnChangeSendsOnce() {
        val w = recording()
        show(writer = w)
        typeAndDone(field("gemini", ProfileTags.COMMAND), "/opt/g")
        arm()
        val action = tag(ProfileTags.Confirm).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            action()
            action()
            flushWrites()
        }
        waitForWrites(w, 1)
        compose.waitForIdle()
        assertEquals(1, w.writes.size)
    }

    /** Another client changes the profile's env mid-confirmation: the confirmed write keeps it. */
    @Test fun anEnvChangeMidConfirmationIsKeptByTheConfirmedWrite() {
        val w = recording()
        show(writer = w)
        typeAndDone(field("gemini", ProfileTags.COMMAND), "/opt/g")
        broadcast(profiles(gemini(env = "FAKE-rotated-key"), WORK, zai()))
        tag(ProfileTags.ConfirmSheet).assertExists()
        confirm()
        waitForWrites(w, 1)
        assertEquals(frame(gemini(env = "FAKE-rotated-key", command = """["/opt/g"]"""), WORK, zai()), w.frames().single())
    }

    /** Another client changes the very command mid-confirmation: the confirmation goes, nothing is sent. */
    @Test fun aCommandChangedElsewhereMidConfirmationSendsNothing() {
        val w = recording()
        show(writer = w)
        typeAndDone(field("gemini", ProfileTags.COMMAND), "/opt/g")
        broadcast(profiles(gemini(command = """["/usr/bin/gemini"]"""), WORK, zai()))
        assertFalse(exists(ProfileTags.ConfirmSheet))
        // ... and in the same frame as the tap: still nothing.
        typeAndDone(field("gemini", ProfileTags.COMMAND), "/opt/h")
        arm()
        val action = tag(ProfileTags.Confirm).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            val next = (providers.list?.generation ?: 0) + 1
            providers = providers.copy(list = ProfileFixtures.list(profiles(gemini(command = """["/usr/bin/other"]"""), WORK, zai()), next))
            action()
            flushWrites()
        }
        compose.waitForIdle()
        assertFalse(exists(ProfileTags.ConfirmSheet))
        assertEquals(emptyList<Any>(), w.writes)
    }

    @Test fun theConfirmationGoesWithTheListOrTheServer() {
        val w = recording()
        show(writer = w)
        typeAndDone(field("gemini", ProfileTags.COMMAND), "/tmp/a")
        providers = providers.copy(list = null)
        compose.waitForIdle()
        assertFalse(exists(ProfileTags.ConfirmSheet))
        providers = ProfileFixtures.binding(writer = w)
        compose.waitForIdle()
        typeAndDone(field("gemini", ProfileTags.COMMAND), "/tmp/b")
        tag(ProfileTags.ConfirmSheet).assertExists()
        providers = ProfileFixtures.binding(origin = ServerFixtures.OTHER_ORIGIN, writer = w)
        compose.waitForIdle()
        assertFalse(exists(ProfileTags.ConfirmSheet))
        assertEquals("gemini --experimental-acp", editable(field("gemini", ProfileTags.COMMAND)))
        assertEquals(emptyList<Any>(), w.writes)
    }

    @Test fun aCommandOrHomeIsNeverSentOnAFocusLossATabChangeOrAClose() {
        val w = recording()
        show(writer = w)
        val command = tag(field("gemini", ProfileTags.COMMAND)).performScrollTo()
        command.performClick()
        command.performTextReplacement("/tmp/half")
        tag(field("gemini", ProfileTags.HOME)).performScrollTo().performClick()
        tag(field("gemini", ProfileTags.HOME)).performTextReplacement("/tmp/home")
        tag(field("gemini", ProfileTags.LABEL)).performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(exists(ProfileTags.ConfirmSheet))
        state.tab = SettingsTab.General
        compose.waitForIdle()
        state.tab = SettingsTab.Engines
        compose.waitForIdle()
        assertEquals("gemini --experimental-acp", editable(field("gemini", ProfileTags.COMMAND)))
        tag(field("gemini", ProfileTags.COMMAND)).performScrollTo().performTextReplacement("/tmp/again")
        shown = false
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), w.writes)
    }

    @Test fun aSpoofedCommandIsShownVisiblyAndSentExactly() {
        val w = answering()
        show(writer = w)
        // A bidi override and a zero-width space are not JavaScript whitespace: they stay in their part,
        // shown as tokens. A no-break space IS (the web's `/\s+/`): it splits, and the note says so.
        val typed = "/srv/\u202Egnp.exe\u200B/gemini --x\u00A0y"
        typeAndDone(field("gemini", ProfileTags.COMMAND), typed)
        val next = textOf(ProfileTags.ConfirmNew)
        assertFalse(next.contains('\u202E'))
        assertTrue(next.contains("\u27E8U+202E\u27E9"))
        assertTrue(next.contains("\u27E8U+200B\u27E9"))
        assertEquals(listOf("/srv/\u202Egnp.exe\u200B/gemini", "--x", "y"), parts(ProfileTags.ConfirmNew))
        tag(ProfileTags.ConfirmNote).assertExists()
        confirm()
        waitForWrites(w, 1)
        assertEquals(frame(gemini(command = """["/srv/\u202Egnp.exe\u200B/gemini","--x","y"]"""), WORK, zai()), w.frames().single())
    }

    @Test fun aCommandPastTheServersLimitCannotBeTyped() {
        show()
        val command = field("gemini", ProfileTags.COMMAND)
        tag(command).performScrollTo().performTextReplacement(List(33) { "p" }.joinToString(" "))
        compose.waitForIdle()
        assertEquals("gemini --experimental-acp", editable(command))
        tag(command).performTextReplacement("x".repeat(257))
        compose.waitForIdle()
        assertEquals("gemini --experimental-acp", editable(command))
    }

    /** The choke point (r2 of slice 4, here): no plain edit can change what a profile runs, and the binding refuses a write that would. */
    @Test fun noPlainEditChangesWhatAProfileRuns() {
        val list = ProfileFixtures.list()
        val edits = listOf(
            ProfileEdit.Enabled("gemini"), ProfileEdit.Label("gemini", "G"), ProfileEdit.Rename("gemini", "g2"), ProfileEdit.Extends("gemini", "codex"),
            ProfileEdit.EnvKey("gemini", "GEMINI_API_KEY", "K"), ProfileEdit.EnvValue("gemini", "GEMINI_API_KEY", SecretText("v")),
            ProfileEdit.EnvRemove("gemini", "GEMINI_API_KEY"), ProfileEdit.EnvAdd("gemini", "N", SecretText("v")), ProfileEdit.DropEnv("gemini", "X"),
            ProfileEdit.DisallowedTools("claude-work", "Task"), ProfileEdit.Order("gemini", "3"), ProfileEdit.VerifiedThrough("gemini", "1.0"),
            ProfileEdit.Model("claude-work", ModelList.Models, com.tether.app.client.ModelOp.Remove(0, "claude-opus-4")), ProfileEdit.Add, ProfileEdit.Remove("zai"),
        )
        for (edit in edits) {
            val write = ProvidersPatch.write(list, edit)!!
            assertFalse("$edit", write.isConfirmed)
            assertEquals("$edit", null, ProvidersPatch.refusal(write, list))
            for (p in write.profiles) {
                val id = (p["id"] as JsonPrimitive).content
                val before = list.profile(if (edit is ProfileEdit.Rename && id == "g2") "gemini" else id)
                assertEquals("$edit", before?.command?.let { c -> JsonArray(c.map(::JsonPrimitive)) }, p["command"])
                assertEquals("$edit", before?.homeDir?.let(::JsonPrimitive), p["homeDir"])
            }
        }
        // A forged write reaching the binding's writer is refused there (the client applies the same rule).
        val w = RecordingProvidersWriter(newest = { list })
        val forged = ProvidersPatch.confirmed(list, com.tether.app.client.ProfileRunsEdit.Command("gemini", listOf("/opt/ok")))!!
        assertTrue(w.setProviders(forged, ORIGIN))
        val stale = ProvidersPatch.confirmed(ProfileFixtures.list(generation = 0), com.tether.app.client.ProfileRunsEdit.Command("gemini", listOf("/opt/ok")))!!
        assertFalse(w.setProviders(stale, ORIGIN))
    }

    // ---- the env values (secrets) ----------------------------------------------------------------

    private fun secretList() = ProfileFixtures.list(profiles(gemini(env = SENTINEL), WORK, zai(token = SENTINEL_2)))

    @Test fun envValuesAreMaskedByDefaultAndInNoSemanticsLogPreferenceOrSavedState() {
        ShadowLog.clear()
        show(secretList())
        tag(ProfileTags.envMasked("gemini", "GEMINI_API_KEY")).performScrollTo().assertExists()
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).assertDoesNotExist()
        assertTrue(texts().contains("Value for GEMINI_API_KEY, hidden"))
        assertTrue(texts().contains("Reveal Value for GEMINI_API_KEY"))
        // Keys are not secret: drawn in their own fields.
        assertEquals("GEMINI_API_KEY", editable(ProfileTags.envKey("gemini", "GEMINI_API_KEY")))
        assertNowhere(SENTINEL, SENTINEL_2)
        assertFalse(allSemantics().contains("•".repeat(SENTINEL.length)))
    }

    @Test fun revealShowsOnlyThatValueAndHideMasksItAgain() {
        ShadowLog.clear()
        show(secretList())
        tap(ProfileTags.envReveal("gemini", "GEMINI_API_KEY"))
        assertEquals(SENTINEL, editable(ProfileTags.envInput("gemini", "GEMINI_API_KEY")))
        assertTrue(allSemantics().contains(SENTINEL))
        assertFalse(allSemantics().contains(SENTINEL_2))
        assertFalse(savedState().contains(SENTINEL))
        assertFalse(store.stored().toString().contains(SENTINEL))
        assertFalse(ShadowLog.getLogs().any { "${it.tag} ${it.msg}".contains(SENTINEL) })
        tap(ProfileTags.envReveal("gemini", "GEMINI_API_KEY"))
        tag(ProfileTags.envMasked("gemini", "GEMINI_API_KEY")).assertExists()
        assertNowhere(SENTINEL, SENTINEL_2)
    }

    @Test fun aRevealedValueIsSentOnlyByDone() {
        val w = recording()
        show(secretList(), w)
        tap(ProfileTags.envReveal("gemini", "GEMINI_API_KEY"))
        val input = tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY"))
        input.performClick()
        input.performTextReplacement("FAKE-half")
        // Focus moves away: nothing.
        tag(field("gemini", ProfileTags.LABEL)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), w.writes)
        // Done sends the value exactly (not trimmed), and only that value changes.
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performScrollTo().performTextReplacement(" FAKE-new ")
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performImeAction()
        waitForWrites(w, 1)
        assertEquals(frame(gemini(env = " FAKE-new "), WORK, zai(token = SENTINEL_2)), w.frames().single())
    }

    @Test fun aHalfTypedValueIsNotSentWhenSettingsCloses() {
        val w = recording()
        show(secretList(), w)
        tap(ProfileTags.envReveal("gemini", "GEMINI_API_KEY"))
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performClick()
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performTextReplacement("FAKE-ha")
        compose.waitForIdle()
        shown = false
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), w.writes)
    }

    @Test fun closingLeavingTheTabOrSwitchingServerMasksAgain() {
        show(secretList())
        tap(ProfileTags.envReveal("gemini", "GEMINI_API_KEY"))
        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()
        tag(ProfileTags.envMasked("gemini", "GEMINI_API_KEY")).assertExists()
        assertNowhere(SENTINEL, SENTINEL_2)
        tap(ProfileTags.envReveal("gemini", "GEMINI_API_KEY"))
        state.tab = SettingsTab.General
        compose.waitForIdle()
        state.tab = SettingsTab.Engines
        compose.waitForIdle()
        tag(ProfileTags.envMasked("gemini", "GEMINI_API_KEY")).assertExists()
        assertNowhere(SENTINEL, SENTINEL_2)
        tap(ProfileTags.envReveal("gemini", "GEMINI_API_KEY"))
        providers = providers.copy(origin = ServerFixtures.OTHER_ORIGIN)
        compose.waitForIdle()
        tag(ProfileTags.envMasked("gemini", "GEMINI_API_KEY")).assertExists()
        assertFalse(allSemantics().contains(SENTINEL))
    }

    @Test fun stoppingTheAppMasksAgain() {
        val owner = TestOwner()
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        providers = ProfileFixtures.binding(secretList())
        compose.setContent {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                SettingsUnderTest(store.prefs, state, providers = providers)
            }
        }
        compose.waitUntil(5_000) { state.draft != null }
        tap(ProfileTags.envReveal("gemini", "GEMINI_API_KEY"))
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).assertExists()
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.waitForIdle()
        tag(ProfileTags.envMasked("gemini", "GEMINI_API_KEY")).assertExists()
        assertFalse(allSemantics().contains(SENTINEL))
    }

    @Test fun aRevealedValueCannotBeCopiedOrCut() {
        show(secretList())
        tap(ProfileTags.envReveal("gemini", "GEMINI_API_KEY"))
        val f = tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY"))
        f.performClick()
        f.performSemanticsAction(SemanticsActions.SetSelection) { it(0, SENTINEL.length, false) }
        compose.waitForIdle()
        assertTrue("the field offers copy", f.fetchSemanticsNode().config.contains(SemanticsActions.CopyText))
        f.performSemanticsAction(SemanticsActions.CopyText)
        compose.waitForIdle()
        f.performSemanticsAction(SemanticsActions.SetSelection) { it(0, SENTINEL.length, false) }
        compose.waitForIdle()
        if (f.fetchSemanticsNode().config.contains(SemanticsActions.CutText)) f.performSemanticsAction(SemanticsActions.CutText)
        compose.waitForIdle()
        val clipboard = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.content.ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        assertFalse("the clipboard holds the value", clip != null && (0 until clip.itemCount).any { clip.getItemAt(it).text?.contains(SENTINEL) == true })
    }

    @Test fun aNewValueIsMaskedUntilRevealedAndSentOnlyByAdd() {
        ShadowLog.clear()
        val w = recording()
        show(secretList(), w)
        tag(ProfileTags.envNewMasked("claude-work")).performScrollTo().assertExists()
        tag(ProfileTags.envNewName("claude-work")).performTextReplacement("  ANTHROPIC_API_KEY ")
        tap(ProfileTags.envNewReveal("claude-work"))
        tag(ProfileTags.envNewInput("claude-work")).performTextReplacement(SENTINEL)
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), w.writes)
        // Masked again with the draft in it: the draft is not in the semantics tree.
        tap(ProfileTags.envNewReveal("claude-work"))
        assertTrue(texts().contains("New environment variable value, hidden"))
        assertFalse(allSemantics().contains(SENTINEL))
        assertFalse(savedState().contains(SENTINEL))
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
        // A placeholder-shaped id without a label is not a real one either.
        tap(ProfileTags.modelAdd("claude-work", ModelList.Additional))
        typeAndDone(ProfileTags.draftId("claude-work", ModelList.Additional), "model-7")
        compose.waitForIdle()
        // Discard sends nothing.
        tap(ProfileTags.modelAdd("claude-work", ModelList.Additional))
        tag(ProfileTags.draftId("claude-work", ModelList.Additional)).performTextReplacement("real-id")
        tap(ProfileTags.draftDiscard("claude-work", ModelList.Additional))
        assertFalse(exists(ProfileTags.draftId("claude-work", ModelList.Additional)))
        assertEquals(emptyList<Any>(), w.writes)
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

    // ---- a list the app cannot read exactly -------------------------------------------------------

    @Test fun anUnreadableListIsShownButNeverWritten() {
        val w = recording()
        show(ProfileFixtures.list("""[${gemini()},{"id":"Bad_ID","extends":"codex","label":"Odd","enabled":true}]"""), w)
        tag(ProfileTags.ReadOnly).assertExists()
        assertFalse(exists(ProfileTags.Add))
        assertFalse(exists(ProfileTags.remove("gemini")))
        tag(field("gemini", ProfileTags.LABEL)).assertIsNotEnabled()
        tag(ProfileTags.switch("gemini")).assertIsNotEnabled()
        // The values can still be read (revealed), but nothing can be written.
        tap(ProfileTags.envReveal("gemini", "GEMINI_API_KEY"))
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).assertIsNotEnabled()
        assertEquals(emptyList<Any>(), w.writes)
    }

    @Test fun serverTextIsDrawnSafely() {
        show(ProfileFixtures.list(profiles(gemini(command = """["gem\u202Eini"]"""), WORK, zai())))
        assertFalse(textOf(ProfileTags.subtitle("gemini")).contains('\u202E'))
        assertEquals("gemini", editable(field("gemini", ProfileTags.COMMAND)))
    }
}

/**
 * ta-q6p: a real activity recreation (a configuration change) commits nothing: not a half-typed
 * label, not a revealed env value typed but not Done, not a command waiting in its confirmation.
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

    /** A revealed env value typed but not Done, and a label half typed and still focused. */
    @Test fun aHalfTypedValueOrLabelIsNotSent() {
        showIt()
        tag(ProfileTags.envReveal("gemini", "GEMINI_API_KEY")).performScrollTo().performClick()
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performClick()
        tag(ProfileTags.envInput("gemini", "GEMINI_API_KEY")).performTextReplacement("FAKE-ha")
        // Focus moves to the label (the secret's blur sends nothing), which is half typed and keeps focus.
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

    /** A command waiting in its confirmation. */
    @Test fun aCommandWaitingForItsConfirmationIsNotSent() {
        showIt()
        tag(ProfileTags.field("gemini", ProfileTags.COMMAND)).performScrollTo().performTextReplacement("/tmp/x")
        tag(ProfileTags.field("gemini", ProfileTags.COMMAND)).performImeAction()
        compose.waitForIdle()
        tag(ProfileTags.ConfirmSheet).assertExists()
        recreate()
    }
}

/** A rotation (saved-instance-state restore) masks a revealed env value: the reveal is never saved state. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProfilesRotationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun aRotationMasksTheValue() {
        val restoration = StateRestorationTester(compose)
        val binding = ProfileFixtures.binding(ProfileFixtures.list(profiles(gemini(env = SENTINEL), WORK, zai())))
        restoration.setContent {
            val state = androidx.compose.runtime.saveable.rememberSaveable(saver = SettingsDialogState.Saver) { SettingsDialogState(SettingsTab.Engines) }
            SettingsUnderTest(store.prefs, state, providers = binding)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(ProfileTags.envReveal("gemini", "GEMINI_API_KEY"), useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ProfileTags.envInput("gemini", "GEMINI_API_KEY"), useUnmergedTree = true).assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsDialogTags.panel(SettingsTab.Engines), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ProfileTags.envMasked("gemini", "GEMINI_API_KEY"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ProfileTags.envInput("gemini", "GEMINI_API_KEY"), useUnmergedTree = true).assertDoesNotExist()
    }
}
