package com.tether.app.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tether.app.client.BrowserInstallJob
import com.tether.app.client.BrowserRuntimeSource
import com.tether.app.client.BrowserRuntimeStatus
import com.tether.app.client.LabelText
import com.tether.app.client.SecurityResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The Browser section's words (settings-dialog.tsx, BrowserRuntimeSection and BrowserRuntimeStatusRows, the web's own). */
object BrowserCopy {
    const val TITLE = "Browser"
    const val CAPTION = "Whether the in-console browser can open on this server. This only reads the status; nothing is installed from here."

    /** The web's caption (settings-dialog.tsx BrowserRuntimeSection), used when the server can install from here (it reports `canInstall`). */
    const val CAPTION_INSTALLABLE = "Whether the in-console browser can open on this server. Chromium installs only when you press Install Chromium."
    const val STATUS = "Status"
    const val CHECKING = "Checking the browser…"
    const val FAILED = "Could not read the browser status."
    const val REFRESH = "Refresh"
    const val REFRESHING = "Checking…"
    const val READY = "Ready: Playwright and its Chromium are installed."
    const val CHROMIUM_MISSING = "Not ready: the browser (Chromium) is missing."
    const val PLAYWRIGHT_MISSING = "Not ready: the Playwright package could not be loaded."
    const val PLAYWRIGHT = "Playwright"
    const val CHROMIUM = "Chromium"
    const val TO_INSTALL = "To install"
    const val INSTALL_CAPTION = "Run this in the Tether install directory, then open the browser again: "
    const val COPY = "Copy command"
    const val COPIED = "Copied"

    // The Install Chromium row (settings-dialog.tsx BrowserRuntimeStatusRows).
    const val INSTALL = "Install Chromium"
    const val INSTALL_IDLE = "Downloads Chromium into Tether's state folder, as the service user. Nothing installs until you press this."
    const val INSTALLING_CAPTION = "Installing Chromium on the server. This can take a few minutes; you can leave this page open."
    const val INSTALLING = "Installing…"
    const val INSTALL_FAILED = "Install failed"
    const val INSTALL_DID_NOT_FINISH = "The install did not finish."
    const val TRY_AGAIN = "Try again"
    const val BY_HAND = "Or install by hand"
    const val NOT_STARTED = "The install could not start."
    const val OUTPUT_LINES = 12

    fun notStarted(code: Int?): String = if (code == null) NOT_STARTED else "The install could not start (HTTP $code)."

    /** `The install finished, but Chromium was still not found` + ` at <path>`. */
    fun stillMissing(s: BrowserRuntimeStatus): String = "The install finished, but Chromium was still not found" + (path(s)?.let { " at $it" } ?: "") + "."

    /** The row's caption in the web's order: the job's reason, or the finished-but-missing sentence, or the idle sentence; then the start's note. */
    fun installCaption(s: BrowserRuntimeStatus, job: BrowserInstallJob?, note: String): String {
        val head = when {
            job?.state == BrowserInstallJob.FAILED -> job.reason?.let(LabelText::error)?.takeIf { it.isNotEmpty() } ?: INSTALL_DID_NOT_FINISH
            installFailed(s, job) -> stillMissing(s)
            else -> INSTALL_IDLE
        }
        val n = note.takeIf { it.isNotEmpty() }
        return if (n != null) "$head $n" else head
    }

    /** A job that ended "done" while the expected Chromium is still not found is a failure for the person looking at it. */
    fun installFailed(s: BrowserRuntimeStatus, job: BrowserInstallJob?): Boolean =
        job?.state == BrowserInstallJob.FAILED || (job?.state == BrowserInstallJob.DONE && !s.binaryPresent)

    /** The last [OUTPUT_LINES] lines of the installer, cleaned line by line (the label rule), empty lines dropped. */
    fun outputTail(job: BrowserInstallJob?): List<String> = job?.output.orEmpty().takeLast(OUTPUT_LINES).map { LabelText.clean(it, LabelText.MAX_HINT) }

