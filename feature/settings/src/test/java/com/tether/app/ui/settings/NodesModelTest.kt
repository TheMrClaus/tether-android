package com.tether.app.ui.settings

import com.tether.app.client.NodeActionResult
import com.tether.app.client.NodeRegistryRules
import com.tether.app.client.NodeRequestOutcome
import com.tether.app.ui.settings.NodeFixtures.CONSOLE
import com.tether.app.ui.settings.NodeFixtures.ORIGIN
import com.tether.app.ui.settings.NodeFixtures.OTHER_ORIGIN
import com.tether.app.ui.settings.NodeFixtures.REFUSAL
import com.tether.app.ui.settings.NodeFixtures.SENTINEL
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10.3: the Nodes panel's pure rules: what each outcome says (the server's words cleaned by the
 * label rule, T6.7), when the form lets go of the credential, the web's status words, how server
 * text is drawn, the form's bounds, and [NodesActions]: one request at a time, an answer for a
 * request that is no longer current dropped, nothing secret in any value it holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodesModelTest {

    private fun answered(ok: Boolean, nodeId: String?, message: String?) =
        NodeRequestOutcome.Answered(NodeActionResult(ok, nodeId, message, 1L))

    @Test fun everyOutcomeSaysWhatHappenedInWordsCleanedOfHiddenControls() {
        fun n(o: NodeRequestOutcome, action: NodeAction = NodeAction.Probe) = NodeOutcomes.notice(ORIGIN, action, "node_ws", o, 1)
        assertEquals("Reachable.", n(answered(true, "node_ws", "Reachable.")).text)
        assertTrue(n(answered(true, "node_ws", "Reachable.")).ok)
        // The web's fallbacks: `message ?? (ok ? "Done." : "That did not work.")`; words that clean to nothing too.
        assertEquals(NodesCopy.DONE, n(answered(true, "node_ws", null)).text)
        assertEquals(NodesCopy.FAILED, n(answered(false, null, null)).text)
        assertEquals(NodesCopy.FAILED, n(answered(false, null, "​‮ ")).text)
        // A server's message is untrusted display text: bidi controls dropped, whitespace collapsed.
        assertEquals("No such node. evil", n(answered(false, "x", "No such node.‮\n\n evil​")).text)
        // The refusal before #236 is deployed, in either shape the server may use.
        assertEquals(REFUSAL, n(answered(false, null, REFUSAL), NodeAction.Add).text)
        assertFalse(n(answered(false, null, REFUSAL), NodeAction.Add).ok)
        assertEquals(REFUSAL, n(NodeRequestOutcome.ServerError("$REFUSAL⁦"), NodeAction.Add).text)
        assertEquals(NodesCopy.FAILED, n(NodeRequestOutcome.ServerError("⁧")).text)
        assertEquals(NodeRegistryRules.NOT_SENT_MESSAGE, n(NodeRequestOutcome.NotSent).text)
        assertEquals(NodesCopy.LINK_LOST, n(NodeRequestOutcome.LinkLost).text)
        assertEquals(NodesCopy.TIMED_OUT, n(NodeRequestOutcome.TimedOut).text)
        assertEquals(NodesCopy.TOO_LONG, n(NodeRequestOutcome.Invalid(NodesCopy.TOO_LONG)).text)
        // A long server message is bounded.
        assertTrue(n(answered(false, null, "x".repeat(5_000))).text.length <= com.tether.app.client.LabelText.MAX_ERROR)
    }

    @Test fun theFormLetsGoOfTheCredentialOnlyOnceTheServerHoldsTheNode() {
        fun held(o: NodeRequestOutcome, action: NodeAction = NodeAction.Add) = NodeOutcomes.notice(ORIGIN, action, null, o, 1).heldByServer
        // The server answers a registered node with its id (ok follows the probe, so false is still held).
        assertTrue(held(answered(true, "node_ws", "Reachable.")))
        assertTrue(held(answered(false, "node_ws", "The node did not respond in time (timed out).")))
        // A refusal, an unreadable bundle, an error frame, nothing sent, no answer: kept.
        assertFalse(held(answered(false, null, REFUSAL)))
        assertFalse(held(answered(false, null, "That credential could not be read. Re-copy it from the peer.")))
        assertFalse(held(NodeRequestOutcome.ServerError("Could not add that node.")))
        assertFalse(held(NodeRequestOutcome.NotSent))
        assertFalse(held(NodeRequestOutcome.LinkLost))
        assertFalse(held(NodeRequestOutcome.TimedOut))
        assertFalse(held(NodeRequestOutcome.Invalid(NodesCopy.TOO_LONG)))
        // Only an Add.
        assertFalse(held(answered(true, "node_ws", "Reachable."), NodeAction.Probe))
    }

    @Test fun theStatusWordsAreTheWebs() {
        assertEquals(
            listOf("Reachable", "Unreachable", "Credential rejected", "Identity mismatch", "Protocol mismatch", "Error", "Not probed yet", "Not probed yet"),
            listOf("reachable", "unreachable", "unauthorized", "identity_mismatch", "skew", "error", "unknown", "quarantined").map { NodeStatusLook.of(it).label },
        )
        assertEquals(com.tether.app.ui.icons.TetherIcons.Check, NodeStatusLook.Reachable.icon)
        assertEquals(com.tether.app.ui.icons.TetherIcons.TriangleAlert, NodeStatusLook.Skew.icon)
        assertEquals(com.tether.app.ui.icons.TetherIcons.Timer, NodeStatusLook.NotProbed.icon)
    }

    @Test fun serverTextIsBoundedAndCleaned() {
        val hostile = NodeFixtures.node("node_h", "Work‮station​\n2", "https://x.example.test/" + "a".repeat(600), "reachable", version = "0.1‮\n9")
        assertEquals("Workstation 2", NodeText.label(hostile))
        // A label of hidden characters only is spelled out (ta-28i: two such never look alike) ...
        assertEquals(com.tether.app.client.LabelText.visibleValue("​‮"), NodeText.label(hostile.copy(label = "​‮")))
        assertEquals("\\u{200B}\\u{202E}", NodeText.label(hostile.copy(label = "​‮")))
        // ... and a missing or blank one is the node id.
        assertEquals("node_blank", NodeText.label(hostile.copy(nodeId = "node_blank", label = "")))
        assertEquals("node_blank", NodeText.label(hostile.copy(nodeId = "node_blank", label = "   ")))
        assertEquals(NodeText.MAX_URL, NodeText.url(hostile).length)
        assertTrue(NodeText.url(hostile).endsWith("…"))
        assertEquals("https://x.example.test", NodeText.url(hostile.copy(baseUrl = "https://x.example.test")))
        assertEquals("0.1 9", NodeText.version(hostile))
        assertNull(NodeText.version(hostile.copy(peerVersion = "​")))
        // nodes-settings.tsx: the skew warning only when the peer names a version that is not the console's.
        assertEquals(129, NodeText.skew(NodeFixtures.OLD, CONSOLE))
        assertNull(NodeText.skew(NodeFixtures.WORKSTATION, CONSOLE))
        assertNull(NodeText.skew(NodeFixtures.FRESH, CONSOLE))
        assertNull("no console version known, no claim", NodeText.skew(NodeFixtures.OLD, null))
    }

    @Test fun theFormFieldsKeepToTheServersBounds() {
        assertTrue(NodeFormRules.labelFits("a".repeat(64)))
        assertFalse(NodeFormRules.labelFits("a".repeat(65)))
        // Bytes, as the validator counts them: 32 two-byte letters fit, 33 do not.
        assertTrue(NodeFormRules.labelFits("é".repeat(32)))
        assertFalse(NodeFormRules.labelFits("é".repeat(33)))
        assertTrue(NodeFormRules.baseUrlFits("h".repeat(512)))
        assertFalse(NodeFormRules.baseUrlFits("h".repeat(513)))
        assertTrue(NodeFormRules.credentialFits("c".repeat(8192)))
        assertFalse(NodeFormRules.credentialFits("c".repeat(8193)))
    }

    @Test fun oneRequestAtATimeAndEachAnswerIsShown() {
        val scope = TestScope(StandardTestDispatcher())
        val writer = RecordingNodesWriter()
        val actions = NodesActions(writer, scope)
        assertTrue(actions.probe(ORIGIN, "node_ws"))
        // Busy at once (in the tap's own frame): every other request starts nothing.
        assertEquals(NodeAction.Probe, actions.busyFor(ORIGIN)?.action)
        assertFalse(actions.probe(ORIGIN, "node_ws"))
        assertFalse(actions.remove(ORIGIN, "node_lab"))
        assertFalse(actions.add(ORIGIN, SENTINEL, "", ""))
        scope.runCurrent()
        assertEquals(1, writer.calls.size)
        writer.answer(answered(true, "node_ws", "Reachable."))
        scope.runCurrent()
        assertNull(actions.busyFor(ORIGIN))
        assertEquals("Reachable.", actions.noticeFor(ORIGIN)?.text)
        assertNull("another server shows nothing of this one's", actions.noticeFor(OTHER_ORIGIN))
        // Then the next one goes.
        assertTrue(actions.remove(ORIGIN, "node_lab"))
        scope.runCurrent()
        assertEquals(listOf(NodeAction.Probe, NodeAction.Remove), writer.calls.map { it.action })
        assertEquals(listOf("node_ws", "node_lab"), writer.calls.map { it.nodeId })
        assertEquals(listOf(ORIGIN, ORIGIN), writer.calls.map { it.origin })
    }

    @Test fun nothingStartsWithoutAServerOrACredentialAndATooLongOneSaysSo() {
        val scope = TestScope(StandardTestDispatcher())
        val writer = RecordingNodesWriter()
        val actions = NodesActions(writer, scope)
        assertFalse(actions.add(null, SENTINEL, "", ""))
        assertFalse(actions.probe(null, "node_ws"))
        assertFalse(actions.add(ORIGIN, "  \n ", "", ""))
        assertNull(actions.notice)
        // Over the validator's 4096 (after the trim the client makes): refused here, and said.
        assertFalse(actions.add(ORIGIN, " " + "c".repeat(NodeRegistryRules.CREDENTIAL_MAX_LENGTH + 1), "", ""))
        assertEquals(NodesCopy.TOO_LONG, actions.noticeFor(ORIGIN)?.text)
        // Exactly the bound (with spaces around it) goes.
        assertTrue(actions.add(ORIGIN, "  " + "c".repeat(NodeRegistryRules.CREDENTIAL_MAX_LENGTH) + "  ", " L ", " U "))
        scope.runCurrent()
        val call = writer.calls.single()
        assertTrue(call.credential!!.matches("c".repeat(NodeRegistryRules.CREDENTIAL_MAX_LENGTH)))
        // The form's text as typed: the client trims (and omits blanks), as the web form + hook do.
        assertEquals(" L ", call.label)
        assertEquals(" U ", call.baseUrl)
    }

    @Test fun anAnswerForARequestNoLongerCurrentIsDropped() {
        val scope = TestScope(StandardTestDispatcher())
        val writer = RecordingNodesWriter()
        val actions = NodesActions(writer, scope)
        actions.probe(ORIGIN, "node_ws")
        scope.runCurrent()
        // After a server switch, the other server's request may start while A's is still out.
        assertNull(actions.busyFor(OTHER_ORIGIN))
        assertTrue(actions.probe(OTHER_ORIGIN, "node_b"))
        scope.runCurrent()
        writer.calls[0].reply.complete(NodeRequestOutcome.LinkLost)
        scope.runCurrent()
        assertNull("A's late answer is not shown", actions.notice)
        assertEquals(NodeAction.Probe, actions.busyFor(OTHER_ORIGIN)?.action)
        writer.calls[1].reply.complete(answered(true, "node_b", "Reachable."))
        scope.runCurrent()
        assertEquals("Reachable.", actions.noticeFor(OTHER_ORIGIN)?.text)
        assertNull(actions.noticeFor(ORIGIN))
    }

    @Test fun aThrowingWriterIsNeverSilent() {
        val scope = TestScope(StandardTestDispatcher())
        val actions = NodesActions(object : NodesWriter by NodesWriter.None {
            override suspend fun probe(origin: String, nodeId: String): NodeRequestOutcome = throw IllegalStateException("boom")
        }, scope)
        actions.probe(ORIGIN, "node_ws")
        scope.runCurrent()
        assertNull(actions.busy)
        assertEquals(NodesCopy.FAILED, actions.noticeFor(ORIGIN)?.text)
        assertFalse(actions.noticeFor(ORIGIN)!!.ok)
    }

    /** r2 (verifier L2): the shared guard takes back exactly a cut, and nothing else. */
    @Test fun theCutGuardUndoesACutAndOnlyACut() {
        var now = 0L
        val guard = CutGuard { now }
        // A cut: the edit removed "bearer", then the refused write carries "bearer".
        guard.edited("tok-bearer-9", "tok--9")
        assertEquals("tok-bearer-9", guard.undo("bearer"))
        // Forgotten once used: a second write restores nothing.
        assertNull(guard.undo("bearer"))
        // A Backspace over a selection writes no clipboard: nothing to undo (and a later write of other text is no match).
        guard.edited("tok-bearer-9", "tok--9")
        assertNull(guard.undo("tok"))
        // Removed text inside a repeated stretch still matches.
        guard.edited("aaaa", "aa")
        assertEquals("aaaa", guard.undo("aa"))
        // A plain typed edit followed by a copy of the whole is not a cut.
        guard.edited("abc", "abcd")
        assertNull(guard.undo("abcd"))
        // A write well after the edit is a Copy, not the cut's own write.
        guard.edited("tok-bearer-9", "tok--9")
        now += 600_000_000L
        assertNull(guard.undo("bearer"))
        // No edit at all, an empty or a missing clip: nothing.
        assertNull(CutGuard { 0L }.undo("x"))
        guard.edited("ab", "a")
        assertNull(guard.undo(""))
        guard.edited("ab", "a")
        assertNull(guard.undo(null))
    }

    @Test fun theGoldenCredentialIsObviouslyFake() {
        assertTrue(NodeFixtures.FAKE_CREDENTIAL.startsWith("FAKE-"))
        assertTrue(NodeFixtures.FAKE_CREDENTIAL.contains("not-a-real-credential"))
    }

    @Test fun noValueTheActionsHoldPrintsTheCredential() {
        val scope = TestScope(StandardTestDispatcher())
        val writer = RecordingNodesWriter()
        val actions = NodesActions(writer, scope)
        actions.add(ORIGIN, SENTINEL, "Workstation", "")
        scope.runCurrent()
        assertFalse(actions.busy.toString().contains(SENTINEL))
        assertFalse(writer.calls.toString().contains(SENTINEL))
        writer.answer(answered(false, null, REFUSAL))
        scope.runCurrent()
        assertFalse(actions.notice.toString().contains(SENTINEL))
        // The opaque credential confirms the text it was given, and nothing else.
        assertTrue(writer.calls.single().credential!!.matches(SENTINEL))
        assertFalse(writer.calls.single().credential!!.matches(SENTINEL + "x"))
    }
}
