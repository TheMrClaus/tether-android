package com.tether.app.ui.settings

import android.text.InputType
import androidx.activity.compose.setContent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.NodeActionResult
import com.tether.app.client.NodeRegistryRules
import com.tether.app.client.NodeRequestOutcome
import com.tether.app.ui.settings.NodeFixtures.CONSOLE
import com.tether.app.ui.settings.NodeFixtures.LIST
import com.tether.app.ui.settings.NodeFixtures.NOW
import com.tether.app.ui.settings.NodeFixtures.ORIGIN
import com.tether.app.ui.settings.NodeFixtures.OTHER_ORIGIN
import com.tether.app.ui.settings.NodeFixtures.REFUSAL
import com.tether.app.ui.settings.NodeFixtures.SENTINEL
import com.tether.app.ui.text.SafeText
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
import org.robolectric.shadows.ShadowLog

/**
 * T10.3: Settings > Nodes (components/nodes-settings.tsx at 887c222) through the semantics tree:
 * the web's order and words; server text drawn safely; add / probe / remove each sent once, by
 * its key only, to the drawing server, one at a time, its own answer shown (a refusal included,
 * the form kept, nothing retried); and the credential under slice 3's rules: masked by default,
 * the sentinel in no semantics node, log line, preference, saver or saved-state value while
 * masked, no copy or cut, the password keyboard, masked again on close, tab change, server switch,
 * rotation and ON_STOP, and never sent by anything but Add node.
 *
 * The v2 rule (ta-b72): an answer resumes the panel's request on the composition's dispatcher,
 * the test's here, and every read after a tap waits on the screen or the writer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class NodesBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Nodes)
    private var origin by mutableStateOf<String?>(ORIGIN)
    private var list by mutableStateOf(LIST)
    private var shown by mutableStateOf(true)
    private val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
    private val writer = RecordingNodesWriter()
    private var root: View? = null

    private fun show(owner: androidx.lifecycle.LifecycleOwner? = null, menus: MenuSpies? = null) {
        compose.setContent {
            val view = LocalView.current
            SideEffect { root = view }
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                if (shown) {
                    val actions = rememberNodesActions(writer)
                    val content = @androidx.compose.runtime.Composable {
                        WithMenuSpies(menus) { SettingsUnderTest(store.prefs, state, nodes = NodesBinding(list, origin, actions, CONSOLE, now = { NOW })) }
                    }
                    if (owner != null) {
                        CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) { content() }
                    } else {
                        content()
                    }
                }
            }
        }
        compose.waitUntil(5_000) { state.draft != null }
        waitFor(NodeTags.Section)
    }

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String) = compose.waitUntil(5_000) { exists(t) }
    private fun waitGone(t: String) = compose.waitUntil(5_000) { !exists(t) }
    private fun waitText(text: String) = compose.waitUntil(5_000) { texts().any { it.contains(text) } }
    private fun waitCalls(n: Int) = compose.waitUntil(5_000) { writer.calls.size == n }

    /** EVERY semantics property of every node in every root (text, editable AND raw input text, names, actions…). */
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

    private fun fieldText(t: String): String? = tag(t).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun description(t: String): String? = tag(t).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    private fun enabled(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
        ?.config?.contains(SemanticsProperties.Disabled) == false

    private fun savedState(): String = registry.performSave().toString() +
        with(SettingsDialogState.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(state) }.toString()

    private fun assertNowhere(leak: String) {
        val semantics = allSemantics()
        val saved = savedState()
        val logs = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        val prefs = store.stored().toString()
        assertFalse("semantics holds the credential", semantics.contains(leak))
        assertFalse("saved state holds the credential", saved.contains(leak))
        assertFalse("a log line holds the credential", logs.contains(leak))
        assertFalse("the preference store holds the credential", prefs.contains(leak))
    }

    private fun reveal() {
        tag(NodeTags.CredentialReveal).performScrollTo().performClick()
        waitFor(NodeTags.Credential)
    }

    private fun hide() {
        tag(NodeTags.CredentialReveal).performClick()
        waitFor(NodeTags.CredentialMasked)
    }

    /** Type [text] into the (revealed) credential, then mask it. */
    private fun typeCredential(text: String, keepRevealed: Boolean = false) {
        reveal()
        tag(NodeTags.Credential).performTextReplacement(text)
        compose.waitUntil(5_000) { tag(NodeTags.Credential).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text == text }
        if (!keepRevealed) hide()
    }

    private fun tapAdd() {
        compose.waitUntil(5_000) { enabled(NodeTags.Add) }
        tag(NodeTags.Add).performScrollTo().performClick()
    }

    private fun answered(ok: Boolean, nodeId: String?, message: String?) = NodeRequestOutcome.Answered(NodeActionResult(ok, nodeId, message, NOW))

    // ---- order and words ---------------------------------------------------------------------

    @Test fun theListIsDrawnInTheWebsOrderAndWords() {
        show()
        val all = texts()
        fun at(s: String) = all.indexOfFirst { it.contains(s) }.also { assertTrue("missing: $s", it >= 0) }
        // Heading, caption (with the mint command), the form, then the rows in the server's order.
        val order = listOf(
            "Nodes",
            NodesCopy.CAPTION_CODE,
            NodesCopy.ADD_HEADING,
            NodesCopy.LABEL_PLACEHOLDER,
            NodesCopy.BASE_URL_PLACEHOLDER,
            "Credential bundle, not set",
            NodesCopy.ADD,
            "Workstation",
            "Lab box",
            "Build server",
            "Old laptop",
            "New peer",
        ).map(::at)
        assertEquals(order.sorted(), order)
        assertTrue(texts().any { it == NodesCopy.CAPTION_HEAD + NodesCopy.CAPTION_CODE + NodesCopy.CAPTION_TAIL })
        // status · url · v<version> · last seen <relative>
        fun line(id: String) = tag(NodeTags.status(id)).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertEquals("Reachable · http://10.0.0.2:4173 · v0.14.2 · last seen 5m", line("node_ws"))
        assertEquals("Unreachable · https://lab.example.test · v0.13.0 · last seen 3h", line("node_lab"))
        assertEquals("Credential rejected · https://build.example.test · v0.14.0 · last seen 1d", line("node_rev"))
        assertEquals("Protocol mismatch · http://10.0.0.9:4173 · v0.9.0 · last seen 2d", line("node_old"))
        // Never probed: no version, no "last seen".
        assertEquals("Not probed yet · https://peer.example.test", line("node_new"))
        // The skew warning on the old peer only, the console's version named.
        assertTrue(exists(NodeTags.skew("node_old")))
        assertEquals(listOf("node_old"), LIST.map { it.nodeId }.filter { exists(NodeTags.skew(it)) })
        assertTrue(texts().contains(NodesCopy.skew(129, CONSOLE)))
        // r2 (verifier L3): announced at once, like the web's role="alert".
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Assertive, tag(NodeTags.skew("node_old")).fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        assertEquals("This node speaks protocol v129; this console speaks v137. Upgrade the older host before driving its sessions.", NodesCopy.skew(129, CONSOLE))
        // The keys are named for their node (the web's aria-label).
        assertEquals("Probe Workstation", description(NodeTags.probe("node_ws")))
        assertEquals("Remove Lab box", description(NodeTags.remove("node_lab")))
        // An empty credential: Add node is off; the empty note is not drawn while there are nodes.
        assertFalse(enabled(NodeTags.Add))
        assertFalse(exists(NodeTags.Empty))
        assertTrue(writer.calls.isEmpty())
    }

    @Test fun anEmptyRegistrySaysSoInTheWebsWords() {
        list = emptyList()
        show()
        waitFor(NodeTags.Empty)
        assertTrue(texts().contains(NodesCopy.EMPTY))
    }

    @Test fun serverTextIsDrawnSafely() {
        list = listOf(
            NodeFixtures.node("node_h", "Work‮station​", "https://good.example.test‮/lab", "reachable", version = "0.1‮"),
            NodeFixtures.node("node_blank", "", "https://blank.example.test", "unknown"),
        )
        show()
        val drawn = texts().joinToString("\n")
        assertTrue(drawn.contains("Workstation"))
        assertFalse("a bidi override is drawn raw", drawn.contains("‮"))
        assertFalse("a zero-width space is drawn raw", drawn.contains("​"))
        // The URL's hidden control is shown as a visible token, never applied.
        assertTrue(tag(NodeTags.status("node_h")).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.contains(SafeText.MARK))
        // A node without a label is named by its id.
        assertEquals("Probe node_blank", description(NodeTags.probe("node_blank")))
        assertEquals("Probe Workstation", description(NodeTags.probe("node_h")))
    }

    @Test fun signedOutNothingIsDrawnAndNothingCanBeSent() {
        origin = null
        show()
        assertFalse(exists(NodeTags.Add))
        assertFalse(exists(NodeTags.CredentialMasked))
        assertFalse(exists(NodeTags.row("node_ws")))
        assertTrue(writer.calls.isEmpty())
    }

    // ---- add -------------------------------------------------------------------------------

    @Test fun addSendsTheFormOnceMasksTheCredentialAndShowsItsOwnAnswer() {
        show()
        tag(NodeTags.Label).performTextReplacement(" Workstation ")
        tag(NodeTags.BaseUrl).performTextReplacement("http://10.0.0.2:4173")
        typeCredential(SENTINEL, keepRevealed = true)
        tapAdd()
        waitCalls(1)
        val call = writer.calls.single()
        assertEquals(NodeAction.Add, call.action)
        assertEquals(ORIGIN, call.origin)
        assertTrue("the typed credential went", call.credential!!.matches(SENTINEL))
        assertEquals(" Workstation ", call.label)
        assertEquals("http://10.0.0.2:4173", call.baseUrl)
        // Masked the moment it is sent; busy, so a second tap sends nothing.
        waitFor(NodeTags.CredentialMasked)
        assertFalse(allSemantics().contains(SENTINEL))
        waitText(NodesCopy.ADDING)
        assertFalse(enabled(NodeTags.Add))
        assertFalse(enabled(NodeTags.probe("node_ws")))
        tag(NodeTags.Add).performClick()
        compose.waitForIdle()
        assertEquals(1, writer.calls.size)
        // The server's answer for THIS request: shown, and the form lets go of the credential.
        writer.answer(answered(true, "node_new", "Reachable."))
        waitFor(NodeTags.Notice)
        waitText("Reachable.")
        compose.waitUntil(5_000) { description(NodeTags.CredentialMasked) == "Credential bundle, not set" }
        // The label and base URL stay, as on the web.
        assertEquals(" Workstation ", fieldText(NodeTags.Label))
        assertEquals("http://10.0.0.2:4173", fieldText(NodeTags.BaseUrl))
        assertEquals(1, writer.calls.size)
    }

    @Test fun aRefusalIsSaidTheFormIsKeptAndNothingIsRetried() {
        show()
        tag(NodeTags.Label).performTextReplacement("Workstation")
        typeCredential(SENTINEL)
        tapAdd()
        waitCalls(1)
        // tether #236 not deployed yet: one node-result ok:false for the phone's sign-in.
        writer.answer(answered(false, null, REFUSAL))
        waitFor(NodeTags.Notice)
        waitText(REFUSAL)
        // The form keeps what was typed (the credential still held, masked) ...
        assertEquals("Credential bundle, hidden", description(NodeTags.CredentialMasked))
        assertEquals("Workstation", fieldText(NodeTags.Label))
        assertNowhere(SENTINEL)
        // ... and nothing is sent again by itself, however long it waits.
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        assertEquals(1, writer.calls.size)
        // A deliberate second tap is the only way it goes again.
        tapAdd()
        waitCalls(2)
        assertTrue(writer.calls[1].credential!!.matches(SENTINEL))
    }

    @Test fun aRefusalAsAnErrorFrameIsShownCleaned() {
        show()
        typeCredential(SENTINEL)
        tapAdd()
        waitCalls(1)
        writer.answer(NodeRequestOutcome.ServerError("Skipping‮ this is refused.\n\nAsk the owner.⁦"))
        waitText("Skipping this is refused. Ask the owner.")
        assertFalse(texts().any { it.contains("‮") || it.contains("⁦") })
        assertEquals("Credential bundle, hidden", description(NodeTags.CredentialMasked))
    }

    @Test fun noAnswerALostLinkAndNotConnectedAreEachSaidAndNeverRetried() {
        show()
        typeCredential(SENTINEL)
        for ((n, outcome) in listOf(NodeRequestOutcome.TimedOut, NodeRequestOutcome.LinkLost, NodeRequestOutcome.NotSent).withIndex()) {
            tapAdd()
            waitCalls(n + 1)
            writer.answer(outcome)
            waitText(
                when (outcome) {
                    NodeRequestOutcome.TimedOut -> NodesCopy.TIMED_OUT
                    NodeRequestOutcome.LinkLost -> NodesCopy.LINK_LOST
                    else -> NodeRegistryRules.NOT_SENT_MESSAGE
                },
            )
            compose.mainClock.advanceTimeBy(60_000)
            compose.waitForIdle()
            assertEquals("nothing was retried", n + 1, writer.calls.size)
            // The credential is kept for a deliberate retry (the server may not hold the node).
            assertEquals("Credential bundle, hidden", description(NodeTags.CredentialMasked))
        }
    }

    @Test fun anAddThatRegisteredTheNodeLetsGoOfTheCredentialEvenWhenItsProbeFailed() {
        show()
        typeCredential(SENTINEL)
        tapAdd()
        waitCalls(1)
        writer.answer(answered(false, "node_x", "The node did not respond in time (timed out)."))
        waitText("The node did not respond in time (timed out).")
        compose.waitUntil(5_000) { description(NodeTags.CredentialMasked) == "Credential bundle, not set" }
    }

    // ---- probe and remove -------------------------------------------------------------------

    @Test fun probeAndRemoveSendTheirOwnNodeOneAtATimeAndRemoveHasNoConfirmation() {
        show()
        tag(NodeTags.probe("node_lab")).performScrollTo().performClick()
        waitCalls(1)
        assertEquals(NodeAction.Probe, writer.calls[0].action)
        assertEquals("node_lab", writer.calls[0].nodeId)
        assertEquals(ORIGIN, writer.calls[0].origin)
        waitText(NodesCopy.PROBING)
        // One at a time: every other key is off until the answer.
        assertFalse(enabled(NodeTags.remove("node_ws")))
        tag(NodeTags.remove("node_ws")).performClick()
        compose.waitForIdle()
        assertEquals(1, writer.calls.size)
        writer.answer(answered(false, "node_lab", "The node did not respond in time (timed out)."))
        waitText("The node did not respond in time (timed out).")
        // Remove goes at once, as on the web (no confirmation).
        compose.waitUntil(5_000) { enabled(NodeTags.remove("node_ws")) }
        tag(NodeTags.remove("node_ws")).performScrollTo().performClick()
        waitCalls(2)
        assertEquals(NodeAction.Remove, writer.calls[1].action)
        assertEquals("node_ws", writer.calls[1].nodeId)
        writer.answer(answered(true, "node_ws", "Node removed."))
        waitText("Node removed.")
        assertEquals(2, writer.calls.size)
    }

    @Test fun aTabChangeNeitherCancelsARequestNorLosesItsAnswer() {
        show()
        tag(NodeTags.probe("node_ws")).performScrollTo().performClick()
        waitCalls(1)
        state.tab = SettingsTab.General
        compose.waitForIdle()
        writer.answer(answered(true, "node_ws", "Reachable."))
        compose.waitForIdle()
        state.tab = SettingsTab.Nodes
        waitText("Reachable.")
        assertEquals(1, writer.calls.size)
    }

    // ---- the server the screen was drawn from ------------------------------------------------

    @Test fun aServerSwitchStartsEmptyAndTheOldServersAnswerIsNotShown() {
        show()
        tag(NodeTags.Label).performTextReplacement("For A")
        typeCredential(SENTINEL, keepRevealed = true)
        tapAdd()
        waitCalls(1)
        assertEquals(ORIGIN, writer.calls[0].origin)
        // Signed in to another server: its registry, an empty form, the credential gone and masked.
        list = listOf(NodeFixtures.LAB)
        origin = OTHER_ORIGIN
        waitGone(NodeTags.row("node_ws"))
        assertEquals("Credential bundle, not set", description(NodeTags.CredentialMasked))
        assertEquals("", fieldText(NodeTags.Label))
        assertFalse(allSemantics().contains(SENTINEL))
        // A's answer lands: not B's to show.
        writer.answer(answered(true, "node_a", "Reachable."))
        compose.waitForIdle()
        assertFalse(exists(NodeTags.Notice))
        // A request made now is B's.
        tag(NodeTags.probe("node_lab")).performScrollTo().performClick()
        waitCalls(2)
        assertEquals(OTHER_ORIGIN, writer.calls[1].origin)
    }

    // ---- the credential: slice 3's rules ----------------------------------------------------

    @Test fun theCredentialIsMaskedByDefaultAndInNoSemanticsLogPreferenceOrSavedState() {
        ShadowLog.clear()
        show()
        typeCredential(SENTINEL)
        compose.waitForIdle()
        // Masked: a fixed mask, named "hidden"; the value is in no property of any node (InputText included).
        assertEquals("Credential bundle, hidden", description(NodeTags.CredentialMasked))
        assertFalse(exists(NodeTags.Credential))
        assertNowhere(SENTINEL)
        assertFalse("the mask gives away the length", allSemantics().contains("•".repeat(SENTINEL.length)))
    }

    @Test fun revealShowsItInTheFieldOnlyAndHideMasksItAgain() {
        ShadowLog.clear()
        show()
        typeCredential(SENTINEL, keepRevealed = true)
        // Control: the probe sees a revealed value (so its absence elsewhere means something).
        assertTrue(allSemantics().contains(SENTINEL))
        assertFalse(savedState().contains(SENTINEL))
        assertFalse(store.stored().toString().contains(SENTINEL))
        assertFalse(ShadowLog.getLogs().any { "${it.tag} ${it.msg}".contains(SENTINEL) })
        hide()
        assertNowhere(SENTINEL)
        assertTrue(writer.calls.isEmpty())
    }

    @Test fun theRevealedFieldUsesThePasswordKeyboardAndTheLabelDoesNot() {
        show()
        reveal()
        tag(NodeTags.Credential).performClick()
        compose.waitForIdle()
        val info = EditorInfo()
        compose.runOnIdle { root!!.onCreateInputConnection(info) }
        assertEquals("password variation", InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
        // Control: a plain field of the same form is not a password field.
        tag(NodeTags.Label).performClick()
        compose.waitForIdle()
        val label = EditorInfo()
        compose.runOnIdle { root!!.onCreateInputConnection(label) }
        assertNotEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, label.inputType and InputType.TYPE_MASK_VARIATION)
    }

    @Test fun theRevealedCredentialCannotBeCopiedOrCut() {
        show()
        typeCredential(SENTINEL, keepRevealed = true)
        val field = tag(NodeTags.Credential)
        field.performClick()
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(0, SENTINEL.length, false) }
        compose.waitForIdle()
        assertTrue("the field offers copy", field.fetchSemanticsNode().config.contains(SemanticsActions.CopyText))
        field.performSemanticsAction(SemanticsActions.CopyText)
        compose.waitForIdle()
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(0, SENTINEL.length, false) }
        compose.waitForIdle()
        assertTrue("the field offers cut", field.fetchSemanticsNode().config.contains(SemanticsActions.CutText))
        field.performSemanticsAction(SemanticsActions.CutText)
        compose.waitForIdle()
        assertEquals("the cut deleted the credential", SENTINEL, fieldText(NodeTags.Credential))
        val clipboard = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.content.ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        assertFalse("the clipboard holds the credential", clip != null && (0 until clip.itemCount).any { clip.getItemAt(it).text?.contains(SENTINEL) == true })
    }

    /** ta-78a (1): the hardware copy and cut keys write nothing and delete nothing from the revealed credential. */
    @Config(shadows = [DeviceKeyCharacterMap::class])
    @Test fun theRevealedCredentialSurvivesTheCopyAndCutKeys() {
        show()
        typeCredential(SENTINEL, keepRevealed = true)
        val field = tag(NodeTags.Credential)
        field.performClick()
        compose.waitForIdle()
        NoCopyProbe.seed()
        for (keys in ClipKeys.entries) {
            field.selectAllAndPress(keys)
            compose.waitForIdle()
            assertEquals("$keys changed the credential", SENTINEL, fieldText(NodeTags.Credential))
        }
        assertEquals("something was written to the clipboard", NoCopyProbe.MARKER, NoCopyProbe.clip())
        field.assertCtrlVPastesTheClipboard(compose, "credential")
    }

    /** ta-78a r2: the revealed credential's real menus (long press, right click) offer nothing that reads it; the Label field is the control. */
    @Config(shadows = [NoMagnifier::class])
    @Test fun theRevealedCredentialsRealMenusOfferNothingThatReadsIt() {
        val menus = MenuSpies()
        show(menus = menus)
        tag(NodeTags.Label).performTextReplacement("Workstation peer")
        typeCredential(SENTINEL, keepRevealed = true)
        assertSecretMenus(compose, menus, tag(NodeTags.Label), tag(NodeTags.Credential), "credential")
    }

    /**
     * ta-oqx N1 / N2: a deletion followed at once by a Copy that fits the gap never brings the
     * deleted text back (the field no longer remembers its last edit at all).
     */
    @Test fun aDeletionThenACopyNeverBringsTheCredentialBack() {
        show()
        typeCredential(SENTINEL + SENTINEL, keepRevealed = true)
        val field = tag(NodeTags.Credential)
        field.performClick()
        field.performTextReplacement(SENTINEL)
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(0, SENTINEL.length, false) }
        field.performSemanticsAction(SemanticsActions.CopyText)
        compose.waitForIdle()
        assertEquals("the deleted text came back", SENTINEL, fieldText(NodeTags.Credential))
    }

    @Test fun nothingButAddNodeSendsTheCredential() {
        show()
        tag(NodeTags.Label).performTextReplacement("Workstation")
        tag(NodeTags.Label).performImeAction()
        typeCredential(SENTINEL, keepRevealed = true)
        // Focus moving away, masking, the keyboard's own action, leaving the tab, closing: nothing goes.
        tag(NodeTags.BaseUrl).performClick()
        tag(NodeTags.BaseUrl).performImeAction()
        hide()
        state.tab = SettingsTab.General
        compose.waitForIdle()
        state.tab = SettingsTab.Nodes
        waitFor(NodeTags.Section)
        shown = false
        compose.waitForIdle()
        shown = true
        waitFor(NodeTags.Section)
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        assertTrue(writer.calls.isEmpty())
    }

    @Test fun closingSettingsDropsTheCredential() {
        show()
        typeCredential(SENTINEL, keepRevealed = true)
        shown = false
        compose.waitForIdle()
        shown = true
        waitFor(NodeTags.CredentialMasked)
        assertEquals("Credential bundle, not set", description(NodeTags.CredentialMasked))
        assertNowhere(SENTINEL)
    }

    @Test fun leavingTheTabDropsTheCredential() {
        show()
        typeCredential(SENTINEL, keepRevealed = true)
        state.tab = SettingsTab.Advanced
        compose.waitForIdle()
        state.tab = SettingsTab.Nodes
        waitFor(NodeTags.CredentialMasked)
        assertEquals("Credential bundle, not set", description(NodeTags.CredentialMasked))
        assertNowhere(SENTINEL)
    }

    /**
     * r2 (security F1): a `nodes` broadcast landing between the press and the release of a key never
     * sends a request for another node: each row is keyed by its node, so the pressed key goes with
     * its row (and, moved from under the finger, the tap is dropped) instead of its slot taking the
     * node now drawn there.
     */
    @Test fun aBroadcastMidTapNeverSendsAnotherNodesRequest() {
        show()
        tag(NodeTags.probe("node_lab")).performScrollTo()
        compose.waitForIdle()
        val at = tag(NodeTags.probe("node_lab")).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(at) }
        // A new peer arrives at the top of the list while the finger is down.
        list = listOf(NodeFixtures.node("node_top", "Top peer", "https://top.example.test", "unknown")) + LIST
        waitFor(NodeTags.row("node_top"))
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertTrue("a tap meant for node_lab went to ${writer.calls.map { it.nodeId }}", writer.calls.all { it.nodeId == "node_lab" })
    }

    /** r2 (security F2): a credential already sent once (here refused) is CLEARED when the app stops, not only masked. */
    @Test fun aSentCredentialIsClearedWhenTheAppStops() {
        val owner = TestOwner()
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        show(owner)
        typeCredential(SENTINEL)
        tapAdd()
        waitCalls(1)
        writer.answer(answered(false, null, REFUSAL))
        waitText(REFUSAL)
        assertEquals("Credential bundle, hidden", description(NodeTags.CredentialMasked))
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.waitUntil(5_000) { description(NodeTags.CredentialMasked) == "Credential bundle, not set" }
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.waitForIdle()
        // Gone for good: revealing shows an empty field, and Add cannot send it again.
        reveal()
        assertEquals("", fieldText(NodeTags.Credential))
        assertFalse(enabled(NodeTags.Add))
        assertEquals(1, writer.calls.size)
    }

    /** r2 (security F2): a credential edited after its send has not been sent as it stands: the app stopping only masks it. */
    @Test fun aCredentialEditedAfterItsSendIsOnlyMaskedWhenTheAppStops() {
        val owner = TestOwner()
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        show(owner)
        typeCredential(SENTINEL)
        tapAdd()
        waitCalls(1)
        writer.answer(NodeRequestOutcome.TimedOut)
        waitText(NodesCopy.TIMED_OUT)
        typeCredential(SENTINEL + "-edited")
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.waitForIdle()
        assertEquals("Credential bundle, hidden", description(NodeTags.CredentialMasked))
        assertFalse(allSemantics().contains(SENTINEL))
    }

    /** r2 (verifier L2): a Cut (an accessibility action or a key; the menu offers none) neither copies nor deletes the credential. */
    @Test fun aCutLeavesTheCredentialWhereItIs() {
        show()
        typeCredential(SENTINEL, keepRevealed = true)
        val field = tag(NodeTags.Credential)
        field.performClick()
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(0, SENTINEL.length, false) }
        compose.waitForIdle()
        assertTrue("the field offers cut", field.fetchSemanticsNode().config.contains(SemanticsActions.CutText))
        field.performSemanticsAction(SemanticsActions.CutText)
        compose.waitForIdle()
        assertEquals("the cut deleted the credential", SENTINEL, fieldText(NodeTags.Credential))
        val clipboard = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.content.ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        assertFalse(clip != null && (0 until clip.itemCount).any { clip.getItemAt(it).text?.contains(SENTINEL) == true })
        // The field still edits normally afterwards.
        field.performTextReplacement("x")
        compose.waitUntil(5_000) { fieldText(NodeTags.Credential) == "x" }
    }

    @Test fun stoppingTheAppMasksTheCredential() {
        val owner = TestOwner()
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        show(owner)
        typeCredential(SENTINEL, keepRevealed = true)
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        waitFor(NodeTags.CredentialMasked)
        // Masked, still held for when the operator comes back (to paste, say), and in no semantics node.
        assertEquals("Credential bundle, hidden", description(NodeTags.CredentialMasked))
        assertFalse(allSemantics().contains(SENTINEL))
        assertTrue(writer.calls.isEmpty())
    }
}