    // Native: what the web has no words for (no credential, a sign-in gateway in front of Tether).
    const val SIGNED_OUT = "Signed out — sign in again to see the browser status."

    /** `status.usable ? … : status.playwrightImportable ? … : …`. */
    fun headline(s: BrowserRuntimeStatus): String = when {
        s.usable -> READY
        s.playwrightImportable -> CHROMIUM_MISSING
        else -> PLAYWRIGHT_MISSING
    }

    /** `Loaded, version X` / `Not loaded: <error>`. Server text by the label rule. */
    fun playwrightLine(s: BrowserRuntimeStatus): String {
        if (!s.playwrightImportable) {
            val why = s.importError?.let(LabelText::error)?.takeIf { it.isNotEmpty() }
            return "Not loaded" + (why?.let { ": $it" } ?: "")
        }
        val v = s.playwrightVersion?.let(LabelText::label)?.takeIf { it.isNotEmpty() }
        return "Loaded" + (v?.let { ", version $it" } ?: "")
    }

    /** `Revision X expected` / `Revision unknown`, then ` · found at ` / ` · not found at ` when a path is known (the path itself is drawn apart, in the mono face). */
    fun chromiumLine(s: BrowserRuntimeStatus): String {
        val rev = s.chromiumRevision?.let(LabelText::label)?.takeIf { it.isNotEmpty() }
        val head = if (rev != null) "Revision $rev expected" else "Revision unknown"
        return if (path(s) != null) head + " · " + (if (s.binaryPresent) "found" else "not found") + " at " else head
    }

    fun path(s: BrowserRuntimeStatus): String? = s.executablePath?.let { LabelText.clean(it, LabelText.MAX_HINT) }?.takeIf { it.isNotEmpty() }

    fun command(s: BrowserRuntimeStatus): String? = s.installCommand?.let { LabelText.clean(it, LabelText.MAX_HINT) }?.takeIf { it.isNotEmpty() }
}

private const val POLL_MS = 1500L

/** The goldens' seam: a state built for the shot (nothing is read or sent while it stands). */
data class BrowserSeed(
    val status: BrowserRuntimeStatus? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val install: BrowserInstallJob? = null,
    val installNote: String = "",
)

/**
 * The Browser section's state and read (settings-dialog.tsx BrowserRuntimeSection) for ONE server
 * ([origin]): [status] / [loading] / [error] as the web's, read each time the section is shown
 * ([shown], the web's mount) and by Refresh ([load]). A failed read keeps the last status (the web
 * keeps it); only the newest read's answer is taken. Every call is sent for [origin] only.
 */
