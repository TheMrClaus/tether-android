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

/**
 * ta-7rh: a scripted [com.tether.app.client.ClaudeAccountActions]: each call is recorded with its
 * server and argument and waits for [answer], unless [auto] answers it at once. The pasted code is
 * kept as the client's own [com.tether.app.client.ClaudeLoginCode] (it prints `***`; compared by
 * equality only).
 */
class FakeAccountActions(
    private val auto: ((String) -> com.tether.app.client.SecurityResult<*>?)? = null,
) : com.tether.app.client.ClaudeAccountActions {
    class Call(val name: String, val origin: String, val arg: String?, val code: com.tether.app.client.ClaudeLoginCode? = null) {
        val reply = kotlinx.coroutines.CompletableDeferred<com.tether.app.client.SecurityResult<*>>()
        override fun toString() = "$name($origin, $arg)"
    }

    val calls = java.util.concurrent.CopyOnWriteArrayList<Call>()

    fun names(): List<String> = calls.map { it.name }

    fun pending(name: String): Boolean = calls.any { it.name == name && !it.reply.isCompleted }

    fun answer(name: String, result: com.tether.app.client.SecurityResult<*>) {
        val call = calls.firstOrNull { it.name == name && !it.reply.isCompleted } ?: error("no pending $name in ${names()}")
        call.reply.complete(result)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> record(name: String, origin: String, arg: String? = null, code: com.tether.app.client.ClaudeLoginCode? = null): com.tether.app.client.SecurityResult<T> {
        val call = Call(name, origin, arg, code)
        calls += call
        auto?.invoke(name)?.let { call.reply.complete(it) }
        return call.reply.await() as com.tether.app.client.SecurityResult<T>
    }

    override suspend fun add(origin: String, nickname: String) = record<Unit>("add", origin, nickname)
    override suspend fun rename(origin: String, accountId: String, nickname: String) = record<Unit>("rename", origin, "$accountId=$nickname")
    override suspend fun remove(origin: String, accountId: String, deleteCredentials: Boolean) =
        record<com.tether.app.client.ClaudeAccountRemoved>("remove", origin, "$accountId deleteCredentials=$deleteCredentials")
    override suspend fun logout(origin: String, accountId: String) = record<Unit>("logout", origin, accountId)
    override suspend fun startLogin(origin: String, accountId: String) = record<com.tether.app.client.ClaudeLoginState>("startLogin", origin, accountId)
    override suspend fun pollLogin(origin: String, accountId: String) = record<com.tether.app.client.ClaudeLoginState>("pollLogin", origin, accountId)
    override suspend fun submitCode(origin: String, accountId: String, code: com.tether.app.client.ClaudeLoginCode) = record<Unit>("submitCode", origin, accountId, code)
    override suspend fun cancelLogin(origin: String, accountId: String) = record<Unit>("cancelLogin", origin, accountId)
    override suspend fun saveSync(origin: String, config: com.tether.app.client.ClaudeSyncConfig) = record<com.tether.app.client.ClaudeSyncSaved>("saveSync", origin, config.toString())
    override suspend fun runSync(origin: String) = record<com.tether.app.client.ClaudeSyncSaved>("runSync", origin)
}

/** A [LoginLinkOpener] that records what it was handed and opens nothing. */
class RecordingOpener(private val opens: Boolean = true) : LoginLinkOpener {
    val opened = java.util.concurrent.CopyOnWriteArrayList<com.tether.app.client.ClaudeLoginLink>()
    override fun open(link: com.tether.app.client.ClaudeLoginLink): Boolean {
        opened += link
        return opens
    }
}
