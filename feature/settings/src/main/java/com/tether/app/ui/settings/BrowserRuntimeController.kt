package com.tether.app.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tether.app.client.BrowserRuntimeSource
import com.tether.app.client.BrowserRuntimeStatus
import com.tether.app.client.LabelText
import com.tether.app.client.SecurityResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The Browser section's words (settings-dialog.tsx, BrowserRuntimeSection and BrowserRuntimeStatusRows, the web's own). */
object BrowserCopy {
    const val TITLE = "Browser"
    const val CAPTION = "Whether the in-console browser can open on this server. This only reads the status; nothing is installed from here."
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

/** The goldens' seam: a state built for the shot (nothing is read or sent while it stands). */
data class BrowserSeed(val status: BrowserRuntimeStatus? = null, val loading: Boolean = false, val error: String? = null)

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

    private var loadSeq = 0
    private var disposed = false

    /** The section is on screen: the status is read (a seeded state is left as built). */
    fun shown() {
        if (seed == null) load()
    }

    /** `load` (the web's own name): Refresh and the first read. */
    fun load() {
        val o = origin ?: return
        if (disposed) return
        val seq = ++loadSeq
        loading = true
        scope.launch {
            val r = try {
                source.status(o)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SecurityResult.Unavailable(null, o)
            }
            if (seq != loadSeq || disposed) return@launch
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
    }

    fun dispose() {
        disposed = true
        loadSeq++
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