@Stable
class BrowserRuntimeController(
    private val source: BrowserRuntimeSource,
    val origin: String?,
    parent: CoroutineScope,
    private val seed: BrowserSeed? = null,
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)

    var status: BrowserRuntimeStatus? by mutableStateOf(seed?.status)
        private set
    var loading: Boolean by mutableStateOf(seed?.loading ?: (origin != null))
        private set
    var error: String? by mutableStateOf(seed?.error ?: if (origin == null) BrowserCopy.SIGNED_OUT else null)
        private set

    /** The install job last read (null: none read, or a server without the route). */
    var install: BrowserInstallJob? by mutableStateOf(seed?.install)
        private set

    /** The start's own words (a 409's message, or why it could not start); empty when none. */
    var installNote: String by mutableStateOf(seed?.installNote.orEmpty())
        private set

    private var loadSeq = 0
    private var disposed = false
    private var poll: Job? = null

    /** The section is on screen: the status and the install job are read (a seeded state is left as built). */
    fun shown() {
        if (seed != null) return
        load()
        val o = origin ?: return
        scope.launch {
            val job = readJob(o) ?: return@launch
            install = job
            pollWhileRunning(o)
        }
    }

    /** `load` (the web's own name): Refresh and the first read. */
    fun load() {
        val o = origin ?: return
        if (disposed) return
        val seq = ++loadSeq
        loading = true
        scope.launch { read(o, seq) }
    }

    /** One status read; a newer read's answer wins. Returns when this read is done. */
    private suspend fun read(o: String, seq: Int) {
        val r = try {
            source.status(o)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SecurityResult.Unavailable(null, o)
        }
        if (seq != loadSeq || disposed) return
        when (r) {
            is SecurityResult.Ok -> {
                status = r.value
                error = null
            }
            is SecurityResult.SignedOut -> error = BrowserCopy.SIGNED_OUT
            // A refusal (403), an older server without the route (404), a gateway, a dropped connection: one quiet line.
            else -> error = BrowserCopy.FAILED
        }
        loading = false
    }

    /** The job, or null for any answer that is not one (a server before the route, a refusal, a drop). */
    private suspend fun readJob(o: String): BrowserInstallJob? {
        val r = try {
            source.installJob(o)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return (r as? SecurityResult.Ok)?.takeIf { it.origin == o }?.value
    }

    /**
     * While the job runs, read its progress every [POLL_MS]; when it ends the status is read AGAIN before the
     * result is shown (so a found Chromium never flashes as a failure first). A refusal on the way (the route is
     * gone, the sign-in lapsed) ends the wait with no spinner; a dropped read is tried again, as the web does.
     */
    private fun pollWhileRunning(o: String) {
        if (install?.running != true || poll?.isActive == true) return
        poll = scope.launch {
            while (install?.running == true && !disposed) {
                delay(POLL_MS)
                val r = try {
                    source.installJob(o)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                when (r) {
                    is SecurityResult.Ok -> {
                        if (r.origin != o) continue
                        val next = r.value
                        if (next.running) {
                            install = next
                        } else {
                            val seq = ++loadSeq
                            loading = true
                            read(o, seq)
                            install = next
                        }
                    }
                    is SecurityResult.Unavailable, is SecurityResult.Blocked, is SecurityResult.LocalNetworkBlocked -> Unit
                    else -> install = null
                }
            }
        }
    }

    /** Install Chromium pressed (never called by anything else). A 409 or any refusal is the row's note; a running job is polled. */
    fun startInstall() {
        val o = origin ?: return
        if (disposed) return
        installNote = ""
        scope.launch {
            val r = try {
                source.startInstall(o)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                installNote = BrowserCopy.NOT_STARTED
                return@launch
            }
            if (disposed) return@launch
            when (r) {
                is SecurityResult.Ok -> {
                    val v = r.value
                    v.job?.let { install = it }
                    if (!v.ok) installNote = v.message ?: BrowserCopy.NOT_STARTED
                    else if (v.job != null && !v.job.running) load()
                    pollWhileRunning(o)
                }
                is SecurityResult.OwnerSignInNeeded -> installNote = BrowserCopy.notStarted(403)
                is SecurityResult.Refused -> installNote = BrowserCopy.notStarted(r.code)
                is SecurityResult.Unavailable -> installNote = BrowserCopy.notStarted(r.code)
                is SecurityResult.Blocked -> installNote = BrowserCopy.notStarted(r.code)
                else -> installNote = BrowserCopy.NOT_STARTED
            }
        }
    }

    fun dispose() {
        disposed = true
        loadSeq++
        poll?.cancel()
        job.cancel()
    }
}

/** The section's controller, bound to the signed-in server and credential (as [GitHubConnectionViewModel]); another one disposes it. */
class BrowserRuntimeViewModel(
    private val source: BrowserRuntimeSource,
    initial: GitHubIdentity,
    identities: Flow<GitHubIdentity>,
) : ViewModel() {
    private var bound = initial
    var controller: BrowserRuntimeController by mutableStateOf(BrowserRuntimeController(source, initial.origin, viewModelScope))
        private set

    init {
        viewModelScope.launch { identities.distinctUntilChanged().collect(::bind) }
    }

    private fun bind(identity: GitHubIdentity) {
        if (identity == bound) return
        bound = identity
        controller.dispose()
        controller = BrowserRuntimeController(source, identity.origin, viewModelScope)
    }

    override fun onCleared() {
        controller.dispose()
    }
}
