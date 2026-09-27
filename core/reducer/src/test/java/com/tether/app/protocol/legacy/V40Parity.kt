package com.tether.app.protocol.legacy

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.conformance.TreeDiff
import com.tether.app.protocol.fold.initialSessionState
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsValue
import java.util.IdentityHashMap
import kotlinx.serialization.json.Json
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import com.tether.app.protocol.fold.reduce as v128Reduce

/**
 * T2.1D Revision 9: the v40 reducer tests (Golden / Lifecycle / Blocks / SessionLevel), kept
 * verbatim as `V40*ParityTest`, run on the v128 fold + [LegacyProjectionAdapter] through the
 * shims below — `freshState` / `reduce` / `fold` resolve here instead of the deleted v40 reducer.
 *
 * Every shim call is one STEP. Each step's adapted [SessionProjection] is compared (as a JSON
 * tree, every field incl. defaults) with the v40 reducer's output for the same inputs, recorded
 * in `src/test/resources/v40-parity/<Class>.json` by commit 60bfd82 while the v40 reducer still
 * ran side by side (37 tests, 180 steps, 0 differences). A difference fails the step unless
 * [V40Differences] lists it with the events.mjs line that justifies it; a test must run exactly
 * its recorded number of steps. The tests' own v40 assertions then run on the v128 result too.
 */
object V40Parity {
    internal var context: Context? = null

    internal class Context(val testClass: String, val method: String) {
        val adapter = LegacyProjectionAdapter()
        val treeOf = IdentityHashMap<SessionProjection, JsObj>()
        val typedOf = IdentityHashMap<JsObj, SessionProjection>()
        var step = 0

        /** The v40 reducer's recorded outputs for this test, one per shim step. */
        val fixture: JsArr by lazy {
            val text = V40Parity::class.java.getResourceAsStream("/v40-parity/$testClass.json")?.bufferedReader()?.readText()
                ?: error("no recorded v40 steps: v40-parity/$testClass.json")
            (JsCodec.parse(text) as JsObj)[method] as? JsArr ?: error("no recorded v40 steps for $testClass.$method")
        }
    }

    private val ENCODER = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    /** Every field of the typed projection, defaults and nulls included. */
    fun encode(projection: SessionProjection): JsValue =
        JsCodec.fromJson(ENCODER.encodeToJsonElement(SessionProjection.serializer(), projection))

    internal fun ctx(): Context = checkNotNull(context) { "V40Parity shim used outside a V40ParityRule test" }

    internal fun adopt(tree: JsObj, input: String): SessionProjection {
        val c = ctx()
        val typed = c.typedOf[tree] ?: checkNotNull(c.adapter.adapt(tree)) { "adapter rejected the tree at step ${c.step}" }
        c.typedOf[tree] = typed
        c.treeOf[typed] = tree
        compare(c, typed, input)
        c.step++
        return typed
    }

    private fun compare(c: Context, typed: SessionProjection, input: String) {
        val expected = c.fixture.getOrNull(c.step)
            ?: throw AssertionError("${c.testClass}.${c.method}: step ${c.step} beyond the ${c.fixture.size} recorded v40 steps")
        val divergences = TreeDiff.diff(expected, encode(typed))
        val unexplained = divergences.filterNot { V40Differences.allows(c.testClass, c.method, it.path) }
        if (unexplained.isNotEmpty()) {
            throw AssertionError(
                "v128 fold + adapter diverges from v40 at ${c.testClass}.${c.method} step ${c.step} after $input\n" +
                    unexplained.take(12).joinToString("\n") { it.render() },
            )
        }
    }

    internal fun finish(c: Context) {
        check(c.fixture.size == c.step) { "${c.testClass}.${c.method}: ran ${c.step} steps, ${c.fixture.size} recorded" }
    }
}

/** Installs the per-test [V40Parity] context. */
class V40ParityRule : TestWatcher() {
    override fun starting(description: Description) {
        V40Parity.context = V40Parity.Context(description.testClass.simpleName, description.methodName)
    }

    override fun succeeded(description: Description) {
        V40Parity.finish(V40Parity.ctx())
    }

    override fun finished(description: Description) {
        V40Parity.context = null
    }
}

fun freshState(): SessionProjection = V40Parity.adopt(initialSessionState("s1", "claude", "/workspace"), "freshState()")

fun reduce(state: SessionProjection, event: AgentEvent): SessionProjection {
    val tree = checkNotNull(V40Parity.ctx().treeOf[state]) { "state was not produced by the V40Parity shims" }
    return V40Parity.adopt(v128Reduce(tree, JsCodec.fromJson(event.raw) as JsObj), event.raw.toString())
}

fun fold(state: SessionProjection, vararg events: AgentEvent): SessionProjection =
    events.fold(state) { acc, event -> reduce(acc, event) }
