package com.tether.app.ui.usage

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.client.ServiceOpenSource
import com.tether.app.client.UsageAnalytics
import com.tether.app.client.UsageCall
import com.tether.app.client.UsageFailure
import java.time.Instant
import java.time.ZoneOffset

/** usage-dashboard.tsx:60-64 `RANGES`: the key, the label, the days (null = all) and the key's name. */
enum class UsageRange(val key: String, val label: String, val days: Int?, val description: String) {
    Week("7d", "7d", 7, "Last 7 days"),
    Month("30d", "30d", 30, "Last 30 days"),
    All("all", "All", null, "All time"),
    ;

    companion object {
        fun of(key: String?): UsageRange = entries.firstOrNull { it.key == key } ?: Month
    }
}

/**
 * usage-dashboard.tsx `UsageDashboard`'s state: the range (30d at first), the last payload, the last
 * error, and which range the payload is for. Loading is derived, as on the web: no error and either
 * no payload or a payload for another range. A new payload clears the error; an error keeps the
 * payload ("Showing the last available readings").
 */
@Stable
class UsageDashboardState(range: UsageRange = UsageRange.Month) {
    var range by mutableStateOf(range)
        private set
    var data by mutableStateOf<UsageAnalytics?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loadedRange by mutableStateOf<UsageRange?>(null)
        private set

    /** Bumped by Refresh and Try again (the web's `refresh` counter, part of the fetch effect's keys). */
    var refresh by mutableIntStateOf(0)
        private set

    val loading: Boolean get() = error == null && (data == null || loadedRange != range)

    /** A range key: clears the error, then the range's own fetch runs. */
    fun selectRange(next: UsageRange) {
        error = null
        range = next
    }

    /** Refresh / Try again: clears the error and fetches again. */
    fun refreshNow() {
        error = null
        refresh += 1
    }

    /** One answer for [forRange] (only ever the effect's current range; a stale one is never applied). */
    fun onResult(forRange: UsageRange, call: UsageCall<UsageAnalytics>) {
        when (call) {
            is UsageCall.Ok -> {
                data = call.value
                error = null
                loadedRange = forRange
            }
            is UsageCall.Failed -> UsageDashboardModel.errorText(call.failure)?.let { error = it }
        }
    }
}

object UsageDashboardModel {
    /** usage-dashboard.tsx:86: the page polls at the server TTL (USAGE_TTL_MS) while it is shown. */
    const val POLL_MS = 15_000L

    /** T6.8's case: a sign-in gateway answered instead of Tether (nothing of it is shown). */
    const val GATEWAY = "A sign-in page answered instead of Tether"

    /** `sinceParam`: the UTC date `days` days before [nowMs] ("" = all time). */
    fun sinceParam(days: Int?, nowMs: Long): String {
        if (days == null || days == 0) return ""
        return Instant.ofEpochMilli(nowMs - days * 86_400_000L).atZone(ZoneOffset.UTC).toLocalDate().toString()
    }

    /**
     * The web's error line for a failed load (`HTTP ${status}` for an answer, the browser's own
     * message for a fetch that never got through). Null = not this screen's answer (another server).
     */
    fun errorText(failure: UsageFailure): String? = when (failure) {
        is UsageFailure.Http -> "HTTP ${failure.code}"
        UsageFailure.Unreachable -> "Failed to fetch"
        is UsageFailure.Unusable -> "The server's answer could not be read"
        is UsageFailure.Blocked -> GATEWAY
        UsageFailure.SignedOut -> "Not signed in to Tether"
        UsageFailure.LocalNetworkBlocked -> ServiceOpenSource.LOCAL_NETWORK
        UsageFailure.OtherServer -> null
    }

    /** The status line under the header (usage-dashboard.tsx:150-152). */
    fun syncState(state: UsageDashboardState, env: UsageEnv): String = when {
        state.error != null -> "Update unavailable"
        state.loading -> "Scanning transcripts…"
        else -> state.data?.let { "Updated ${UsageFormat.clock(it.generatedAt, env)} · refreshes automatically" }.orEmpty()
    }

    /** The subtitle (usage-dashboard.tsx:140). */
    fun subtitle(data: UsageAnalytics?): String =
        if (data == null) "Your agents, measured." else "${UsageFormat.whole(data.totals.sessions)} sessions · ${UsageFormat.whole(data.totals.turns)} turns"

    /** The error banner (usage-dashboard.tsx:154). */
    fun errorBanner(error: String, hasData: Boolean): String =
        "Could not update usage. ${if (hasData) "Showing the last available readings. " else ""}$error"
}
