package com.tether.app.ui.usage

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.client.AccountUsage
import com.tether.app.client.AccountWindow
import com.tether.app.client.AccountsUsage
import com.tether.app.client.ClaudeResetGrantsReading
import com.tether.app.client.ServiceOpenSource
import com.tether.app.client.UsageCall
import com.tether.app.client.UsageFailure
import com.tether.app.client.UsageSource
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * T9.2: the Accounts dialog's words (components/usage-accounts-dialog.tsx), pure so every branch is
 * tested against the web's copy.
 */
object UsageAccountsModel {
    /** usage-accounts-dialog.tsx:90 (STALE_TTL_MS in lib/claude-plan-usage.mjs). */
    const val USAGE_STALE_MS = 10 * 60 * 1000.0

    /** usage-accounts-dialog.tsx:472: the bounded auto-retry after a failed (non per-card) load. */
    val AUTO_RETRY_DELAYS_MS = listOf(3_000L, 8_000L, 20_000L)

    /** `lastReadingAge`: "4m ago", "3h ago", "2d ago", else the date. */
    fun lastReadingAge(at: Double, now: Double, env: UsageEnv): String {
        val minutes = max(0.0, floor((now - at) / 60_000)).toLong()
        if (minutes < 60) return "${minutes}m ago"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h ago"
        val days = hours / 24
        if (days < 7) return "${days}d ago"
        return UsageFormat.monthDay(at, env)
    }

    /** `durationLabel`: a window with no named meter, by its duration ("1 day", "12 hour", "90 min"). */
    fun durationLabel(minutes: Double): String = when {
        minutes >= 24 * 60 && minutes % (24 * 60) == 0.0 -> "${UsageFormat.js(minutes / (24 * 60))} day"
        minutes >= 60 && minutes % 60 == 0.0 -> "${UsageFormat.js(minutes / 60)} hour"
        else -> "${UsageFormat.js(minutes)} min"
    }

    /** `sourceLabel`: only the Claude values. */
    fun sourceLabel(source: String?): String? = when (source) {
        "session" -> "live session"
        "http" -> "polled"
        else -> null
    }

    /** The card's rows: 5 hour, Weekly, Fable, then every other window by its duration. */
    fun rows(entry: AccountUsage): List<Pair<String, AccountWindow>> = buildList {
        val w = entry.windows
        w?.fiveHour?.let { add("5 hour" to it) }
        w?.weekly?.let { add("Weekly" to it) }
        w?.fable?.let { add("Fable" to it) }
        w?.other.orEmpty().forEach { add(durationLabel(it.windowMinutes) to it) }
    }

    /** Issue #166: a plan without a 5-hour window says so (never a fabricated 0% row). */
    fun noFiveHour(entry: AccountUsage): String? {
        val rows = rows(entry)
        val windows = entry.windows
        if (rows.isEmpty() || windows?.fiveHour != null) return null
        return if (windows?.weekly != null && windows.other.isEmpty()) "No 5-hour limit on this plan — weekly only." else "No 5-hour limit on this plan."
    }

    enum class NoteTone { Expired, Throttled, Stale, Fresh }

    /** The card's status line, in the web's priority order (usage-accounts-dialog.tsx:231-254). */
    fun note(entry: AccountUsage, generatedAt: Double, env: UsageEnv): Pair<String, NoteTone>? {
        val at = entry.at?.takeIf { it > 0 }
        val stale = at != null && generatedAt - at > USAGE_STALE_MS
        val label = sourceLabel(entry.source)
        return when {
            entry.tokenExpired -> (
                if (at != null) "Token expired — reading frozen at ${lastReadingAge(at, generatedAt, env)}, until this account's CLI next runs"
                else "Token expired — no reading until this account's CLI next runs"
                ) to NoteTone.Expired
            entry.throttledByGuard -> {
                val secs = retrySeconds(entry.retryAfter, generatedAt)
                (
                    if (at != null) "Asked Anthropic moments ago — try again in ${secs}s${if (stale) "; last reading ${lastReadingAge(at, generatedAt, env)}" else ""}"
                    else "Asked Anthropic moments ago — try again in ${secs}s"
                    ) to NoteTone.Throttled
            }
            at != null && (stale || entry.rateLimited) ->
                "${if (entry.rateLimited) "Rate-limited on refresh — " else ""}showing last reading${if (stale) " as of ${lastReadingAge(at, generatedAt, env)}" else ""}" to NoteTone.Stale
            at != null && label != null -> "Updated ${lastReadingAge(at, generatedAt, env)} · $label" to NoteTone.Fresh
            else -> null
        }
    }

    /** The card without rows (usage-accounts-dialog.tsx:291-299). */
    fun emptyText(entry: AccountUsage, generatedAt: Double, fallback: String): String = when {
        entry.tokenExpired -> "Token expired and no reading is cached yet — this account will report once its CLI next runs."
        entry.throttledByGuard -> "Just asked Anthropic — try again in ${retrySeconds(entry.retryAfter, generatedAt)}s."
        entry.rateLimited -> "Refresh is rate-limited and no reading is cached yet — try again later."
        else -> fallback
    }

    private fun retrySeconds(retryAfter: Double?, generatedAt: Double): Long = max(1.0, ceil(((retryAfter ?: 0.0) - generatedAt) / 1000)).toLong()

