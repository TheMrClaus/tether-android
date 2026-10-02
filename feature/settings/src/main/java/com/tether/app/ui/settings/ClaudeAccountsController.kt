package com.tether.app.ui.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.tether.app.client.ClaudeAccount
import com.tether.app.client.ClaudeAccountActions
import com.tether.app.client.ClaudeAccountsJson
import com.tether.app.client.ClaudeAccountsSource
import com.tether.app.client.ClaudeAccountsSync
import com.tether.app.client.ClaudeLoginCode
import com.tether.app.client.ClaudeLoginLink
import com.tether.app.client.ClaudeLoginStatus
import com.tether.app.client.ClaudeSyncConfig
import com.tether.app.client.ClaudeSyncMode
import com.tether.app.client.LabelText
import com.tether.app.client.SecurityResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** ta-7rh: the section's words for the changes (the web's, where it has them). */
object ClaudeAccountsCopy {
    const val ADD = "Add Claude account"
    const val ADD_KEY = "Add"
    const val ADDING = "Adding…"
    const val NICKNAME = "Nickname"
    const val NICKNAME_CAPTION = "A short label for the new account, e.g. \"work\""
    const val NICKNAME_FIELD = "New Claude account nickname"
    const val RENAME_CAPTION = "Changes the display name only — the account's login and terminal alias are unchanged"
    const val SAVE = "Save"
    const val SAVING = "Saving…"
    const val CANCEL = "Cancel"
    const val PLACEHOLDER = "work"
    const val LOG_IN = "Log in"
    const val LOG_OUT = "Log out"
    const val LOGGING_OUT = "Logging out…"
    const val REMOVE = "Remove"
    const val REMOVING = "Removing…"
    const val ALSO_DELETE = "Also delete stored login"
    const val LOGIN_TITLE = "Login in progress"
    const val LOGIN_WAITING = "Waiting for you to open the link…"
    const val LOGIN_PASTE = "Paste the code Anthropic gave you below."
    const val LOGIN_FAILED = "The login failed."
    const val LOGIN_STARTING = "Starting…"
    const val LOGIN_SIGNED_IN = "Signed in."
    const val OPEN_TITLE = "Open this link"
    const val OPEN = "Open"
    const val CODE_TITLE = "Authorization code"
    const val CODE_CAPTION = "Paste the code Anthropic shows after you approve access."
    const val CODE_PLACEHOLDER = "paste code here"
    const val SUBMIT = "Submit code"
    const val SUBMITTING = "Submitting…"
    const val SYNC_NOW = "Sync now"
    const val SYNCING = "Syncing…"
    const val SYNC_SAVE_FAILED = "Could not save sync settings."
    const val SYNC_FAILED = "Sync failed."
    const val ADD_FAILED = "Could not add that account."
    const val RENAME_FAILED = "Could not rename that account."
    const val REMOVE_FAILED = "Could not remove that account."
    const val LOGOUT_FAILED = "Could not log out."
    const val START_FAILED = "Could not start the login."
    const val CODE_FAILED = "That code was not accepted."

    /** Native: the login link opens in the phone's browser, never in the app. */
    fun openCaption(host: String) = "Complete sign-in in your browser ($host), then come back here and paste the code."
    const val LINK_REFUSED = "The server sent a sign-in link that is not a plain https address, so the app will not open it. Finish this login from the web console."
    const val LINK_UNOPENED = "No browser on this phone could open the link."
    const val LOGIN_GONE = "This login is no longer running on the server. Start it again."
    const val LOGIN_TOO_LONG = "The login took too long and was stopped here. Start it again."

    /**
     * The owner-grade refusal of a server without tether #236 (it says "needs an owner sign-in"): said
     * once, with the one way to try again.
     */
    const val OWNER_NEEDED = "This server has not been updated yet to let the app change Claude accounts: it asks for an owner sign-in. Once the server is updated this works from the phone like the web; until then, use the web console."
    const val TRY_AGAIN = "Try again"

    const val REMOVE_TITLE = "Remove Claude account?"
    fun removeBody(title: String) = "$title is removed from Tether. Sessions can no longer use it."
    const val REMOVE_ALSO_LOGIN = "Its stored login is deleted too: signing in again needs a new login."
    const val REMOVE_KEEPS_LOGIN = "Its stored login stays on the server."
    const val REMOVE_HOST_DEFAULT = "This is the machine's own Claude login: Tether lists it again on the next read and never deletes its login."
    const val REMOVE_CONFIRM = "Remove"
    const val LOGOUT_TITLE = "Log out of this Claude account?"
    fun logoutBody(title: String) = "$title is signed out on the server. Sessions using it stop working until it is logged in again."
    const val LOGOUT_CONFIRM = "Log out"

