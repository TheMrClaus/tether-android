package com.tether.app.ui.chat

import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.model.TextOnlyDelta
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.tree
import com.tether.app.protocol.tree.JsObj
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-jtfq: what counts as "only more words in an agent message", on the tree and on its typed projection. */
class TextOnlyDeltaTest {
    private val base: JsObj = ChatFixtures.streaming.tree

    private fun delta(tree: JsObj, text: String) =
        reduce(tree, ev("message_delta", "t1", ts = ChatFixtures.T_STREAM + 1_000) { put("blockId", "t1:m1"); put("text", text) }.tree())

    private fun apply(tree: JsObj, type: String, block: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        reduce(tree, ev(type, "t1", ts = ChatFixtures.T_STREAM + 2_000) { put("blockId", block); extra() }.tree())

    @Test fun moreWordsInTheStreamingMessageAreTextOnly() {
        val next = delta(delta(base, " more"), " and more")
        assertTrue("tree", TextOnlyDelta.isTextOnly(base, next))
        val adapter = LegacyProjectionAdapter()
        assertTrue("projection, one adapter", TextOnlyDelta.isTextOnly(adapter.adapt(base), adapter.adapt(next)))
        // The same reading, adapted by separate adapters (nothing shared), is still the held reading plus words.
        assertTrue("projection, two adapters", TextOnlyDelta.isTextOnly(LegacyProjectionAdapter.adaptOnce(base), LegacyProjectionAdapter().adapt(next)))
    }

    @Test fun theSameTreeAndNullsAreTextOnlyOnlyAgainstThemselves() {
        assertTrue(TextOnlyDelta.isTextOnly(base, base))
        assertTrue(TextOnlyDelta.isTextOnly(null as JsObj?, null))
        assertFalse(TextOnlyDelta.isTextOnly(base, null))
        assertFalse(TextOnlyDelta.isTextOnly(null, base))
        assertFalse(TextOnlyDelta.isTextOnly(LegacyProjectionAdapter.adaptOnce(base), null))
    }

    @Test fun aMessageThatFinishesIsNotTextOnly() {
        val done = apply(delta(base, " more"), "message_completed", "t1:m1") { put("text", "Two suites fail so far: more") }
        assertFalse(TextOnlyDelta.isTextOnly(delta(base, " more"), done))
        val adapter = LegacyProjectionAdapter()
        assertFalse(TextOnlyDelta.isTextOnly(adapter.adapt(delta(base, " more")), adapter.adapt(done)))
    }

    @Test fun aNewBlockIsNotTextOnly() {
        val started = apply(base, "message_started", "t1:m2")
        assertFalse(TextOnlyDelta.isTextOnly(base, started))
        val adapter = LegacyProjectionAdapter()
        assertFalse(TextOnlyDelta.isTextOnly(adapter.adapt(base), adapter.adapt(started)))
    }

    @Test fun aTurnEndingIsNotTextOnly() {
        val ended = reduce(base, ev("turn_end", "t1", ts = ChatFixtures.T_STREAM + 2_000) { put("outcome", "ok") }.tree())
        assertFalse(TextOnlyDelta.isTextOnly(base, ended))
        val adapter = LegacyProjectionAdapter()
        assertFalse(TextOnlyDelta.isTextOnly(adapter.adapt(base), adapter.adapt(ended)))
    }

    @Test fun aChangeOutsideTheTurnsIsNotTextOnly() {
        val withKey = base.put("somethingElse", com.tether.app.protocol.tree.JsStr("x"))
        assertFalse(TextOnlyDelta.isTextOnly(base, withKey))
    }
}
