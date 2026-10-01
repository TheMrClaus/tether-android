package com.tether.app.ui.settings

import com.tether.app.client.ClaudeAccount
import com.tether.app.client.ClaudeAccountRefusal
import com.tether.app.client.ClaudeAccountStatus
import com.tether.app.client.ClaudeAccountsJson
import com.tether.app.client.ClaudeAccountsResult
import com.tether.app.client.ClaudeAccountsSource
import com.tether.app.client.ClaudeAccountsSync
import java.util.Collections
import kotlinx.coroutines.awaitCancellation

/** The seeded Claude-accounts state of the Engines goldens and tests (tether 887c222 shapes). */
object AccountsFixtures {
    const val ORIGIN = "https://tether.test"
    const val OTHER_ORIGIN = "https://other.test"

    /** Planted in every `plan.raw`: nothing drawn, said, logged, printed or stored may hold it. */
    const val RAW_SENTINEL = "RAW-SENTINEL-team_tier_1-default_raven-7f3c"

    val LIST_JSON = """
        {"accounts":[
          {"id":"claude-default","label":"Claude Code (default)","enabled":true,"configDir":"/srv/tether/.claude","hasConfigDir":true,"managed":false,"imported":true,"syncEligible":true,
           "plan":{"label":"Team Premium 5x","organizationName":"Brainrocket","source":"profile",
                   "raw":{"seatTier":"team_tier_1","rateLimitTier":"default_claude_max_5x","organizationType":"claude_team","probe":"$RAW_SENTINEL","$RAW_SENTINEL":true}}},
          {"id":"claude-work","label":"Claude Code (work)","enabled":true,"configDir":"/srv/tether/state/claude-accounts/claude-work","hasConfigDir":true,"managed":true,"imported":false,"syncEligible":true,
           "plan":{"label":"Team Standard","source":"profile","raw":{"subscriptionType":"team","rateLimitTier":"default_raven","probe":"$RAW_SENTINEL"}}},
          {"id":"claude-fresh","label":"Claude Code (fresh)","enabled":true,"configDir":null,"hasConfigDir":false,"managed":true,"imported":false,"syncEligible":true,"plan":null}
        ]}
    """.trimIndent()

    const val SYNC_JSON = """
        {"config":{"mode":"selected","categories":{"plugins":true,"skills":true,"hooks":false,"mcp":true},"primaryAccountId":"claude-default"},
         "lastResult":{"ranAt":1790000000000,"status":"ok","primaryAccountId":"claude-default","entries":[
           {"accountId":"claude-work","category":"skills","kind":"dir","path":"skills","status":"linked"},
           {"accountId":"claude-work","category":"plugins","kind":"dir","path":"plugins","status":"up-to-date"},
           {"accountId":"claude-work","category":"mcp","kind":"field","path":".claude.json","status":"merged"}]}}
    """

    fun decode(json: String): List<ClaudeAccount> = ClaudeAccountsJson.accounts(ClaudeAccountsJson.parseObject(json)!!)!!
    val LIST: List<ClaudeAccount> get() = decode(LIST_JSON)
    val SYNC: ClaudeAccountsSync get() = ClaudeAccountsJson.sync(ClaudeAccountsJson.parseObject(SYNC_JSON)!!)!!

    val LOGGED_IN = ClaudeAccountStatus(loggedIn = true, authMethod = "claude.ai", email = "work@example.com", error = null)

    /** A fixed clock face for `toLocaleTimeString`, so the goldens never depend on the host's zone. */
    val TIME: (Long) -> String = { "10:13:20" }
}

/**
 * A scripted [ClaudeAccountsSource]: each list() takes the next of [lists] (the last repeats), or
 * waits forever when [hangList]; status() answers from [statuses] (else 404 "No such Claude
 * account."). Every call is recorded.
 */
class FakeAccounts(
    private val origin: String = AccountsFixtures.ORIGIN,
    private val lists: List<ClaudeAccountsResult<List<ClaudeAccount>>> = listOf(ClaudeAccountsResult.Ok(AccountsFixtures.LIST, origin)),
    private val sync: ClaudeAccountsResult<ClaudeAccountsSync> = ClaudeAccountsResult.Ok(AccountsFixtures.SYNC, origin),
    private val statuses: Map<String, ClaudeAccountsResult<ClaudeAccountStatus>> = mapOf("claude-work" to ClaudeAccountsResult.Ok(AccountsFixtures.LOGGED_IN, origin)),
    private val hangList: Boolean = false,
    private val listGate: (suspend () -> Unit)? = null,
    private val statusGate: (suspend () -> Unit)? = null,
) : ClaudeAccountsSource {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var listed = 0

    override suspend fun list(): ClaudeAccountsResult<List<ClaudeAccount>> {
        calls += "list"
        if (hangList) awaitCancellation()
        listGate?.invoke()
        return lists[minOf(listed++, lists.size - 1)]
    }

    override suspend fun sync(): ClaudeAccountsResult<ClaudeAccountsSync> {
        calls += "sync"
        return sync
    }

    override suspend fun status(accountId: String): ClaudeAccountsResult<ClaudeAccountStatus> {
        calls += "status:$accountId"
        statusGate?.invoke()
        return statuses[accountId] ?: ClaudeAccountsResult.Refused(404, ClaudeAccountRefusal.NoSuchAccount, origin)
    }

    fun binding() = ClaudeAccountsBinding(this, origin, AccountsFixtures.TIME)
}