/**
 * T10.3: a rotation (saved-instance-state restore) drops the credential and masks the field: the
 * form is plain `remember`, the reveal is not saved, and the restored state holds no credential.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class NodesRotationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun aRotationDropsTheCredentialAndSendsNothing() {
        val writer = RecordingNodesWriter()
        val restoration = StateRestorationTester(compose)
        var saved: String? = null
        restoration.setContent {
            val state = androidx.compose.runtime.saveable.rememberSaveable(saver = SettingsDialogState.Saver) { SettingsDialogState(SettingsTab.Nodes) }
            val actions = rememberNodesActions(writer)
            SettingsUnderTest(store.prefs, state, nodes = NodesBinding(LIST, ORIGIN, actions, CONSOLE, now = { NOW }))
            val registry = LocalSaveableStateRegistry.current
            SideEffect { saved = registry?.performSave()?.toString() }
        }
        fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(NodeTags.CredentialReveal, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tag(NodeTags.CredentialReveal).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(NodeTags.Credential, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tag(NodeTags.Credential).performTextReplacement(SENTINEL)
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(NodeTags.CredentialMasked, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // The tab is restored (saved state); the reveal and the credential are not.
        tag(SettingsDialogTags.panel(SettingsTab.Nodes)).assertExists()
        tag(NodeTags.Credential).assertDoesNotExist()
        assertEquals("Credential bundle, not set", tag(NodeTags.CredentialMasked).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString())
        assertFalse("the saved state holds the credential", saved.orEmpty().contains(SENTINEL))
        assertTrue(writer.calls.isEmpty())
    }
}

/** T10.3: a real activity recreation (a configuration change) sends nothing, the credential half typed or not. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class NodesRecreationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    /**
     * r2 (verifier L1): the recreated activity draws Settings again (its own content, set as it is
     * created, like an app's onCreate, with the dialog's saved state restored), so the test sees the
     * tab after the recreation: the Nodes tab back (saved state), the form empty, the credential
     * masked and gone, nothing sent before, during or after.
     */
    @Test fun recreatingTheActivitySendsNothingAndStartsTheFormEmpty() {
        val writer = RecordingNodesWriter()
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            val state = androidx.compose.runtime.saveable.rememberSaveable(saver = SettingsDialogState.Saver) { SettingsDialogState(SettingsTab.Nodes) }
            val actions = rememberNodesActions(writer)
            SettingsUnderTest(store.prefs, state, nodes = NodesBinding(LIST, ORIGIN, actions, CONSOLE, now = { NOW }))
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
            fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
            fun one(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().size == 1
            compose.waitUntil(5_000) { one(NodeTags.Section) }
            tag(NodeTags.Label).performTextReplacement("Workstation")
            tag(NodeTags.CredentialReveal).performScrollTo().performClick()
            tag(NodeTags.Credential).performClick()
            tag(NodeTags.Credential).performTextReplacement(SENTINEL)
            compose.waitForIdle()
            assertTrue(writer.calls.isEmpty())
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5_000) { recreated.get() == 1 && one(NodeTags.Section) && one(NodeTags.CredentialMasked) }
            tag(NodeTags.Credential).assertDoesNotExist()
            assertEquals("Credential bundle, not set", tag(NodeTags.CredentialMasked).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString())
            assertEquals("", tag(NodeTags.Label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
            compose.waitForIdle()
            assertTrue(writer.calls.isEmpty())
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}
