package com.tether.app.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tether.app.client.GitHubConnectionSource
import com.tether.app.client.GitHubConnectionStatus
import com.tether.app.client.GitHubDevicePoll
import com.tether.app.client.GitHubDeviceStatus
import com.tether.app.client.GitHubToken
import com.tether.app.client.LabelText
import com.tether.app.client.SecurityResult
import com.tether.app.client.TetherClient
import com.tether.app.client.serverOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** ta-coik.21: the GitHub connection section's words (settings-dialog.tsx 90fbb9f :1082-1166, the web's own). */
object GitHubCopy {
    const val LOADING = "Loading GitHub status…"
    const val STATUS = "Status"
    const val STATUS_FAILED = "Could not reach the GitHub status service."
    const val RETRY = "Retry"
    const val RECHECK = "Re-check"
    const val REMOVE_TOKEN = "Remove Tether token"
    const val REMOVING = "Removing…"
    const val NOT_LOGGED_IN = "gh is installed but no account is logged in."
    const val NOT_INSTALLED = "gh CLI is not installed — paste a token to connect without it."
    const val CONNECT_TITLE = "Connect in the app"
    const val CONNECT_CAPTION = "Open the device flow and enter a one-time code in your browser."
    const val START = "Start device flow"
    const val WAITING = "Waiting…"
    const val CODE_TITLE = "One-time code"
    const val WAITING_CODE = "Waiting for gh to issue the code…"
    const val OPEN = "open"
    const val CANCEL = "Cancel"
    const val DEVICE_FAILED = "The device flow failed."
    const val START_FAILED = "Could not start the GitHub login."
    const val PAT_TITLE = "Personal access token"
    const val TOKEN_TITLE = "Token"
    const val TOKEN_SAVED = "Token verified and saved."
    const val TOKEN_HINT = "Paste a fine-grained or classic PAT."
    const val TOKEN_PLACEHOLDER = "ghp_…"
    const val TOKEN_FIELD = "GitHub personal access token"
    const val VERIFY = "Verify & save"
    const val VERIFYING = "Verifying…"
    const val TOKEN_FAILED = "Could not verify that token."
    const val DISCONNECT_FAILED = "Could not disconnect."

    /** :1132-1134 `poll.verificationUri || "https://github.com/login/device"`. */
    const val DEFAULT_DEVICE_URL = "https://github.com/login/device"

    /** :1007: the web's own words when the poll's answer is not JSON. */
    const val NO_RESPONSE = "no response"

    /**
     * The server's owner-grade refusal (server.mjs 90fbb9f :3324-3326), which the web shows as its
     * `data.error`. Recognised by its opening and shown in these fixed words, never as received.
     */
    const val OWNER_NEEDED = "This needs an owner sign-in (password, passkey, the SSO gateway or the paired Tether app)."

    // Native: what the web has no words for (no credential, the phone's local-network block, a sign-in
    // gateway in front of Tether, another server signed in to).
    const val SIGNED_OUT_STATUS = "Signed out — sign in again to see the GitHub connection."
    const val SIGNED_OUT = "Signed out — sign in again to change the GitHub connection."
    const val LOCAL_NETWORK = "Local network access is blocked"
    const val NOT_SENT_OTHER = "Nothing was sent: the app is now signed in to another server."
    const val NOT_SENT = "Nothing was sent."
    const val LINK_UNOPENED = "No browser on this phone could open the link."
    const val LINK_UNOPENED_APP = "No app on this phone could open the link."
    const val LINK_REFUSED = "The server sent a device address that a browser will not open (a javascript:, data:, file: or content: address, or not an address at all)."
    fun blocked(code: Int) = "A sign-in page answered instead of Tether (HTTP $code). Nothing was sent past it."
    fun blockedStatus(code: Int) = "A sign-in page answered instead of Tether (HTTP $code). Exempt /api/github/ for paired devices."

    /** settings-dialog.tsx :1098-1102, the Status row's sentence. Server text by the label rule. */
    fun statusLine(status: GitHubConnectionStatus): String {
        if (!status.authenticated) return if (status.ghInstalled) NOT_LOGGED_IN else NOT_INSTALLED
        val account = status.account?.let(LabelText::label)?.takeIf { it.isNotEmpty() } ?: "unknown"
        val version = status.ghVersion?.let(LabelText::label)?.takeIf { it.isNotEmpty() }
        val scopes = status.scopes.map(LabelText::label).filter { it.isNotEmpty() }
        return buildString {
            append("Connected as @").append(account)
            append(if (status.managedToken) " · managed token" else " · host gh login")
            if (version != null) append(" · gh ").append(version)
            if (scopes.isNotEmpty()) append(" · ").append(scopes.joinToString(", "))
        }
    }
}

