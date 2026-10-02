package com.tether.app.ui.settings

import com.tether.app.client.ClaudeAccount
import com.tether.app.client.ClaudeAccountActions
import com.tether.app.client.ClaudeAccountPlanSource
import com.tether.app.client.ClaudeAccountRefusal
import com.tether.app.client.ClaudeAccountStatus
import com.tether.app.client.ClaudeAccountsResult
import com.tether.app.client.ClaudeAccountsSource
import com.tether.app.client.ClaudeAccountsSync
import com.tether.app.client.ClaudeSyncMode
import com.tether.app.client.ClaudeSyncResult
import com.tether.app.client.LabelText

/**
 * What the Engines tab needs for Claude accounts: the client's read-only source, its changes
 * ([actions], ta-7rh), and the canonical origin of the server it is signed in to (null when signed
 * out). Every answer about any other origin is dropped ([ClaudeAccountsModel]); every change is sent
 * for [origin] only. [timeOf] draws a sync time (the web's `toLocaleTimeString`; a seam for the
 * goldens).
 *
 * [initial] (the goldens' seam, as `initialPreferences` is SettingsFrame's): a state already built
 * for [origin]. The section's first frame is then that state, and it does not read the list or
 * the sync state on opening (Retry and Check still ask the source). Null (the app): it reads both.
 */
data class ClaudeAccountsBinding(
    val source: ClaudeAccountsSource,
    val origin: String?,
    val timeOf: (Long) -> String = ::localTime,
    val initial: ClaudeAccountsState? = null,
    /** ta-7rh: the owner-grade changes; [ClaudeAccountActions.Unavailable] sends nothing. */
    val actions: ClaudeAccountActions = ClaudeAccountActions.Unavailable,
    /** ta-7rh: where a login link is opened (the phone's browser); [LoginLinkOpener.None] opens nothing. */
    val opener: LoginLinkOpener = LoginLinkOpener.None,
    /** ta-7rh, the goldens' seam: the changes' state beside [initial] (taken only with it). */
    val writeSeed: AccountsWriteSeed? = null,
    /** ta-7rh: the login poll's pace (a seam for the tests). */
    val pace: LoginPollPace = LoginPollPace(),
) {
    companion object {
        /** No client (previews): nothing is fetched and the section says it is signed out. */
        val None = ClaudeAccountsBinding(ClaudeAccountsSource.Unavailable, null)
    }
}

/** Why the last read gave no value. */
sealed interface AccountsFault {
    data object SignedOut : AccountsFault
    data object Forbidden : AccountsFault
    data object LocalNetworkBlocked : AccountsFault

    /** T6.8: a sign-in gateway answered instead of Tether. */
    data class Blocked(val code: Int) : AccountsFault

    /** r2: one of the status route's own refusals, recognised by the reader; drawn as fixed copy. */
    data class Refused(val reason: ClaudeAccountRefusal) : AccountsFault

    data class Unavailable(val code: Int?) : AccountsFault
}

/** One account's `statuses[id]` (settings-dialog.tsx:1383): being checked, the answer, or why there is none. */
sealed interface AccountStatusState {
    data object Checking : AccountStatusState
    data class Known(val status: ClaudeAccountStatus) : AccountStatusState
    data class Failed(val fault: AccountsFault) : AccountStatusState
}

/**
 * ClaudeAccountsSection's state (settings-dialog.tsx:1380-1400), for ONE server ([origin]).
 * [accounts] null = not loaded yet. [listFault] wins over [accounts] (the web's `listError ?` first).
 * Nothing here is ever persisted: it lives while the Engines tab is open.
 */
data class ClaudeAccountsState(
    val origin: String? = null,
    val accounts: List<ClaudeAccount>? = null,
    val listFault: AccountsFault? = null,
    val statuses: Map<String, AccountStatusState> = emptyMap(),
    val sync: ClaudeAccountsSync? = null,
)

/**
 * ta-9q2: the section's state changes, pure. Native additions to the web (HostUsageModel.fold's
 * rule): an answer is taken only when it is about [current] (the shown server's canonical origin);
 * an answer about another server than the state's own starts from nothing; signed out or refused
 * (401/403) drops the list rather than keeping it beside the refusal.
 */
