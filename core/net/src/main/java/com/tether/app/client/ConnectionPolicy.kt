package com.tether.app.client

import com.tether.app.protocol.PROTOCOL_VERSION
import com.tether.app.protocol.ServerMessage
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The connection manager's timers, in one place (T1.2). */
object ConnectionTimings {
    /**
     * How long a `ping` may go unanswered before the socket is presumed half-open
     * and dropped. Web twin: use-tether.ts PING_TIMEOUT_MS = UNACKED_CLOSE_MS.
     * Any inbound frame (not only the pong) proves the link alive.
     */
    const val PING_TIMEOUT_MS: Long = PendingInput.UNACKED_CLOSE_MS

    /**
     * After the app leaves the foreground, how long the socket is kept (quick app
     * switches, the photo picker, the share sheet) before it is closed and
     * reconnects stop. FCM covers the background; the foreground re-connects.
     */
    const val BACKGROUND_GRACE_MS: Long = 60_000

    /**
     * Consecutive connect TIMEOUTS, while the OS restricts local-network access,
     * after which the block is suspected even for a host the classifier cannot
     * see as local (e.g. a global IPv6 address on the Wi-Fi LAN). T0.6 review.
     */
    const val LOCAL_NETWORK_SUSPECT_TIMEOUTS: Int = 3

    /**
     * ta-coik.32 (R2): a socket whose handshake was accepted at least this long before it was lost
     * is replaced at once (no backoff) while the app is in front. The floor keeps a server that
     * accepts and then drops every link from turning that into a loop: one immediate attempt per
     * this period at most; every later attempt backs off.
     */
    const val IMMEDIATE_RECONNECT_MIN_LIFETIME_MS: Long = 10_000

    /**
     * ta-coik.32 (R1): back in front after at least this long away, an open socket is not pinged
     * (up to [PING_TIMEOUT_MS]) but replaced at once. A backgrounded app is frozen by the OS and
     * cannot answer the server's heartbeat (a ping every 30 s, the socket ended at the second
     * miss), so after this long the link is presumed gone; a shorter trip keeps the web's ping.
     */
    const val BACKGROUND_REPLACE_AFTER_MS: Long = 25_000
}

/** Handle to a task scheduled on a [Scheduler]. */
fun interface Cancellable {
    fun cancel()
}

/**
 * Runs [task] once, [delayMs] from now. Every connection-manager timer (reconnect
 * backoff, ping timeout, background grace) goes through this seam, so tests
 * drive time deterministically instead of sleeping.
 */
fun interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

/** Production [Scheduler]: a coroutine delay on [scope]. */
class CoroutineScheduler(private val scope: CoroutineScope) : Scheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val job = scope.launch {
            delay(delayMs)
            task()
        }
        return Cancellable { job.cancel() }
    }
}

/**
 * Exponential reconnect backoff with "equal jitter": attempt n waits a uniform
 * value in [d/2, d] where d = min(cap, base * 2^n). The floor (base/2) means
 * a failure loop can never spin; [reset] after a successful handshake.
 * Not thread-safe: the client calls it under its lock.
 */
class Backoff(
    val baseMs: Long = 1_000,
    val capMs: Long = 30_000,
    private val random: () -> Double = { Random.nextDouble() },
) {
    init {
        require(baseMs > 0 && capMs >= baseMs) { "backoff needs 0 < base <= cap" }
    }

    /** Failed attempts since the last [reset]. */
    var attempts: Int = 0
        private set

    /** The delay before the next attempt; counts the attempt. */
    fun next(): Long {
        val exp = min(capMs, baseMs shl min(attempts, 20))
        attempts++
        val half = exp / 2
        val jitter = (random().coerceIn(0.0, 1.0) * (exp - half)).toLong()
        return half + jitter
    }

    fun reset() {
        attempts = 0
    }
}

/** Which side of the native compatibility window (S1.1 / D5) is behind. */
enum class IncompatibleReason {
    /** The server's floor is above this app's protocol: update the app. */
    ClientTooOld,

    /** The server predates the protocol this app speaks: update the server. */
    ServerTooOld,
}

/**
 * Why this app cannot talk to the server. [serverProtocolVersion] and
 * [nativeProtocolFloor] are what the server reported (null when it did not);
 * [message] is the server's own copy, when a `version_mismatch` carried one.
 */
data class Incompatibility(
    val reason: IncompatibleReason,
    val serverProtocolVersion: Int?,
    val nativeProtocolFloor: Int?,
    val message: String? = null,
)

/**
 * The client side of the native window: the server serves a native hello with
 * `nativeProtocolFloor <= v <= PROTOCOL_VERSION` (lib/hello-compat.mjs). The
 * server's `version_mismatch` reply to our hello is authoritative; the same
 * window evaluated on `/healthz` and `ready` lets the app decide before (or
 * without) that reply. The client version defaults to PROTOCOL_VERSION, which
 * is TARGET_PROTOCOL_VERSION (ta-3uk), so a server below it is ServerTooOld.
 */
object Compatibility {

    /**
     * Null = compatible. A server with no `nativeProtocolFloor` predates v129 (it
     * has no native window, only strict equality), so it is too old for this app.
     */
    fun evaluate(
        serverProtocolVersion: Int?,
        nativeProtocolFloor: Int?,
        clientVersion: Int = PROTOCOL_VERSION,
    ): Incompatibility? {
        val reason = when {
            nativeProtocolFloor == null -> IncompatibleReason.ServerTooOld
            clientVersion < nativeProtocolFloor -> IncompatibleReason.ClientTooOld
            serverProtocolVersion != null && clientVersion > serverProtocolVersion -> IncompatibleReason.ServerTooOld
            else -> return null
        }
        return Incompatibility(reason, serverProtocolVersion, nativeProtocolFloor)
    }

    /**
     * A `version_mismatch` frame. A native reply carries `reason`; a server
     * that answered with the web (strict-equality) frame only says which
     * version it requires, which still tells the two sides apart.
     */
    fun fromMismatch(frame: ServerMessage.VersionMismatch, clientVersion: Int = PROTOCOL_VERSION): Incompatibility {
        val serverVersion = frame.serverProtocolVersion ?: frame.requiredVersion.takeIf { it >= 0 }
        val reason = when (frame.reason) {
            "client_too_old" -> IncompatibleReason.ClientTooOld
            "server_too_old" -> IncompatibleReason.ServerTooOld
            else -> if (serverVersion != null && serverVersion < clientVersion) {
                IncompatibleReason.ServerTooOld
            } else {
                IncompatibleReason.ClientTooOld
            }
        }
        return Incompatibility(reason, serverVersion, frame.nativeProtocolFloor, frame.message)
    }
}
