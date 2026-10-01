package com.tether.app.ui.settings

import com.tether.app.client.NodeCredential
import com.tether.app.client.NodeRequestOutcome
import com.tether.app.protocol.NodeSummary
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred

/** T10.3: the Nodes panel's seeds. Every credential here is a sentinel or obviously FAKE. */
object NodeFixtures {
    const val ORIGIN = "https://console.example.test"
    const val OTHER_ORIGIN = "https://other-console.example.test"

    /** Checked against semantics, logs, preferences, saved state and the clipboard. */
    const val SENTINEL = "parity-SENTINEL-nodebearer-Q7z9x"

    /** What the revealed golden shows: obviously not a real bundle. */
    const val FAKE_CREDENTIAL = "FAKE-node-bundle-not-a-real-credential-0000"

    /** A fixed "now" (the "last seen" line); the list's times are just before it. */
    const val NOW = 1_759_400_000_000L
    const val CONSOLE = 137

    fun node(
        id: String,
        label: String,
        url: String,
        status: String,
        lastSeen: Long = 0,
        version: String? = null,
        protocol: Int? = null,
    ) = NodeSummary(
        nodeId = id,
        label = label,
        baseUrl = url,
        publicKey = "cGFyaXR5LWtleQ",
        createdAt = NOW - 30L * 86_400_000,
        lastSeenAt = lastSeen,
        status = status,
        peerVersion = version,
        peerProtocolVersion = protocol,
    )

    val WORKSTATION = node("node_ws", "Workstation", "http://10.0.0.2:4173", "reachable", NOW - 5 * 60_000, "0.14.2", 137)
    val LAB = node("node_lab", "Lab box", "https://lab.example.test", "unreachable", NOW - 3 * 3_600_000, "0.13.0", 137)
    val REVOKED = node("node_rev", "Build server", "https://build.example.test", "unauthorized", NOW - 26 * 3_600_000, "0.14.0", 137)
    val OLD = node("node_old", "Old laptop", "http://10.0.0.9:4173", "skew", NOW - 2 * 86_400_000, "0.9.0", 129)
    val FRESH = node("node_new", "New peer", "https://peer.example.test", "unknown")

    val LIST = listOf(WORKSTATION, LAB, REVOKED, OLD, FRESH)

    /** The refusal a phone sign-in gets before tether #236 is deployed (lib/node-ws-guard.mjs at 887c222). */
    const val REFUSAL = "Manage nodes from a browser session, not from a paired device."
}

/**
 * A writer that records each request and leaves it waiting until the test answers it, the way
 * the client waits for the request's own `node-result`. The credential stays opaque
 * ([NodeCredential.matches] checks it).
 */
class RecordingNodesWriter : NodesWriter {
    class Call(
        val action: NodeAction,
        val origin: String,
        val nodeId: String?,
        val credential: NodeCredential?,
        val label: String?,
        val baseUrl: String?,
        val reply: CompletableDeferred<NodeRequestOutcome> = CompletableDeferred(),
    ) {
        override fun toString() = "Call($action, $origin, $nodeId, $credential, $label, $baseUrl)"
    }

    val calls = CopyOnWriteArrayList<Call>()

    override suspend fun add(origin: String, credential: NodeCredential, label: String?, baseUrl: String?): NodeRequestOutcome =
        record(Call(NodeAction.Add, origin, null, credential, label, baseUrl))

    override suspend fun probe(origin: String, nodeId: String): NodeRequestOutcome = record(Call(NodeAction.Probe, origin, nodeId, null, null, null))

    override suspend fun remove(origin: String, nodeId: String): NodeRequestOutcome = record(Call(NodeAction.Remove, origin, nodeId, null, null, null))

    private suspend fun record(call: Call): NodeRequestOutcome {
        calls += call
        return call.reply.await()
    }

    /** Answer the last request. */
    fun answer(outcome: NodeRequestOutcome) {
        calls.last().reply.complete(outcome)
    }
}

/**
 * The writer behind a seeded shot: a request is a timing dependency (and a bug), so it fails the
 * shot. An AssertionError, not an Exception: [NodesActions] shows an Exception as "That did not
 * work.", which would be recorded instead of failing.
 */
object NeverWritesNodes : NodesWriter {
    override suspend fun add(origin: String, credential: NodeCredential, label: String?, baseUrl: String?): NodeRequestOutcome = throw AssertionError("a seeded shot must not add")
    override suspend fun probe(origin: String, nodeId: String): NodeRequestOutcome = throw AssertionError("a seeded shot must not probe")
    override suspend fun remove(origin: String, nodeId: String): NodeRequestOutcome = throw AssertionError("a seeded shot must not remove")
}