object ClaudeAccountsModel {
    /** settings-dialog.tsx:1422: the one plan re-read, 4 s after a first paint from the offline snapshot. */
    const val PLAN_RETRY_MS = 4_000L

    private fun <T> stale(state: ClaudeAccountsState, result: ClaudeAccountsResult<T>, current: String?): Boolean =
        current == null || (result.origin != null && result.origin != current)

    fun fault(result: ClaudeAccountsResult<*>): AccountsFault? = when (result) {
        is ClaudeAccountsResult.Ok -> null
        is ClaudeAccountsResult.SignedOut -> AccountsFault.SignedOut
        is ClaudeAccountsResult.Forbidden -> AccountsFault.Forbidden
        ClaudeAccountsResult.LocalNetworkBlocked -> AccountsFault.LocalNetworkBlocked
        is ClaudeAccountsResult.Blocked -> AccountsFault.Blocked(result.code)
        is ClaudeAccountsResult.Refused -> AccountsFault.Refused(result.reason)
        is ClaudeAccountsResult.Unavailable -> AccountsFault.Unavailable(result.code)
    }

    /** No server to ask: the section says so (and holds nothing from a server it no longer shows). */
    fun signedOut(): ClaudeAccountsState = ClaudeAccountsState(listFault = AccountsFault.SignedOut)

    /** settings-dialog.tsx:1408-1426 `loadAccounts`. */
    fun foldList(state: ClaudeAccountsState, result: ClaudeAccountsResult<List<ClaudeAccount>>, current: String?): ClaudeAccountsState {
        if (stale(state, result, current)) return state
        val kept = if (state.origin != current) ClaudeAccountsState(origin = current) else state
        return when (result) {
            is ClaudeAccountsResult.Ok -> kept.copy(accounts = result.value, listFault = null)
            is ClaudeAccountsResult.SignedOut, is ClaudeAccountsResult.Forbidden ->
                ClaudeAccountsState(origin = current, listFault = fault(result))
            else -> kept.copy(listFault = fault(result))
        }
    }

    /** settings-dialog.tsx:1422: re-read once when any plan is still the offline snapshot. */
    fun wantsPlanRetry(accounts: List<ClaudeAccount>?): Boolean =
        accounts.orEmpty().any { it.plan?.source == ClaudeAccountPlanSource.Credentials }

    /**
     * `checkStatus(id)`'s "loading", only for an account of this server that may be asked. r2: an
     * account already being checked is refused (the SAME state comes back), so two taps in one frame
     * ask once: each status read runs `claude auth status` on the server. A caller asks the source
     * only when this returns a new state.
     */
    fun checking(state: ClaudeAccountsState, id: String): ClaudeAccountsState = when {
        state.statuses[id] == AccountStatusState.Checking -> state
        state.accounts.orEmpty().none { it.id == id && it.checkable } -> state
        else -> state.copy(statuses = state.statuses + (id to AccountStatusState.Checking))
    }

    /** settings-dialog.tsx:1443-1453 `checkStatus`: the answer, or the reason as the status. */
    fun foldStatus(state: ClaudeAccountsState, id: String, result: ClaudeAccountsResult<ClaudeAccountStatus>, current: String?): ClaudeAccountsState {
        if (stale(state, result, current) || state.origin != current) return state
        if (state.accounts.orEmpty().none { it.id == id }) return state
        val next = when (result) {
            is ClaudeAccountsResult.Ok -> AccountStatusState.Known(result.value)
            else -> AccountStatusState.Failed(fault(result)!!)
        }
        return state.copy(statuses = state.statuses + (id to next))
    }

    /**
     * ClaudeAccountSyncSection's `loadSync` (settings-dialog.tsx:1250-1259). A failed read keeps what
     * was there (the web keeps `config` and shows its error only beside one; a first failure leaves
     * the section hidden).
     */
    fun foldSync(state: ClaudeAccountsState, result: ClaudeAccountsResult<ClaudeAccountsSync>, current: String?): ClaudeAccountsState {
        if (stale(state, result, current) || state.origin != current) return state
        return if (result is ClaudeAccountsResult.Ok) state.copy(sync = result.value) else state
    }