    fun removed(title: String) = "Removed $title."
    fun removedKeptLogin(title: String) = "Removed $title, but its stored login was not deleted: Tether deletes only the logins it keeps itself."
    fun notRemoved(title: String) = "$title is the machine's own Claude login: it stays listed."

    const val SIGNED_OUT = "Signed out — sign in again to change Claude accounts."
    const val LOCAL_NETWORK = "Local network access is blocked"
    const val NOT_SENT_OTHER = "Nothing was sent: the app is now signed in to another server."
    const val NOT_SENT = "Nothing was sent."
    fun blocked(code: Int) = "A sign-in page answered instead of Tether (HTTP $code). Nothing was sent past it."
}

/** What a write is (one at a time, as each of the web's rows has its own busy key). */
enum class AccountsAction { Add, Rename, Remove, Logout, Login, Code, SyncSave, SyncRun }

/** A line under a card or the section: [error] is the web's `is-warning` note, else a quiet status. */
data class AccountsLine(val text: String, val error: Boolean)

/** One account's login panel (settings-dialog.tsx `ClaudeAccountLoginState`), never saved. */
data class LoginPanel(
    val status: ClaudeLoginStatus,
    val link: ClaudeLoginLink? = null,
    val linkRefused: Boolean = false,
    val error: String? = null,
    val starting: Boolean = false,
)

/** A confirmation the section asks before a destructive change (the web's two-tap arm, as a dialog). */
sealed interface AccountsConfirm {
    data class Remove(val id: String, val title: String, val deleteCredentials: Boolean, val hostDefault: Boolean) : AccountsConfirm
    data class Logout(val id: String, val title: String) : AccountsConfirm
}

