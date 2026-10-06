package com.tether.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.tether.app.client.ClaudeAccount
import com.tether.app.client.ClaudeAccountActions
import com.tether.app.client.ClaudeAccountAlias
import com.tether.app.client.ClaudeAccountsJson
import com.tether.app.client.ClaudeAccountsSource
import com.tether.app.client.ClaudeAccountsSync
import com.tether.app.client.ClaudeLoginCode
import com.tether.app.client.ClaudeLoginLink
import com.tether.app.client.ClaudeLoginStatus
import com.tether.app.client.ChromeIntents
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

    /** The login link opens in the phone's browser (the web opens it in a new tab). */
    fun openCaption(host: String) = "Complete sign-in in your browser ($host), then come back here and paste the code."

    /** settings-dialog.tsx :1765 the web's caption, said for a link that is not an http(s) address (no host to name). */
    const val OPEN_CAPTION_WEB = "Complete sign-in in your browser, then come back here."

    /** ta-coik.17: a link a browser never opens from a page either ([com.tether.app.client.ClaudeLoginLink.NOT_OPENED], or not a URL). */
    const val LINK_REFUSED = "The server sent a sign-in link that a browser will not open (a javascript:, data:, file: or content: address, or not an address at all)."
    const val LINK_UNOPENED = "No browser on this phone could open the link."
    const val LINK_UNOPENED_APP = "No app on this phone could open the link."
    /** r2 (security P3-1). */
    const val NOT_ANTHROPIC = "This is not an Anthropic sign-in address. Open it only if you expected this server to send you there."
    const val LOGIN_GONE = "This login is no longer running on the server. Start it again."

    /**
     * The owner-grade refusal of a server without tether #236 (it says "needs an owner sign-in"): said
     * as the web shows a change's error; nothing is disabled, and the next change clears it.
     */
    const val OWNER_NEEDED = "This server is older than the app's owner sign-ins (tether #236), so it asks for an owner sign-in here. Update the server to change Claude accounts from the app."

    /** settings-dialog.tsx:1806 the armed Remove key, and CLAUDE_ACCOUNT_REMOVE_ARM_MS (:1189). */
    const val CONFIRM_REMOVE = "Confirm remove"
    const val REMOVE_ARM_MS = 4_000L
    /** r2 (security P4-4): the armed key names the profile id when the card shows it (look-alike titles). */
    fun confirmRemoveLabel(title: String, idLine: String?) = "Confirm remove $title" + (idLine?.let { ", profile ${LabelText.visibleValue(it)}" } ?: "")

    fun removed(title: String) = "Removed $title."
    fun removedKeptLogin(title: String) = "Removed $title, but its stored login was not deleted: Tether deletes only the logins it keeps itself."
    fun notRemoved(title: String) = "$title is the machine's own Claude login: it stays listed."

    const val SIGNED_OUT = "Signed out — sign in again to change Claude accounts."
    const val LOCAL_NETWORK = "Local network access is blocked"
    const val NOT_SENT_OTHER = "Nothing was sent: the app is now signed in to another server."
    const val NOT_SENT = "Nothing was sent."
    fun blocked(code: Int) = "A sign-in page answered instead of Tether (HTTP $code). Nothing was sent past it."

    // ta-89k: the Terminal alias rows (settings-dialog.tsx 90fbb9f :1810-1832, :1639-1648).
    const val ALIAS_TITLE = "Terminal alias"
    const val ALIAS_CAPTION = "Run this same account directly from a terminal, outside Tether"
    const val ALIAS_SHOW = "Show"
    const val ALIAS_HIDE = "Hide"
    const val ALIAS_LOADING = "Loading alias…"
    const val ALIAS_FAILED = "Could not load the alias for this account."
    const val ALIAS_SNIPPET_LEAD = "Add this once to your "
    const val ALIAS_SNIPPET_MID = " to keep aliases in sync automatically: "
    const val COPY = "Copy"
    const val COPIED = "Copied"
    /** `window.setTimeout(…, 1500)` in `copyAlias`. */
    const val COPIED_MS = 1_500L
}