/** The device-flow poll's pace (settings-dialog.tsx :1001-1022): first at 500 ms, then 1.5 s, 2 s after a failed fetch. No limit, as on the web. */
data class GitHubPollPace(val first: Long = 500L, val next: Long = 1_500L, val afterFailure: Long = 2_000L)

/**
 * The goldens' seam: a state built for the shot (timing-free; nothing is read or sent while it stands).
 * r2: its [toString] never prints [tokenInput].
 */
data class GitHubSeed(
    val status: GitHubConnectionStatus? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val busy: Boolean = false,
    val actionError: String? = null,
    val deviceFlow: Boolean = false,
    val poll: GitHubDevicePoll? = null,
    val tokenInput: String = "",
    val tokenError: String? = null,
    val tokenSaved: Boolean = false,
) {
    override fun toString(): String =
        "GitHubSeed(status=$status, loading=$loading, error=$error, busy=$busy, actionError=$actionError, deviceFlow=$deviceFlow, " +
            "poll=$poll, tokenInput=${if (tokenInput.isEmpty()) "" else "***"}, tokenError=$tokenError, tokenSaved=$tokenSaved)"
}

/**
 * ta-coik.21: GitHubConnectionSection's state and calls (settings-dialog.tsx 90fbb9f :968-1078) for ONE
 * server ([origin]), the web's state machine field for field:
 * - [status] / [loading] / [error] (`loadStatus`, :980-992): read on creation, by Retry and Re-check,
 *   and after a token is saved, the device flow ends or the token is removed. A failed read keeps the
 *   last status (the web keeps it, so its `connected` still steers the rows below). Native: only the
 *   newest read's answer is taken.
 * - [busy] (:972): ONE flag for Start device flow, Verify & save and Remove Tether token, set in the
 *   tap's own frame, so a second tap sends nothing (the web's keys are disabled while it is set).
 * - The device flow (:1001-1042): [deviceFlow] (the web's `method === "device"`) and [poll]; polled
 *   500 ms after the start, every 1.5 s while "pending", 2 s after a failed fetch, with no client
 *   limit (gh's own code expiry ends it on the server, as on the web). Any other answer ends it and
 *   reads the status again. Cancel tells the server (best effort, :1040) and clears both.
 * - The token (:976-978, :1044-1062): [tokenInput] lives ONLY here, in memory (never saved state,
 *   never logged, never in a toString this class makes); it is sent trimmed by [saveToken], cleared
 *   once the server took it, and wiped by [dispose] (sign-out, another server, the ViewModel cleared).
 * Every call is sent for [origin] only (the source refuses another server).
 */