/** Opens a login link outside the app (the phone's browser). False when nothing could. */
fun interface LoginLinkOpener {
    fun open(link: ClaudeLoginLink): Boolean

    companion object {
        val None = LoginLinkOpener { false }

        /**
         * `ACTION_VIEW` + `CATEGORY_BROWSABLE` on the checked https URL ([ClaudeLoginLink]): the
         * phone's browser, no app credential with it (the T15.7 / CompatibilityBanner pattern).
         * Uri.parse, not core-ktx's toUri: this module does not depend on androidx.core (ExternalLinks' rule).
         */
        @SuppressLint("UseKtx")
        fun browser(context: Context) = LoginLinkOpener { link ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }
}

/** The goldens' seam for the writes' state (timing-free; nothing is sent while it stands). */
data class AccountsWriteSeed(
    val ownerNeeded: Boolean = false,
    val adding: Boolean = false,
    val addText: String = "",
    val renaming: String? = null,
    val renameText: String = "",
    val logins: Map<String, LoginPanel> = emptyMap(),
    val deleteCredentials: Map<String, Boolean> = emptyMap(),
    val lines: Map<String, AccountsLine> = emptyMap(),
    val line: AccountsLine? = null,
    val confirm: AccountsConfirm? = null,
)

/** The login poll's pace (settings-dialog.tsx `startLogin` / `pollLogin`), and how long it is kept up here. */
data class LoginPollPace(val first: Long = 500L, val next: Long = 1_500L, val afterFailure: Long = 2_000L, val limitMs: Long = 15 * 60_000L)

/**
 * ta-7rh: the Claude accounts section's state and calls for ONE server ([origin]): the reads
 * ta-9q2 made (moved here unchanged) and the web's changes. Held by the section (the Engines tab), as
 * the web's ClaudeAccountsSection is mounted only on its tab; never saved state, so a rotation starts
 * with nothing and sends nothing but the opening reads.
 *
 * - One change in flight at a time ([busy] set in the tap's own frame: a second tap sends nothing);
 *   each is followed by a re-read, as on the web. Nothing is retried.
 * - Every answer must be about [origin]; any other is dropped. Every change is sent for [origin] only
 *   (the source refuses another server).
 * - Remove and Log out are asked first ([confirm]).
 * - A server without #236 answers a change 403 "needs an owner sign-in": [ownerNeeded], said once,
 *   and the change controls rest until Try again.
 * - A login runs a poll loop per account, stopped by its generation (cancel or a fresh start), a
 *   success, an error, the section closing, or [LoginPollPace.limitMs]. The pasted code lives in
 *   [codes] only until it is handed over, cancelled or the section closes.
 */
@Stable
class ClaudeAccountsController(
    private val source: ClaudeAccountsSource,
    private val actions: ClaudeAccountActions,
    val origin: String?,
    parent: CoroutineScope,
    seed: ClaudeAccountsState? = null,
    writeSeed: AccountsWriteSeed? = null,
    private val pace: LoginPollPace = LoginPollPace(),
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)

    var state: ClaudeAccountsState by mutableStateOf(seed ?: if (origin == null) ClaudeAccountsModel.signedOut() else ClaudeAccountsState())
    /** Bumped to read the list again (Retry, and after every change). */
    var reloads: Int by mutableIntStateOf(0)
        private set

    var busy: AccountsAction? by mutableStateOf(null)
        private set
    var busyId: String? by mutableStateOf(null)
        private set
    var ownerNeeded: Boolean by mutableStateOf(writeSeed?.ownerNeeded == true)
        private set
    var adding: Boolean by mutableStateOf(writeSeed?.adding == true)
        private set
    var addText: String by mutableStateOf(writeSeed?.addText.orEmpty())
    var renaming: String? by mutableStateOf(writeSeed?.renaming)
        private set
    var renameText: String by mutableStateOf(writeSeed?.renameText.orEmpty())
    var logins: Map<String, LoginPanel> by mutableStateOf(writeSeed?.logins.orEmpty())
        private set
    /** The pasted codes, per account: in memory only, dropped once handed over, on cancel and on close. */
    var codes: Map<String, String> by mutableStateOf(emptyMap())
        private set
    var deleteCredentials: Map<String, Boolean> by mutableStateOf(writeSeed?.deleteCredentials.orEmpty())
        private set
    /** A line under one card (its rename, remove, log-out or login failure). */
    var lines: Map<String, AccountsLine> by mutableStateOf(writeSeed?.lines.orEmpty())
        private set
    /** The section's own line (Add, a removal's outcome). */
    var line: AccountsLine? by mutableStateOf(writeSeed?.line)
        private set
    var syncLine: AccountsLine? by mutableStateOf(null)
        private set
    var confirm: AccountsConfirm? by mutableStateOf(writeSeed?.confirm)
        private set

    private val generations = HashMap<String, Int>()
    // r3 (ta-9q2): the ids being asked, checked and set synchronously at click time (main thread
    // only). An id stays here until the frame AFTER its answer lands, so a second same-frame tap is
    // refused however fast the first read returns.
    private val asked = HashSet<String>()

    /** Whether a change may start now (the keys are drawn enabled only then). */
    val canChange: Boolean get() = origin != null && !ownerNeeded && busy == null && state.listFault == null && state.accounts != null

    fun reload() {
        reloads++
    }

    // ---- reads (ta-9q2, unchanged) ---------------------------------------------------------------

    /** `checkStatus(id)`: asked only when the state moved to Checking, never twice for one id in one frame. */
    fun check(id: String) {
        val o = origin ?: return
        if (id in asked) return
        val next = ClaudeAccountsModel.checking(state, id)
        if (next === state) return
        asked += id
        state = next
        scope.launch {
            try {
                state = ClaudeAccountsModel.foldStatus(state, id, source.status(id), o)
                withFrameNanos { }
            } finally {
                asked -= id
            }
        }
    }

    // ---- Add and Rename --------------------------------------------------------------------------

    fun openAdd() {
        adding = true
        line = null
    }

    fun cancelAdd() {
        adding = false
        addText = ""
        line = null
    }

    fun editAdd(text: String) {
        if (text.length <= ClaudeAccountActions.NICKNAME_MAX) addText = text
        if (line?.error == true) line = null
    }

    /** settings-dialog.tsx `submitAdd`: a blank nickname sends nothing. */
    fun submitAdd(): Boolean {
        val nickname = addText.trim()
        if (nickname.isEmpty()) return false
        return write(AccountsAction.Add, null) { o ->
            val r = actions.add(o, nickname)
            if (!mine(r)) return@write
            if (r is SecurityResult.Ok) {
                addText = ""
                adding = false
                reload()
            } else {
                settle(r, ClaudeAccountsCopy.ADD_FAILED) { line = it }
            }
        }
    }

    fun startRename(account: ClaudeAccount) {
        if (account.id == ClaudeAccountsPresentation.HOST_DEFAULT_ID || !account.checkable) return
        renaming = account.id
        renameText = Nickname.of(LabelText.withoutHidden(account.label))
        lines = lines - account.id
    }

    fun cancelRename() {
        renaming = null
        renameText = ""
    }

    fun editRename(text: String) {
        if (text.length <= ClaudeAccountActions.NICKNAME_MAX) renameText = text
        renaming?.let { id -> if (lines[id]?.error == true) lines = lines - id }
    }

    /** settings-dialog.tsx `submitRename`. */
    fun submitRename(): Boolean {
        val id = renaming ?: return false
        val nickname = renameText.trim()
        if (nickname.isEmpty()) return false
        return write(AccountsAction.Rename, id) { o ->
            val r = actions.rename(o, id, nickname)
            if (!mine(r)) return@write
            if (r is SecurityResult.Ok) {
                if (renaming == id) cancelRename()
                reload()
            } else {
                settle(r, ClaudeAccountsCopy.RENAME_FAILED) { lines = lines + (id to it) }
            }
        }
    }

    // ---- Remove and Log out (asked first) -----------------------------------------------------------

    fun setDeleteCredentials(id: String, on: Boolean) {
        deleteCredentials = deleteCredentials + (id to on)
    }

    fun askRemove(account: ClaudeAccount, title: String) {
        if (!canChange || !account.checkable) return
        confirm = AccountsConfirm.Remove(account.id, title, deleteCredentials[account.id] == true, account.id == ClaudeAccountsPresentation.HOST_DEFAULT_ID)
    }

    fun askLogout(account: ClaudeAccount, title: String) {
        if (!canChange || !account.checkable) return
        confirm = AccountsConfirm.Logout(account.id, title)
    }

    fun dismissConfirm() {
        confirm = null
    }

    /** The confirmation's key: what it SHOWS is what is sent. */
    fun confirmed(shown: AccountsConfirm): Boolean {
        if (confirm != shown) return false
        confirm = null
        return when (shown) {
            is AccountsConfirm.Remove -> remove(shown)
            is AccountsConfirm.Logout -> logout(shown)
        }
    }

    private fun remove(c: AccountsConfirm.Remove): Boolean = write(AccountsAction.Remove, c.id) { o ->
        val r = actions.remove(o, c.id, c.deleteCredentials)
        if (!mine(r)) return@write
        if (r is SecurityResult.Ok) {
            stopLogin(c.id)
            deleteCredentials = deleteCredentials - c.id
            lines = lines - c.id
            line = AccountsLine(
                when {
                    !r.value.removed -> ClaudeAccountsCopy.notRemoved(c.title)
                    c.deleteCredentials && !r.value.credentialsDeleted -> ClaudeAccountsCopy.removedKeptLogin(c.title)
                    else -> ClaudeAccountsCopy.removed(c.title)
                },
                error = false,
            )
        } else {
            settle(r, ClaudeAccountsCopy.REMOVE_FAILED) { lines = lines + (c.id to it) }
        }
        reload()
    }

    private fun logout(c: AccountsConfirm.Logout): Boolean = write(AccountsAction.Logout, c.id) { o ->
        val r = actions.logout(o, c.id)
        if (!mine(r)) return@write
        if (r is SecurityResult.Ok) {
            lines = lines - c.id
            check(c.id)
        } else {
            settle(r, ClaudeAccountsCopy.LOGOUT_FAILED) { lines = lines + (c.id to it) }
        }
    }

    // ---- Log in ------------------------------------------------------------------------------------

    /** settings-dialog.tsx `startLogin`: a fresh generation, the start, then the poll (500 ms, then 1.5 s). */
    fun startLogin(account: ClaudeAccount): Boolean {
        val id = account.id
        if (!account.checkable || logins.containsKey(id)) return false
        if (!canChange) return false
        // In the tap's own frame: the panel shows "Starting…" and a second tap starts nothing.
        val gen = (generations[id] ?: 0) + 1
        generations[id] = gen
        lines = lines - id
        logins = logins + (id to LoginPanel(ClaudeLoginStatus.PendingUrl, starting = true))
        return write(AccountsAction.Login, id) { o ->
            val r = actions.startLogin(o, id)
            if (!mine(r) || generations[id] != gen) return@write
            when (r) {
                is SecurityResult.Ok -> {
                    logins = logins + (id to LoginPanel(r.value.status, r.value.link, r.value.linkRefused))
                    poll(o, id, gen)
                }
                is SecurityResult.OwnerSignInNeeded -> {
                    dropLogin(id)
                    ownerNeeded = true
                }
                else -> logins = logins + (id to LoginPanel(ClaudeLoginStatus.Error, error = failure(r, ClaudeAccountsCopy.START_FAILED)))
            }
        }
    }

    private fun poll(o: String, id: String, gen: Int) {
        scope.launch {
            var wait = pace.first
            var spent = 0L
            while (spent < pace.limitMs) {
                delay(wait)
                spent += wait
                if (generations[id] != gen) return@launch
                val r = actions.pollLogin(o, id)
                if (generations[id] != gen || !mine(r)) return@launch
                when (r) {
                    is SecurityResult.Ok -> when (r.value.status) {
                        ClaudeLoginStatus.Success -> {
                            dropLogin(id)
                            check(id)
                            return@launch
                        }
                        ClaudeLoginStatus.Error -> return@launch endLogin(id, r.value.error?.let(LabelText::error)?.takeIf { it.isNotEmpty() } ?: ClaudeAccountsCopy.LOGIN_FAILED)
                        ClaudeLoginStatus.Idle -> return@launch endLogin(id, ClaudeAccountsCopy.LOGIN_GONE)
                        else -> {
                            val now = logins[id] ?: return@launch
                            logins = logins + (id to now.copy(status = r.value.status, link = r.value.link ?: now.link, linkRefused = r.value.linkRefused || (now.linkRefused && r.value.link == null), starting = false))
                            wait = pace.next
                        }
                    }
                    is SecurityResult.Unavailable, SecurityResult.LocalNetworkBlocked -> wait = pace.afterFailure
                    is SecurityResult.OwnerSignInNeeded -> {
                        dropLogin(id)
                        ownerNeeded = true
                        return@launch
                    }
                    else -> return@launch endLogin(id, failure(r, ClaudeAccountsCopy.LOGIN_FAILED))
                }
            }
            if (generations[id] == gen) endLogin(id, ClaudeAccountsCopy.LOGIN_TOO_LONG)
        }
    }

    /** The panel shows why it ended; the code goes (the web hides the field then). */
    private fun endLogin(id: String, why: String) {
        codes = codes - id
        val now = logins[id] ?: LoginPanel(ClaudeLoginStatus.Error)
        logins = logins + (id to now.copy(status = ClaudeLoginStatus.Error, error = why, starting = false))
    }

    private fun dropLogin(id: String) {
        logins = logins - id
        codes = codes - id
    }

    /** A login of an account no longer here: its loop ends and nothing is sent. */
    private fun stopLogin(id: String) {
        generations[id] = (generations[id] ?: 0) + 1
        dropLogin(id)
    }

    /** settings-dialog.tsx `cancelLogin`: the loop ends at once; the server is told (best effort, not a change). */
    fun cancelLogin(id: String) {
        val o = origin ?: return
        if (!logins.containsKey(id)) return
        stopLogin(id)
        if (ClaudeAccountsJson.isAccountId(id)) scope.launch { runCatching { actions.cancelLogin(o, id) } }
    }

    fun editCode(id: String, text: String) {
        if (!logins.containsKey(id)) return
        if (text.length <= ClaudeAccountActions.CODE_MAX) codes = codes + (id to text)
        logins[id]?.let { if (it.error != null && it.status != ClaudeLoginStatus.Error) logins = logins + (id to it.copy(error = null)) }
    }

    /** settings-dialog.tsx `submitCode`: the code is dropped from here the moment the server took it. */
    fun submitCode(id: String): Boolean {
        val text = codes[id]?.trim().orEmpty()
        val panel = logins[id] ?: return false
        if (text.isEmpty() || panel.status == ClaudeLoginStatus.Error) return false
        return write(AccountsAction.Code, id) { o ->
            val r = actions.submitCode(o, id, ClaudeLoginCode(text))
            if (!mine(r)) return@write
            val now = logins[id] ?: return@write
            when (r) {
                is SecurityResult.Ok -> {
                    codes = codes - id
                    logins = logins + (id to now.copy(status = ClaudeLoginStatus.AwaitingCode, error = null))
                }
                is SecurityResult.OwnerSignInNeeded -> {
                    stopLogin(id)
                    ownerNeeded = true
                }
                else -> logins = logins + (id to now.copy(error = failure(r, ClaudeAccountsCopy.CODE_FAILED)))
            }
        }
    }

    // ---- Sync ------------------------------------------------------------------------------------------

    /**
     * ClaudeAccountSyncSection's `save`: shown at once, then the server's answer. Native: a failed save
     * puts back what was there (the web keeps its optimistic value beside the error).
     */
    fun saveSync(next: ClaudeSyncConfig): Boolean {
        val before = state.sync ?: return false
        if (before.config.mode == ClaudeSyncMode.Unknown || next.mode == ClaudeSyncMode.Unknown || next == before.config) return false
        return write(AccountsAction.SyncSave, null) { o ->
            syncLine = null
            state = state.copy(sync = before.copy(config = next))
            val r = actions.saveSync(o, next)
            if (!mine(r) || state.origin != o) return@write
            if (r is SecurityResult.Ok) {
                state = state.copy(sync = ClaudeAccountsSync(r.value.config, r.value.result))
            } else {
                if (state.sync?.config == next) state = state.copy(sync = before)
                settle(r, ClaudeAccountsCopy.SYNC_SAVE_FAILED) { syncLine = it }
            }
        }
    }

    /** "Sync now" (`disabled={running || config.mode === "none"}`). */
    fun runSync(): Boolean {
        val sync = state.sync ?: return false
        if (sync.config.mode == ClaudeSyncMode.None) return false
        return write(AccountsAction.SyncRun, null) { o ->
            syncLine = null
            val r = actions.runSync(o)
            if (!mine(r) || state.origin != o) return@write
            if (r is SecurityResult.Ok) {
                state = state.copy(sync = ClaudeAccountsSync(r.value.config, r.value.result))
            } else {
                settle(r, ClaudeAccountsCopy.SYNC_FAILED) { syncLine = it }
            }
        }
    }

    // ---- the owner-grade refusal, and the rest ------------------------------------------------------------

    /** "Try again": the change controls are offered again (the next change asks the server again). */
    fun retryOwner() {
        ownerNeeded = false
    }

    /** The section closed or the server changed: every loop ends, every code goes, nothing answers here. */
    fun dispose() {
        generations.keys.toList().forEach { generations[it] = (generations[it] ?: 0) + 1 }
        codes = emptyMap()
        job.cancel()
    }

    private fun mine(r: SecurityResult<*>): Boolean = origin != null && (r.origin == null || r.origin == origin)

    /** A failed change: the owner refusal is the section's state; everything else a warning line. */
    private inline fun settle(r: SecurityResult<*>, fallback: String, put: (AccountsLine) -> Unit) {
        if (r is SecurityResult.OwnerSignInNeeded) {
            ownerNeeded = true
            return
        }
        put(AccountsLine(failure(r, fallback), error = true))
    }

    private fun failure(r: SecurityResult<*>, fallback: String): String = when (r) {
        is SecurityResult.Ok -> fallback
        is SecurityResult.OwnerSignInNeeded -> ClaudeAccountsCopy.OWNER_NEEDED
        is SecurityResult.SignedOut -> ClaudeAccountsCopy.SIGNED_OUT
        is SecurityResult.Refused -> LabelText.error(r.message).ifEmpty { fallback }
        SecurityResult.LocalNetworkBlocked -> ClaudeAccountsCopy.LOCAL_NETWORK
        is SecurityResult.Blocked -> ClaudeAccountsCopy.blocked(r.code)
        is SecurityResult.NotSent -> if (r.origin != null && r.origin != origin) ClaudeAccountsCopy.NOT_SENT_OTHER else ClaudeAccountsCopy.NOT_SENT
        is SecurityResult.Unavailable -> fallback
    }

    private fun write(action: AccountsAction, id: String?, call: suspend (String) -> Unit): Boolean {
        val o = origin ?: return false
        if (!canChange) return false
        busy = action
        busyId = id
        scope.launch {
            try {
                call(o)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never silent: the web's generic failure for that change.
                val generic = AccountsLine(ClaudeAccountsCopy.NOT_SENT, error = true)
                if (id != null) lines = lines + (id to generic) else line = generic
            } finally {
                busy = null
                busyId = null
            }
        }
        return true
    }
}

/** settings-dialog.tsx `claudeAccountNickname`: `Claude Code (<nickname>)` gives the nickname back; any other label stays whole. */
internal object Nickname {
    private val SHAPE = Regex("^Claude Code \\((.*)\\)$", RegexOption.DOT_MATCHES_ALL)

    fun of(label: String): String {
        val nickname = SHAPE.matchEntire(label)?.groupValues?.get(1) ?: label
        return if (nickname.length <= ClaudeAccountActions.NICKNAME_MAX) nickname else com.tether.app.client.TextCut.cut(nickname, ClaudeAccountActions.NICKNAME_MAX)
    }
}