/** settings-dialog.tsx `aliases[id]`: `"loading"`, `"error"`, or the alias. */
sealed interface AliasState {
    data object Loading : AliasState
    data object Failed : AliasState
    data class Shown(val alias: ClaudeAccountAlias) : AliasState
}

/** What a write is (each row's own busy key, as on the web: settings-dialog.tsx addBusy, renameBusyId, removeBusyId, logoutBusyId, login busy, saving, running). */
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

/** Opens a login link outside the app (the phone's browser). False when nothing could. */
fun interface LoginLinkOpener {
    fun open(link: ClaudeLoginLink): Boolean

    companion object {
        val None = LoginLinkOpener { false }

        /**
         * `ACTION_VIEW` + `CATEGORY_BROWSABLE` on the link read ([ClaudeLoginLink]): an http(s) one in
         * the phone's browser, no app credential with it (the T15.7 / CompatibilityBanner pattern);
         * ta-coik.17: another scheme to the app that takes it, as a phone's browser hands a page's
         * link on ([intentFor]).
         */
        fun browser(context: Context) = LoginLinkOpener { link ->
            // ta-coik.18 r2: an `intent:` link as Chrome opens it (the shared ChromeIntents: its web
            // fallback or its package's store page when no app takes it); nothing about it crashes the tap.
            if (!link.web && ChromeIntents.isIntentLink(link.url)) {
                ChromeIntents.open(context, link.url, newTask = true) { web -> ChromeIntents.openView(context, web, newTask = true) }
            } else {
                // r4 (ta-qap9): as Chrome hands a page's link on, never to this app's own non-exported activity.
                ChromeIntents.openView(context, link.url, newTask = true)
            }
        }

        /**
         * The intent a phone's browser starts for a page's link to [link]: `ACTION_VIEW` +
         * `CATEGORY_BROWSABLE`; an `intent:` link as Chrome reads and sanitises it ([ChromeIntents.parse],
         * shared with the inspector's pull request link). Null: an `intent:` link that does not
         * parse, or whose data is a scheme Chrome refuses ([ChromeIntents.REFUSED_DATA_SCHEMES]:
         * content, file and Chrome's own internal schemes). [browser] opens such a link's web fallback.
         */
        // TEST/INSPECTION ONLY (r5): never started (it skips the non-exported check and the fallback);
        // [browser] opens links only through ChromeIntents.open / openView. Its fallback extra is dropped.
        fun intentFor(link: ClaudeLoginLink): Intent? =
            if (!link.web && ChromeIntents.isIntentLink(link.url)) ChromeIntents.parse(link.url) else ChromeIntents.view(link.url)

        /** Chrome's ExternalNavigationHandler ALLOWED_INTENT_FLAGS ([ChromeIntents.ALLOWED_INTENT_FLAGS]). */
        const val ALLOWED_INTENT_FLAGS = ChromeIntents.ALLOWED_INTENT_FLAGS
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
    /** The account whose Remove is armed (the web's "Confirm remove"). */
    val armedRemove: String? = null,
)

/** The login poll's pace (settings-dialog.tsx `startLogin` / `pollLogin`); as on the web, it has no limit. */
data class LoginPollPace(val first: Long = 500L, val next: Long = 1_500L, val afterFailure: Long = 2_000L)

/**
 * ta-7rh: the Claude accounts section's state and calls for ONE server ([origin]): the reads
 * ta-9q2 made (moved here unchanged) and the web's changes. Held by the section (the Engines tab), as
 * the web's ClaudeAccountsSection is mounted only on its tab; never saved state, so a rotation starts
 * with nothing and sends nothing but the opening reads.
 *
 * - Each row's change has its own busy key, as on the web ([busy], set in the tap's own frame: a
 *   second tap on the same key sends nothing; other rows stay usable); each change is followed by a
 *   re-read, as on the web. Nothing is retried.
 * - Every answer must be about [origin]; any other is dropped. Every change is sent for [origin] only
 *   (the source refuses another server).
 * - Log out is sent at once and Remove is the web's two-tap arm ([armedRemove], 4 s), no app-only
 *   confirmation (the owner's standing rule: the app does what the web does, and asks no more).
 * - A server without #236 answers a change 403 "needs an owner sign-in": [ownerNeeded] says so, as the
 *   web shows a change's error; nothing is disabled, and the next change clears it.
 * - A login runs a poll loop per account, stopped by its generation (cancel or a fresh start), a
 *   success, the server's error, idle, or the section closing; never on a clock (the web's has no
 *   limit). Only Cancel tells the server to drop it. The pasted code lives in
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

    /** The busy keys ("Action:id"), each set in the tap's own frame. */
    private var inFlight: Set<String> by mutableStateOf(emptySet())
    /** Sync saves in flight (the web lets one follow another; "Saving…" while any is). */
    var syncSaves: Int by mutableIntStateOf(0)
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
    var armedRemove: String? by mutableStateOf(writeSeed?.armedRemove)
        private set
    private var armJob: Job? = null

    /** ta-89k: settings-dialog.tsx `aliasOpenId`, `aliases`, `aliasCopiedId` (read-only, so not a change: no busy key, no owner note). */
    var aliasOpen: String? by mutableStateOf(null)
        private set
    var aliases: Map<String, AliasState> by mutableStateOf(emptyMap())
        private set
    var aliasCopied: String? by mutableStateOf(null)
        private set

    private val generations = HashMap<String, Int>()
    // r3 (ta-9q2): the ids being asked, checked and set synchronously at click time (main thread
    // only). An id stays here until the frame AFTER its answer lands, so a second same-frame tap is
    // refused however fast the first read returns.
    private val asked = HashSet<String>()

    /**
     * Whether changes may be made at all: signed in. The web's "Add Claude account" is there while the
     * list loads and after it failed (settings-dialog.tsx:1838-1862); every other change sits on a
     * card or the sync rows, which are drawn only once the list (or the sync state) is in.
     */
    val canChange: Boolean get() = origin != null

    /** Whether [action] on [id] is in flight (its key then says so and is drawn disabled, as the web's). */
    fun busy(action: AccountsAction, id: String? = null): Boolean = key(action, id) in inFlight

    private fun key(action: AccountsAction, id: String?) = "${action.name}:${id.orEmpty()}"

    fun reload() {
        reloads++
    }

    /**
     * r2 (verifier P2): fold a list answer into the state as it is NOW. The caller fetches first and
     * hands the answer in; nothing suspends between this read of [state] and its write, so a change
     * that landed while the read was in flight (a sync toggle, a Sync now result) is kept.
     */
    fun foldList(answer: com.tether.app.client.ClaudeAccountsResult<List<ClaudeAccount>>) {
        state = ClaudeAccountsModel.foldList(state, answer, origin)
    }

    /** As [foldList], for the sync GET. */
    fun foldSync(answer: com.tether.app.client.ClaudeAccountsResult<ClaudeAccountsSync>) {
        state = ClaudeAccountsModel.foldSync(state, answer, origin)
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
                // r2: the answer first, then the state as it is when it lands.
                val answer = source.status(id)
                state = ClaudeAccountsModel.foldStatus(state, id, answer, o)
                withFrameNanos { }
            } finally {
                asked -= id
            }
        }
    }

    // ---- Terminal alias (ta-89k) ---------------------------------------------------------------

    /**
     * settings-dialog.tsx `toggleAlias` (90fbb9f :1630-1643): Hide closes; Show opens one account's
     * (closing any other) and fetches its alias unless it is already shown or loading: a failed one is
     * asked again. Any failure is the one sentence ([ClaudeAccountsCopy.ALIAS_FAILED]).
     */
    fun toggleAlias(id: String) {
        if (aliasOpen == id) {
            aliasOpen = null
            return
        }
        aliasOpen = id
        val existing = aliases[id]
        if (existing != null && existing != AliasState.Failed) return
        val o = origin
        if (o == null) {
            aliases = aliases + (id to AliasState.Failed)
            return
        }
        aliases = aliases + (id to AliasState.Loading)
        scope.launch {
            val next = try {
                val r = source.alias(id)
                if (r.origin != null && r.origin != o) return@launch
                if (r is com.tether.app.client.ClaudeAccountsResult.Ok) AliasState.Shown(r.value) else AliasState.Failed
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AliasState.Failed
            }
            aliases = aliases + (id to next)
        }
    }

    /** `copyAlias`: after the clipboard took the line, "Copied" for 1.5 s (each copy its own timer, as the web's). */
    fun aliasWasCopied(id: String) {
        aliasCopied = id
        scope.launch {
            delay(ClaudeAccountsCopy.COPIED_MS)
            if (aliasCopied == id) aliasCopied = null
        }
    }

    // ---- Add and Rename --------------------------------------------------------------------------

    fun openAdd() {
        adding = true
        line = null
    }

    /**
     * ta-coik.20: what was typed before a rotation (the Add nickname and the rename in progress) put
     * back into a fresh controller; the host mirrors these (saved, none is a secret). Sends nothing.
     */
    fun restoreTyped(adding: Boolean, addText: String, renaming: String?, renameText: String) {
        if (adding) this.adding = true
        if (addText.isNotEmpty()) this.addText = addText
        if (renaming != null) {
            this.renaming = renaming
            this.renameText = renameText
        }
    }

    fun cancelAdd() {
        adding = false
        addText = ""
        line = null
    }

    fun editAdd(text: String) {
        addText = text
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
        renameText = text
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

    // ---- Remove (the web's two-tap arm) and Log out (sent at once, as on the web) ----------------------

    fun setDeleteCredentials(id: String, on: Boolean) {
        deleteCredentials = deleteCredentials + (id to on)
    }

    /**
     * settings-dialog.tsx `armRemove`: the first tap arms this account's Remove ("Confirm remove", for
     * [ClaudeAccountsCopy.REMOVE_ARM_MS]); a second tap while armed removes it, with the "Also delete
     * stored login" choice as it is then (the web's `performRemove`). Arming another account moves the arm.
     */
    fun tapRemove(account: ClaudeAccount, title: String): Boolean {
        if (!canChange || !account.checkable || busy(AccountsAction.Remove, account.id)) return false
        if (armedRemove == account.id) {
            disarm()
            return remove(account.id, title)
        }
        armJob?.cancel()
        armedRemove = account.id
        armJob = scope.launch {
            delay(ClaudeAccountsCopy.REMOVE_ARM_MS)
            if (armedRemove == account.id) armedRemove = null
        }
        return false
    }

    private fun disarm() {
        armJob?.cancel()
        armJob = null
        armedRemove = null
    }

    private fun remove(id: String, title: String): Boolean {
        val delete = deleteCredentials[id] == true
        return write(AccountsAction.Remove, id) { o ->
            val r = actions.remove(o, id, delete)
            if (!mine(r)) return@write
            if (r is SecurityResult.Ok) {
                stopLogin(id)
                deleteCredentials = deleteCredentials - id
                lines = lines - id
                line = AccountsLine(
                    when {
                        !r.value.removed -> ClaudeAccountsCopy.notRemoved(title)
                        delete && !r.value.credentialsDeleted -> ClaudeAccountsCopy.removedKeptLogin(title)
                        else -> ClaudeAccountsCopy.removed(title)
                    },
                    error = false,
                )
            } else {
                settle(r, ClaudeAccountsCopy.REMOVE_FAILED) { lines = lines + (id to it) }
            }
            reload()
        }
    }

    /** settings-dialog.tsx `doLogout`: sent at once (the web asks nothing first), then the status is read again. */
    fun logout(account: ClaudeAccount): Boolean {
        if (!account.checkable) return false
        val id = account.id
        return write(AccountsAction.Logout, id) { o ->
            val r = actions.logout(o, id)
            if (!mine(r)) return@write
            if (r is SecurityResult.Ok) {
                lines = lines - id
                check(id)
            } else {
                settle(r, ClaudeAccountsCopy.LOGOUT_FAILED) { lines = lines + (id to it) }
            }
        }
    }

    // ---- Log in ------------------------------------------------------------------------------------

    /**
     * settings-dialog.tsx `startLogin`: a fresh generation, the start, then the poll (500 ms, then
     * 1.5 s). As on the web, Log in after a Cancel starts again even while the cancelled start is
     * still in flight: each start is its own busy key (its generation), and the stale answer is
     * dropped without a word to the server. r3 (security F1): a tap that sends nothing changes
     * nothing (the checks come first, and a refused write puts the panel and generation back).
     */
    fun startLogin(account: ClaudeAccount): Boolean {
        val id = account.id
        if (origin == null || !canChange || !account.checkable || logins.containsKey(id)) return false
        val before = generations[id]
        val gen = (before ?: 0) + 1
        // In the tap's own frame: the panel shows "Starting…" and a second tap starts nothing.
        generations[id] = gen
        val linesBefore = lines
        lines = lines - id
        logins = logins + (id to LoginPanel(ClaudeLoginStatus.PendingUrl, starting = true))
        val sent = write(AccountsAction.Login, id, keyOf = "$id#$gen") { o ->
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
        if (!sent) {
            if (before == null) generations -= id else generations[id] = before
            lines = linesBefore
            logins = logins - id
        }
        return sent
    }

    /**
     * settings-dialog.tsx `pollLogin` (:1561-1581): polled for as long as the login runs, with no
     * limit (the web has none, nor does the server). It stops on success, on the server's error
     * status, on idle (nothing runs any more), on Cancel or a fresh start (the generation), or when
     * the section closes. It never tells the server to drop the login: only Cancel does, as on the web.
     */
    private fun poll(o: String, id: String, gen: Int) {
        scope.launch { pollUntilEnd(o, id, gen) }
    }

    /** The poll loop; returns when the login ended (or is no longer this generation's). */
    private suspend fun pollUntilEnd(o: String, id: String, gen: Int) {
        var wait = pace.first
        while (true) {
            delay(wait)
            if (generations[id] != gen) return
            val r = actions.pollLogin(o, id)
            if (generations[id] != gen || !mine(r)) return
            when (r) {
                is SecurityResult.Ok -> when (r.value.status) {
                    ClaudeLoginStatus.Success -> {
                        dropLogin(id)
                        check(id)
                        return
                    }
                    ClaudeLoginStatus.Error -> return endLogin(id, r.value.error?.let(LabelText::error)?.takeIf { it.isNotEmpty() } ?: ClaudeAccountsCopy.LOGIN_FAILED)
                    // Nothing runs on the server any more.
                    ClaudeLoginStatus.Idle -> return endLogin(id, ClaudeAccountsCopy.LOGIN_GONE)
                    else -> {
                        val now = logins[id] ?: return
                        logins = logins + (id to now.copy(status = r.value.status, link = r.value.link ?: now.link, linkRefused = r.value.linkRefused || (now.linkRefused && r.value.link == null), starting = false))
                        wait = pace.next
                    }
                }
                // A failed fetch: the web's `catch` polls again after 2 s.
                is SecurityResult.Unavailable, SecurityResult.LocalNetworkBlocked -> wait = pace.afterFailure
                // A JSON refusal (a 4xx: 401, 403, 404, 409, 429 …) has no status: the web reads it as
                // `data.status ?? "pending-url"`, keeps the link, and polls again after 1.5 s (:1576-1577).
                is SecurityResult.Refused, is SecurityResult.SignedOut, is SecurityResult.OwnerSignInNeeded -> {
                    val now = logins[id] ?: return
                    logins = logins + (id to now.copy(status = ClaudeLoginStatus.PendingUrl, starting = false))
                    wait = pace.next
                }
                // Not JSON (a sign-in page or redirect): the web's `res.json()` fails into
                // `{ status: "error" }`, which ends the panel (:1565, :1572-1575); nothing is cancelled.
                else -> return endLogin(id, failure(r, ClaudeAccountsCopy.LOGIN_FAILED))
            }
        }
    }

    /** The panel shows why it ended; the code goes (the web hides the field then). Nothing is sent. */
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
        if (ClaudeAccountsJson.isAccountId(id)) tellCancel(o, id)
    }

    /** The best-effort cancel (the web's `try { DELETE } catch {}`): its answer changes nothing here. */
    private fun tellCancel(o: String, id: String) {
        scope.launch {
            try {
                actions.cancelLogin(o, id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // best effort
            }
        }
    }

    fun editCode(id: String, text: String) {
        if (!logins.containsKey(id)) return
        codes = codes + (id to text)
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
     * ClaudeAccountSyncSection's `save`: shown at once, then the server's answer; a failed save puts
     * back what the server had.
     *
     * r3 (security F2): [change] is applied to the config as it is NOW (not as it was drawn), and the
     * optimistic value lands in the tap's own frame, before anything is launched: two toggles in one
     * frame each build on the other, so the second never undoes the first on the server.
     */
    fun saveSync(change: (ClaudeSyncConfig) -> ClaudeSyncConfig): Boolean {
        val current = state.sync ?: return false
        val next = change(current.config)
        if (current.config.mode == ClaudeSyncMode.Unknown || next.mode == ClaudeSyncMode.Unknown || next == current.config) return false
        if (origin == null || !canChange) return false
        state = state.copy(sync = current.copy(config = next))
        val before = current
        val sent = write(AccountsAction.SyncSave, null, exclusive = false) { o ->
            syncLine = null
            val r = actions.saveSync(o, next)
            if (!mine(r) || state.origin != o) return@write
            if (r is SecurityResult.Ok) {
                state = state.copy(sync = ClaudeAccountsSync(r.value.config, r.value.result))
            } else {
                // r2: only the config goes back (to the server's), on the state as it is now.
                val now = state.sync
                if (now?.config == next) state = state.copy(sync = now.copy(config = before.config))
                settle(r, ClaudeAccountsCopy.SYNC_SAVE_FAILED) { syncLine = it }
            }
        }
        if (!sent && state.sync?.config == next) state = state.copy(sync = state.sync?.copy(config = before.config))
        return sent
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

    /**
     * One change: refused only when signed out, or when this very key ([keyOf]) is in flight
     * ([exclusive]; a sync save may follow another, as on the web). A change clears the owner note (the
     * web clears a change's error when it starts); a refusal puts it back.
     */
    private fun write(action: AccountsAction, id: String?, exclusive: Boolean = true, keyOf: String? = id, call: suspend (String) -> Unit): Boolean {
        val o = origin ?: return false
        if (!canChange) return false
        val k = key(action, keyOf)
        if (exclusive && k in inFlight) return false
        ownerNeeded = false
        if (exclusive) inFlight = inFlight + k else syncSaves++
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
                if (exclusive) inFlight = inFlight - k else syncSaves--
            }
        }
        return true
    }
}

/** settings-dialog.tsx `claudeAccountNickname`: `Claude Code (<nickname>)` gives the nickname back; any other label stays whole. */
internal object Nickname {
    private val SHAPE = Regex("^Claude Code \\((.*)\\)$", RegexOption.DOT_MATCHES_ALL)

    fun of(label: String): String {
        return SHAPE.matchEntire(label)?.groupValues?.get(1) ?: label
    }
}