@Stable
class GitHubConnectionController(
    private val source: GitHubConnectionSource,
    val origin: String?,
    parent: CoroutineScope,
    seed: GitHubSeed? = null,
    private val pace: GitHubPollPace = GitHubPollPace(),
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)

    var status: GitHubConnectionStatus? by mutableStateOf(seed?.status)
        private set
    var loading: Boolean by mutableStateOf(seed?.loading ?: (origin != null))
        private set
    var error: String? by mutableStateOf(seed?.error ?: if (origin == null) GitHubCopy.SIGNED_OUT_STATUS else null)
        private set
    var busy: Boolean by mutableStateOf(seed?.busy == true)
        private set
    var actionError: String? by mutableStateOf(seed?.actionError)
        private set
    var deviceFlow: Boolean by mutableStateOf(seed?.deviceFlow == true)
        private set
    var poll: GitHubDevicePoll? by mutableStateOf(seed?.poll)
        private set
    var tokenInput: String by mutableStateOf(seed?.tokenInput.orEmpty())
        private set
    var tokenError: String? by mutableStateOf(seed?.tokenError)
        private set
    var tokenSaved: Boolean by mutableStateOf(seed?.tokenSaved == true)
        private set

    /** :1080 `Boolean(status?.authenticated)`. */
    val connected: Boolean get() = status?.authenticated == true

    private var loadSeq = 0
    private var pollGen = 0
    private var disposed = false

    init {
        // :996-999: the status is read once the section is there.
        if (seed == null && origin != null) loadStatus()
    }

    /** :980-992 `loadStatus`. */
    fun loadStatus() {
        val o = origin ?: return
        if (disposed) return
        val seq = ++loadSeq
        loading = true
        error = null
        scope.launch {
            val r = guarded(o) { source.status(o) }
            if (seq != loadSeq || disposed) return@launch
            when (r) {
                is SecurityResult.Ok -> status = r.value
                is SecurityResult.SignedOut -> error = GitHubCopy.SIGNED_OUT_STATUS
                is SecurityResult.Blocked -> error = GitHubCopy.blockedStatus(r.code)
                else -> error = failure(r, GitHubCopy.STATUS_FAILED)
            }
            loading = false
        }
    }

    /** :1024-1037 `startDevice`; the key is disabled while [busy] or the flow runs, so is this. */
    fun startDevice(): Boolean {
        val o = origin ?: return false
        if (disposed || busy || deviceFlow) return false
        busy = true
        actionError = null
        scope.launch {
            try {
                val r = guarded(o) { source.startLogin(o) }
                // r2: an answer that lands after dispose (sign-out, another server or sign-in) changes nothing.
                if (disposed) return@launch
                when (r) {
                    is SecurityResult.Ok -> {
                        deviceFlow = true
                        poll = GitHubDevicePoll.Started
                        startPolling(o)
                    }
                    else -> actionError = failure(r, GitHubCopy.START_FAILED)
                }
            } finally {
                busy = false
            }
        }
        return true
    }

    /**
     * :1001-1022, the poll loop of one flow (its generation). As the web reads each answer:
     * - JSON with `status: "pending"`: shown, polled again in 1.5 s;
     * - JSON with any other status (complete, error, idle, none): shown, the flow ends, the status is read;
     *   a JSON refusal of any status (401, 403, 409, a 5xx …; r2) is such an answer with no status, so it ends the flow quietly;
     * - not JSON: `res.json()` fails into `{ status: "error", error: "no response" }`; native: a sign-in
     *   gateway in front of Tether says so instead;
     * - the fetch itself failed: polled again in 2 s.
     */
    private fun startPolling(o: String) {
        val gen = ++pollGen
        scope.launch {
            var wait = pace.first
            while (true) {
                delay(wait)
                if (gen != pollGen || !deviceFlow) return@launch
                val r = guarded(o) { source.pollLogin(o) }
                if (gen != pollGen || disposed) return@launch
                when {
                    r is SecurityResult.Ok -> {
                        poll = r.value
                        if (r.value.status == GitHubDeviceStatus.Pending) {
                            wait = pace.next
                            continue
                        }
                        return@launch endFlow()
                    }
                    (r is SecurityResult.Unavailable && r.code == null) || r is SecurityResult.LocalNetworkBlocked -> wait = pace.afterFailure
                    r is SecurityResult.Blocked -> {
                        poll = GitHubDevicePoll(ok = false, status = GitHubDeviceStatus.Error, deviceCode = null, verificationUri = null, error = GitHubCopy.blocked(r.code))
                        return@launch endFlow()
                    }
                    r is SecurityResult.Unavailable -> {
                        poll = GitHubDevicePoll(ok = false, status = GitHubDeviceStatus.Error, deviceCode = null, verificationUri = null, error = GitHubCopy.NO_RESPONSE)
                        return@launch endFlow()
                    }
                    // Nothing was sent (signed out, or signed in to another server now): the flow is not ours to read.
                    r is SecurityResult.NotSent || (r is SecurityResult.SignedOut && r.origin == null) -> {
                        deviceFlow = false
                        return@launch
                    }
                    else -> {
                        poll = GitHubDevicePoll(ok = false, status = GitHubDeviceStatus.Unknown, deviceCode = null, verificationUri = null, error = null)
                        return@launch endFlow()
                    }
                }
            }
        }
    }

    /** :1012-1015: the status is read again and the flow is over. */
    private fun endFlow() {
        loadStatus()
        deviceFlow = false
    }

    /** :1039-1042 `cancelDevice`: the poll stops; the server is told (best effort); then the rows go. */
    fun cancelDevice() {
        val o = origin ?: return
        if (!deviceFlow) return
        pollGen++
        scope.launch {
            guarded(o) { source.cancelLogin(o) }
            if (disposed) return@launch
            deviceFlow = false
            poll = null
        }
    }

    /** :1155 the input's `onChange`: the text, and the last error and "saved" go. */
    fun editToken(text: String) {
        if (disposed) return
        tokenInput = text
        tokenError = null
        tokenSaved = false
    }

    /** :1044-1062 `saveToken`: `disabled={busy || !tokenInput.trim()}`, so a blank one sends nothing. */
    fun saveToken(): Boolean {
        val o = origin ?: return false
        val text = tokenInput.trim()
        if (disposed || busy || text.isEmpty()) return false
        busy = true
        tokenError = null
        tokenSaved = false
        scope.launch {
            try {
                val r = guarded(o) { source.saveToken(o, GitHubToken(text)) }
                if (disposed) return@launch
                when (r) {
                    is SecurityResult.Ok -> {
                        tokenSaved = true
                        tokenInput = ""
                        loadStatus()
                    }
                    else -> tokenError = failure(r, GitHubCopy.TOKEN_FAILED)
                }
            } finally {
                busy = false
            }
        }
        return true
    }

    /** :1064-1078 `disconnect` ("Remove Tether token"): sent at once, as on the web; the host gh login is never touched (server). */
    fun disconnect(): Boolean {
        val o = origin ?: return false
        if (disposed || busy) return false
        busy = true
        actionError = null
        scope.launch {
            try {
                val r = guarded(o) { source.logout(o) }
                if (disposed) return@launch
                when (r) {
                    is SecurityResult.Ok -> loadStatus()
                    else -> actionError = failure(r, GitHubCopy.DISCONNECT_FAILED)
                }
            } finally {
                busy = false
            }
        }
        return true
    }

    /** Signed out, another server, or the ViewModel cleared: the poll ends, the typed token goes, nothing more is sent. */
    fun dispose() {
        disposed = true
        pollGen++
        loadSeq++
        tokenInput = ""
        job.cancel()
    }

    /** A call that throws is the web's `catch`: its fallback (never silent, never a crash). */
    private suspend fun <T> guarded(o: String, call: suspend () -> SecurityResult<T>): SecurityResult<T> = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        SecurityResult.Unavailable(null, o)
    }

    private fun failure(r: SecurityResult<*>, fallback: String): String = when (r) {
        is SecurityResult.Ok -> fallback
        is SecurityResult.OwnerSignInNeeded -> GitHubCopy.OWNER_NEEDED
        is SecurityResult.SignedOut -> GitHubCopy.SIGNED_OUT
        // `data.error || fallback`: Tether's sentence, by the label rule.
        is SecurityResult.Refused -> LabelText.error(r.message).ifEmpty { fallback }
        SecurityResult.LocalNetworkBlocked -> GitHubCopy.LOCAL_NETWORK
        is SecurityResult.Blocked -> GitHubCopy.blocked(r.code)
        is SecurityResult.NotSent -> if (r.origin != null && r.origin != origin) GitHubCopy.NOT_SENT_OTHER else GitHubCopy.NOT_SENT
        is SecurityResult.Unavailable -> fallback
    }
}