    /** settings-dialog.tsx:1864: the sync section is mounted once two accounts are listed. */
    fun wantsSync(state: ClaudeAccountsState): Boolean = state.listFault == null && (state.accounts?.size ?: 0) >= 2
}

/**
 * ta-9q2: what the Claude accounts section shows, derived as settings-dialog.tsx:1380-1867 derives
 * it, with every server string through the text rules: labels, names, the email and sentences by the
 * label rule ([LabelText]); the CLAUDE_CONFIG_DIR is handed on raw and drawn by the one-line path
 * rule where the web shows it (the Sign-in row). `plan.raw` does not exist on this side.
 */
object ClaudeAccountsPresentation {
    const val TITLE = "Claude accounts"
    const val INTRO = "Log a second (or third) Anthropic identity into Tether without leaving the app. Each account gets its own dedicated credential home, separate from your default Claude login."
    const val LOADING = "Loading Claude accounts…"
    const val LIST_ERROR = "Could not load Claude accounts."
    const val STATUS_ERROR = "Status check failed."
    const val NO_HOME = "No credential home yet — created on first login"
    const val PRE_EXISTING = "Pre-existing"
    const val PRE_EXISTING_TIP = "This account existed on your machine before Tether — Tether can drive it but does not own its credential directory"
    const val BLOCKED = "Blocked by a sign-in page"
    const val BLOCKED_DETAIL = "A sign-in gateway (SSO or a proxy) answered instead of Tether. Exempt /api/claude-accounts for paired devices."

    /** lib/claude-accounts.mjs HOST_DEFAULT_ACCOUNT_ID: no Rename (settings-dialog.tsx:1688). */
    const val HOST_DEFAULT_ID = "claude-default"

    /** One `.engine-card` (settings-dialog.tsx:1680-1836). */
    data class Card(
        val id: String,
        /** The label (or, when it has nothing visible, the id spelled out). */
        val title: String,
        /** `plan.label`: the tag after the title. */
        val planTag: String?,
        /** `plan.organizationName`: its own line under the status. */
        val organization: String?,
        val preExisting: Boolean,
        val status: String,
        val checking: Boolean,
        val canCheck: Boolean,
        val canRename: Boolean,
        /** Raw CLAUDE_CONFIG_DIR (bounded), drawn by the path rule; null = [NO_HOME]. */
        val configDir: String?,
        /**
         * r2 (ta-895's rule): the raw profile id, drawn by the one-line rule under the title, set only
         * when this card's title could pass for another's ([LookAlike]); null = not drawn.
         */
        val idLine: String? = null,
    )

    /** The list error row or the blocked notice (settings-dialog.tsx:1666-1670, plus T6.8's native notice). */
    data class Notice(val blocked: Boolean, val text: String, val detail: String? = null)

    data class Value(val title: String, val caption: String, val value: String)

    /** ClaudeAccountSyncSection, read only (settings-dialog.tsx:1241-1376). */
    data class SyncView(val rows: List<Value>, val warning: String?, val summary: String, val canRun: Boolean)

    data class View(val notice: Notice?, val loading: Boolean, val cards: List<Card>, val sync: SyncView?)

    fun view(state: ClaudeAccountsState, timeOf: (Long) -> String): View {
        val fault = state.listFault
        if (fault != null) {
            val notice = if (fault is AccountsFault.Blocked) Notice(true, BLOCKED, BLOCKED_DETAIL) else Notice(false, listError(fault))
            return View(notice, loading = false, cards = emptyList(), sync = null)
        }
        val accounts = state.accounts ?: return View(null, loading = true, cards = emptyList(), sync = null)
        return View(null, loading = false, cards = cards(accounts, state.statuses), sync = sync(accounts, state.sync, timeOf))
    }

    /** The cards, each with its id drawn when its title could pass for another card's. */
    fun cards(accounts: List<ClaudeAccount>, statuses: Map<String, AccountStatusState>): List<Card> {
        val cards = accounts.map { card(it, statuses[it.id]) }
        val alike = LookAlike.collisions(cards.map { it.title })
        return cards.mapIndexed { i, c -> if (i in alike) c.copy(idLine = c.id) else c }
    }