    /** `ClaudeResetGrants`' note (usage-accounts-dialog.tsx:144-155). */
    fun grantsNote(reading: ClaudeResetGrantsReading, generatedAt: Double, env: UsageEnv): String? {
        val at = reading.at.takeIf { it > 0 }
        return when {
            reading.tokenExpired -> if (at != null) "Token expired — resets as of ${lastReadingAge(at, generatedAt, env)}; this account needs a CLI call to read again"
            else "Token expired — this account needs a CLI call before its resets can be read"
            reading.rateLimited -> if (at != null) "Rate-limited — showing resets as of ${lastReadingAge(at, generatedAt, env)}" else "Rate-limited — resets not read yet"
            reading.unavailable -> "Resets not read — no Claude Code CLI version could be resolved to identify as"
            reading.throttledByGuard && at == null -> "Resets not read yet — try again in a moment"
            else -> null
        }
    }

    /** `DEEPSEEK_SOURCE_LABELS` / `deepSeekSourceList`. */
    fun deepSeekSources(sources: List<String>): String = sources.joinToString(", ") {
        when (it) {
            "env" -> "server env"
            "dsh" -> "dsh"
            "pi" -> "Pi"
            "opencode" -> "OpenCode"
            "reasonix" -> "Reasonix"
            else -> it
        }
    }

    /** A JSON object as the ported view helpers read it. */
    fun js(element: kotlinx.serialization.json.JsonElement?): JsValue = if (element == null) JsNull else JsCodec.fromJson(element)

    /** The Codex fallback when a card has no rows (usage-accounts-dialog.tsx:659-663). */
    fun codexFallback(entry: AccountUsage): String =
        if (entry.source == "unavailable") "No Codex usage available right now — confirm Codex is logged in, then retry." else "No rate-limit data reported yet."

    /** The words for a failed load (the web's: a transport failure, or the server's HTTP error). Null = another server's. */
    fun loadError(failure: UsageFailure): String? = when (failure) {
        UsageFailure.Unreachable -> "Could not reach Tether right now."
        is UsageFailure.Http -> "Tether's server answered with an error (HTTP ${failure.code})."
        is UsageFailure.Unusable -> "Could not load usage."
        is UsageFailure.Blocked -> "${UsageDashboardModel.GATEWAY}."
        UsageFailure.SignedOut -> "Not signed in to Tether."
        UsageFailure.LocalNetworkBlocked -> ServiceOpenSource.LOCAL_NETWORK
        UsageFailure.OtherServer -> null
    }
}

/**
 * The Accounts dialog's state (usage-accounts-dialog.tsx `UsageAccountsDialog`). It outlives the
 * dialog, as the web's `<dialog>` does: a reopen shows the last reading while it refetches.
 *
 * [refresh]: no [force] = the plain open (cache-first on the server); "all" = the header Refresh
 * (forces every account); an account id = that card's update key (forces just it, spins only it,
 * never retries on its own). A failed plain or "all" load retries after 3, 8 and 20 s, then stops;
 * a manual refresh or a close cancels a pending retry.
 */
@Stable
class UsageAccountsState(
    private val scope: CoroutineScope,
    private val source: () -> UsageSource,
    private val origin: () -> String?,
) {
    var visible by mutableStateOf(false)
        private set
    var data by mutableStateOf<AccountsUsage?>(null)
        private set
    var error by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var retrying by mutableStateOf(false)
        private set
    var refreshingId by mutableStateOf<String?>(null)
        private set

    private var retryJob: Job? = null
    private var retryAttempt = 0

    /** The only way the dialog is shown: it shows and starts a live fetch. */
    fun open() {
        visible = true
        refresh()
    }

    fun close() {
        cancelRetry()
        retryAttempt = 0
        visible = false
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
        retrying = false
    }

    fun refresh(force: String? = null) {
        cancelRetry()
        val perCard = force != null && force != "all"
        if (perCard) refreshingId = force else loading = true
        val asked = origin()
        scope.launch {
            try {
                when (val call = source().accounts(asked, force)) {
                    is UsageCall.Ok -> {
                        data = call.value
                        error = ""
                        retryAttempt = 0
                    }
                    is UsageCall.Failed -> {
                        val text = UsageAccountsModel.loadError(call.failure) ?: return@launch
                        error = text
                        if (perCard) return@launch
                        val delays = UsageAccountsModel.AUTO_RETRY_DELAYS_MS
                        if (retryAttempt < delays.size) {
                            val wait = delays[retryAttempt]
                            retryAttempt += 1
                            retrying = true
                            retryJob = scope.launch {
                                delay(wait)
                                refresh()
                            }
                        } else {
                            retrying = false
                        }
                    }
                }
            } finally {
                if (perCard) refreshingId = null else loading = false
            }
        }
    }

    /** Test seam: a payload as if the server had answered (goldens). */
    internal fun seed(payload: AccountsUsage?, error: String = "", retrying: Boolean = false, loading: Boolean = false, refreshingId: String? = null) {
        data = payload
        this.error = error
        this.retrying = retrying
        this.loading = loading
        this.refreshingId = refreshingId
        visible = true
    }
}