/** r2: what one controller is for: the signed-in server's origin AND the credential in force there ([TetherClient.credentialEpoch]). */
data class GitHubIdentity(val origin: String?, val credential: Long)

/**
 * ta-coik.21: where the section's controller lives. The web's GitHubConnectionSection stays mounted
 * with the dashboard (its `<dialog>` and tab panels are always in the page), so its status, its device
 * flow and a half-typed token survive closing Settings and switching tabs; an activity-scoped
 * ViewModel keeps the same here, across rotation too. It is bound to the signed-in server AND the
 * credential in force ([identities], r2): another server, another sign-in on the same server, or a
 * sign-out disposes the controller (the poll ends, the typed token is wiped, an answer still in flight
 * lands nowhere) and starts a fresh one; [onCleared] does the same. Nothing of it is ever saved state.
 */
class GitHubConnectionViewModel(
    private val source: GitHubConnectionSource,
    initial: GitHubIdentity,
    identities: Flow<GitHubIdentity>,
) : ViewModel() {
    private var bound = initial
    var controller: GitHubConnectionController by mutableStateOf(GitHubConnectionController(source, initial.origin, viewModelScope))
        private set

    init {
        viewModelScope.launch { identities.distinctUntilChanged().collect(::bind) }
    }

    private fun bind(identity: GitHubIdentity) {
        if (identity == bound) return
        bound = identity
        controller.dispose()
        controller = GitHubConnectionController(source, identity.origin, viewModelScope)
    }

    override fun onCleared() {
        controller.dispose()
    }

    companion object {
        /** SettingsDialog's own `origin` (the paired server's canonical origin while signed in, else null) with the credential's epoch. */
        fun identitiesOf(client: TetherClient): Flow<GitHubIdentity> =
            combine(client.configured, client.serverUrl, client.credentialEpoch) { configured, server, credential ->
                GitHubIdentity(if (configured) serverOrigin(server) else null, credential)
            }

        /** The identity as it stands now (the first frame's controller). */
        fun identityOf(client: TetherClient): GitHubIdentity =
            GitHubIdentity(if (client.configured.value) serverOrigin(client.serverUrl.value) else null, client.credentialEpoch.value)
    }
}