    fun card(account: ClaudeAccount, status: AccountStatusState?): Card = Card(
        id = account.id,
        title = LabelText.title(account.label, LabelText.MAX_LABEL).ifEmpty { LabelText.visibleValue(account.id) },
        planTag = account.plan?.label?.let(LabelText::label)?.takeIf { it.isNotEmpty() },
        organization = account.plan?.organizationName?.let(LabelText::label)?.takeIf { it.isNotEmpty() },
        preExisting = account.imported,
        status = statusCopy(status),
        checking = status == AccountStatusState.Checking,
        canCheck = account.checkable && status != AccountStatusState.Checking,
        canRename = account.id != HOST_DEFAULT_ID,
        configDir = account.configDir?.takeIf { account.hasConfigDir },
    )

    /** settings-dialog.tsx:1653-1660 `statusCopy`. */
    fun statusCopy(status: AccountStatusState?): String = when (status) {
        null -> "Status unknown"
        AccountStatusState.Checking -> "Checking…"
        is AccountStatusState.Failed -> "Status unknown — ${statusError(status.fault)}"
        is AccountStatusState.Known -> {
            val s = status.status
            val error = s.error?.let(LabelText::error)?.takeIf { it.isNotEmpty() }
            val email = s.email?.let(LabelText::label)?.takeIf { it.isNotEmpty() }
            when {
                error != null -> "Status unknown — $error"
                s.loggedIn -> if (email != null) "Logged in — $email" else "Logged in"
                else -> "Not logged in"
            }
        }
    }

    /** `data.error || "Status check failed."`, and the native reasons the web has no words for. */
    fun statusError(fault: AccountsFault): String = when (fault) {
        is AccountsFault.Refused -> fault.reason.sentence
        AccountsFault.SignedOut -> "Signed out"
        AccountsFault.LocalNetworkBlocked -> "Local network access is blocked"
        is AccountsFault.Blocked -> BLOCKED
        AccountsFault.Forbidden, is AccountsFault.Unavailable -> STATUS_ERROR
    }

    /**
     * `data.error || "Could not load Claude accounts."`. r2: the list route has no refusal of its own,
     * so no server sentence is ever shown here; the fixed copy stands for every failure.
     */
    fun listError(fault: AccountsFault): String = when (fault) {
        is AccountsFault.Refused -> LIST_ERROR
        AccountsFault.SignedOut -> "Signed out — sign in again to see Claude accounts."
        AccountsFault.LocalNetworkBlocked -> "Local network access is blocked"
        is AccountsFault.Blocked -> BLOCKED
        AccountsFault.Forbidden, is AccountsFault.Unavailable -> LIST_ERROR
    }

    /** settings-dialog.tsx:1216-1220 SYNC_MODE_OPTIONS. */
    fun modeLabel(mode: ClaudeSyncMode): String = when (mode) {
        ClaudeSyncMode.All -> "Sync everything"
        ClaudeSyncMode.Selected -> "Sync selected categories"
        ClaudeSyncMode.None -> "Don't sync"
        ClaudeSyncMode.Unknown -> "Unknown"
    }

    /**
     * ClaudeAccountSyncSection: shown once two sync-eligible accounts are listed and the config is
     * read (settings-dialog.tsx:1309 `syncCapableAccounts.length < 2 || !config` → nothing).
     */
    fun sync(accounts: List<ClaudeAccount>, sync: ClaudeAccountsSync?, timeOf: (Long) -> String): SyncView? {
        if (accounts.size < 2 || sync == null) return null
        val capable = accounts.filter { it.syncEligible }
        if (capable.size < 2) return null
        val config = sync.config
        val primaryMissing = config.mode != ClaudeSyncMode.None && config.primaryAccountId == null
        val rows = buildList {
            add(Value("Sync across accounts", "Share plugins, skills, MCP servers, and hooks between your Claude accounts", modeLabel(config.mode)))
            if (config.mode == ClaudeSyncMode.Selected) {
                val c = config.categories
                val on = listOfNotNull("Plugins".takeIf { c.plugins }, "Skills".takeIf { c.skills }, "MCP servers".takeIf { c.mcp }, "Hooks".takeIf { c.hooks })
                add(Value("Categories", "Only these are kept in sync", on.joinToString(", ").ifEmpty { "None" }))
            }
            if (config.mode != ClaudeSyncMode.None) {
                val primary = capable.firstOrNull { it.id == config.primaryAccountId }
                val titles = accounts.map { card(it, null).title }
                val alike = LookAlike.collisions(titles)
                val value = when {
                    // r2: a title that could pass for another's names its id too (escaped, one line).
                    primary != null -> accounts.indexOf(primary).let { i ->
                        if (i in alike) "${titles[i]} · ${LabelText.visibleValue(primary.id)}" else titles[i]
                    }
                    primaryMissing -> "Choose a primary account…"
                    else -> "None"
                }
                add(Value("Primary account", "Its plugins/skills/hooks/MCP servers are what the others receive", value))
            }
        }
        return SyncView(
            rows = rows,
            warning = if (primaryMissing) {
                "No primary account is set (its previous primary may have been removed) — synced content is still backed up, but nothing new will sync until you choose one."
            } else null,
            summary = summary(sync.lastResult, timeOf),
            // `disabled={running || config.mode === "none"}` (ta-7rh: and a mode this client does not know).
            canRun = config.mode != ClaudeSyncMode.None && config.mode != ClaudeSyncMode.Unknown,
        )
    }

