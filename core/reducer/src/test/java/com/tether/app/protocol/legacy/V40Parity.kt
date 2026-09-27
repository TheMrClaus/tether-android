package com.tether.app.protocol.legacy

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.conformance.TreeDiff
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsValue
import java.io.File
import java.util.IdentityHashMap
import kotlinx.serialization.json.Json
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import com.tether.app.protocol.fold.initialSessionState as v128InitialState
import com.tether.app.protocol.fold.reduce as v128Reduce
import com.tether.app.protocol.reduce.initialSessionState as v40InitialState
import com.tether.app.protocol.reduce.reduce as v40Reduce

/**
 * T2.1D Revision 9: the v40 reducer tests (Golden / Lifecycle / Blocks / SessionLevel), kept
 * verbatim as `V40*ParityTest`, run on the v128 fold + [LegacyProjectionAdapter] through the
 * shims below — `freshState` / `reduce` / `fold` resolve here instead of the v40 reducer.
 *
 * Every shim call is one STEP. Each step's adapted [SessionProjection] is compared (as a JSON
 * tree, every field incl. defaults) with the v40 reducer's output for the same inputs: the v40
 * twin is folded side by side while the v40 reducer exists, and its outputs are recorded in
 * `src/test/resources/v40-parity/<Class>.json` (`-Pparity.recordV40=<dir>` writes them). A
 * difference fails the step unless [V40Differences] lists it with the events.mjs line that
 * justifies it. The tests' own v40 assertions then run on the v128 result as well.
 */
object V40Parity {
    internal var context: Context? = null

    internal class Context(val testClass: String, val method: String) {
        val adapter = LegacyProjectionAdapter()
        val treeOf = IdentityHashMap<SessionProjection, JsObj>()
        val typedOf = IdentityHashMap<JsObj, SessionProjection>()
        val v40Of = IdentityHashMap<SessionProjection, SessionProjection>()
        val recorded = ArrayList<JsValue>()
        var step = 0

        /** The v40 reducer's recorded outputs for this test, one per shim step. */
        val fixture: JsArr? by lazy {
            val text = V40Parity::class.java.getResourceAsStream("/v40-parity/$testClass.json")?.bufferedReader()?.readText()
            text?.let { (JsCodec.parse(it) as JsObj)[method] as? JsArr }
        }
    }

    private val recording: Boolean get() = !System.getProperty("parity.recordV40").isNullOrBlank()

    private val ENCODER = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    /** Every field of the typed projection, defaults and nulls included. */
    fun encode(projection: SessionProjection): JsValue =
        JsCodec.fromJson(ENCODER.encodeToJsonElement(SessionProjection.serializer(), projection))

    internal fun ctx(): Context = checkNotNull(context) { "V40Parity shim used outside a V40ParityRule test" }

    internal fun adopt(tree: JsObj, v40: SessionProjection, input: String): SessionProjection {
        val c = ctx()
        val typed = c.typedOf[tree] ?: checkNotNull(c.adapter.adapt(tree)) { "adapter rejected the tree at step ${c.step}" }
        c.typedOf[tree] = typed
        c.treeOf[typed] = tree
        c.v40Of[typed] = v40
        compare(c, typed, v40, input)
        c.step++
        return typed
    }

    private fun compare(c: Context, typed: SessionProjection, v40: SessionProjection, input: String) {
        val expected = encode(v40)
        c.recorded.add(expected)
        if (!recording) {
            val fixture = c.fixture ?: error("no recorded v40 steps for ${c.testClass}.${c.method} in v40-parity/${c.testClass}.json")
            val recordedStep = fixture.getOrNull(c.step) ?: error("${c.testClass}.${c.method}: step ${c.step} beyond the ${fixture.size} recorded v40 steps")
            val drift = TreeDiff.diff(recordedStep, expected)
            if (drift.isNotEmpty()) throw AssertionError("recorded v40 fixture drifted from the live v40 reducer at step ${c.step}\n" + drift.take(12).joinToString("\n") { it.render() })
        }
        val divergences = TreeDiff.diff(expected, encode(typed))
        val unexplained = divergences.filterNot { V40Differences.allows(c.testClass, c.method, it.path) }
        if (unexplained.isNotEmpty()) {
            throw AssertionError(
                "v128 fold + adapter diverges from v40 at ${c.testClass}.${c.method} step ${c.step} after $input\n" +
                    unexplained.take(12).joinToString("\n") { it.render() },
            )
        }
    }

    internal fun record(c: Context) {
        if (!recording) {
            val fixture = c.fixture
            check(fixture != null && fixture.size == c.recorded.size) {
                "${c.testClass}.${c.method}: ran ${c.recorded.size} steps, ${fixture?.size} recorded"
            }
            return
        }
        val dir = System.getProperty("parity.recordV40")!!
        val file = File(dir, "${c.testClass}.json")
        val existing = if (file.exists()) JsCodec.parse(file.readText()) as JsObj else JsObj.EMPTY
        val next = existing.put(c.method, JsArr.of(c.recorded))
        file.parentFile.mkdirs()
        file.writeText(JsCodec.canonical(next) + "\n")
    }
}

/** Installs the per-test [V40Parity] context. */
class V40ParityRule : TestWatcher() {
    override fun starting(description: Description) {
        V40Parity.context = V40Parity.Context(description.testClass.simpleName, description.methodName)
    }

    override fun succeeded(description: Description) {
        V40Parity.record(V40Parity.ctx())
    }

    override fun finished(description: Description) {
        V40Parity.context = null
    }
}

fun freshState(): SessionProjection = V40Parity.adopt(
    v128InitialState("s1", "claude", "/workspace"),
    v40InitialState("s1", "claude", "/workspace"),
    "freshState()",
)

fun reduce(state: SessionProjection, event: AgentEvent): SessionProjection {
    val c = V40Parity.ctx()
    val tree = checkNotNull(c.treeOf[state]) { "state was not produced by the V40Parity shims" }
    val v40 = checkNotNull(c.v40Of[state])
    return V40Parity.adopt(v128Reduce(tree, JsCodec.fromJson(event.raw) as JsObj), v40Reduce(v40, event), event.raw.toString())
}

fun fold(state: SessionProjection, vararg events: AgentEvent): SessionProjection =
    events.fold(state) { acc, event -> reduce(acc, event) }