    /** settings-dialog.tsx:1229-1239 `summarizeSyncResult`. */
    fun summary(result: ClaudeSyncResult?, timeOf: (Long) -> String): String {
        if (result == null) return "Never synced yet."
        when (result.status) {
            "disabled" -> return "Sync is off."
            "not-enough-accounts" -> return "Needs at least 2 accounts."
            "no-primary" -> return "Choose a primary account below."
            "error" -> return "Last sync failed: ${result.error?.let(LabelText::error)?.takeIf { it.isNotEmpty() } ?: "unknown error"}"
        }
        val whenText = result.ranAt?.let(timeOf).orEmpty()
        return "Last synced ${if (whenText.isNotEmpty()) "at $whenText — " else ""}${result.changed} updated, ${result.upToDate} already current."
    }
}

/**
 * r2: whether drawn account titles could pass for one another, so a card names its profile id
 * (ta-895's New session picker rule, there for exact duplicates). Two titles collide when their
 * SKELETONS are equal, a small fold in the spirit of UTS #39 confusable detection (the app has no
 * ICU SpoofChecker): NFKC, combining marks dropped, case folded, the Cyrillic and Greek letters
 * that draw like Latin ones mapped to them, `0`→`o`, `1` / `i` / `|`→`l`, `rn`→`m`, `vv`→`w`, and
 * spaces and punctuation ignored. It catches the usual look-alikes ("Work" twice, Cyrillic "Wоrk",
 * "W0rk", "Work " / "work"); it is not a full confusables table.
 */
object LookAlike {
    private val TO_LATIN: Map<Int, Char> = buildMap {
        fun put(from: String, to: String) = from.forEachIndexed { i, c -> put(c.code, to[i]) }
        // Cyrillic, lower and upper case.
        put("абвгеіїјкмнорстухѕԁӏԛԝүһ", "abbrelljkmhopctyxsdlqwyh")
        put("АВЕІЇЈКМНОРСТУХЅԀӀԚԜҮҺ", "abelljkmhopctyxsdlqwyh")
        // Greek.
        put("αβεικνορτυχ", "abelkvoptux")
        put("ΑΒΕΖΗΙΚΜΝΟΡΤΥΧ", "abezhlkmnoptyx")
        // Latin and digit look-alikes.
        put("ı0o1il|ſ", "loolllls")
    }

    fun skeleton(title: String): String {
        val decomposed = java.text.Normalizer.normalize(java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC), java.text.Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
            val mapped = TO_LATIN[cp] ?: TO_LATIN[Character.toLowerCase(cp)]
            when {
                mapped != null -> out.append(mapped)
                Character.isLetterOrDigit(cp) -> out.appendCodePoint(Character.toLowerCase(cp))
                else -> Unit
            }
        }
        return out.toString().replace("rn", "m").replace("vv", "w")
    }

    /** The indices of [titles] whose skeleton another title shares. */
    fun collisions(titles: List<String>): Set<Int> {
        val skeletons = titles.map(::skeleton)
        val counts = skeletons.groupingBy { it }.eachCount()
        return skeletons.indices.filterTo(HashSet()) { (counts[skeletons[it]] ?: 0) > 1 }
    }
}
