package com.tether.app.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.client.ClaudeAccountProfile
import com.tether.app.client.SetupClaudeStatus
import com.tether.app.client.SetupGitHubPoll
import com.tether.app.client.GitHubStatus
import com.tether.app.client.SetupApi
import com.tether.app.client.SetupCall
import com.tether.app.client.SetupCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * T10.6 (ta-pqui): the GitHub and Claude accounts stations' state, ported from tether 90fbb9f
 * app/setup/page.tsx StepGitHub (:757-970) and StepClaudeAccounts (:971-1219). Pure of Compose UI, so every
 * rule is tested without a screen.
 *
 * Secrets: a pasted GitHub token and a Claude authorization code live here only until the server has them
 * (cleared on success exactly as the page clears them), travel only in the request body of the typed
 * server's own setup routes, and are never logged, stored or put in an error message.
 */

/** page.tsx's poll cadences: a first look after [initialMs], then every [pollMs]; a poll that did not answer retries after [retryMs]. */
class SetupAccountTiming(val initialMs: Long = 500, val pollMs: Long = 1_500, val retryMs: Long = 2_000)

enum class GitHubMethod { Device, Token }

class SetupGitHubModel(
    private val api: SetupApi,
    private val scope: CoroutineScope,
    private val timing: SetupAccountTiming = SetupAccountTiming(),
) {
    var status by mutableStateOf<GitHubStatus?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var poll by mutableStateOf<SetupGitHubPoll?>(null)
        private set
    var method by mutableStateOf<GitHubMethod?>(null)
        private set
    var tokenInput by mutableStateOf("")
        private set
    var tokenError by mutableStateOf("")
        private set
    var tokenSaved by mutableStateOf(false)
        private set

    private var entered = false
    private var pollJob: Job? = null

    val connected: Boolean get() = status?.authenticated == true

    /** The station came on screen (a rotation re-enters without starting over). */
    fun enter() {
        if (entered) return
        entered = true
        loadStatus()
    }

    /** The station left the screen: page.tsx unmounts StepGitHub, so everything on it is forgotten (the server's flow runs on). */
    fun leave() {
        entered = false
        pollJob?.cancel()
        pollJob = null
        status = null
        loading = true
        error = ""
        busy = false
        poll = null
        method = null
        tokenInput = ""
        tokenError = ""
        tokenSaved = false
    }

    fun loadStatus() {
        scope.launch { loadStatusNow() }
    }

    internal suspend fun loadStatusNow() {
        loading = true
        error = ""
        try {
            when (val call = api.githubStatus()) {
                is SetupCall.Ok -> status = call.value
                is SetupCall.Failed -> error = call.message.ifEmpty { SetupCopy.GITHUB_STATUS_FAILED }
            }
        } finally {
            loading = false
        }
    }

    /** "Connect in the app": start the device flow, then poll it (its first poll captures the one-time code). */
    fun startDevice() {
        if (busy || method == GitHubMethod.Device) return
        scope.launch { startDeviceNow() }
    }

    internal suspend fun startDeviceNow() {
        busy = true
        error = ""
        try {
            when (val call = api.githubLoginStart()) {
                is SetupCall.Ok -> {
                    method = GitHubMethod.Device
                    poll = SetupGitHubPoll(ok = true, status = "pending", deviceCode = null, verificationUri = null, error = null)
                    pollJob?.cancel()
                    pollJob = scope.launch { pollLoop() }
                }
                is SetupCall.Failed -> error = call.message.ifEmpty { SetupCopy.GITHUB_LOGIN_FAILED }
            }
        } finally {
            busy = false
        }
    }

    private suspend fun pollLoop() {
        delay(timing.initialMs)
        while (true) {
            when (val call = api.githubLoginPoll()) {
                is SetupCall.Failed -> delay(timing.retryMs)
                is SetupCall.Ok -> {
                    poll = call.value
                    if (call.value.status == "pending") {
                        delay(timing.pollMs)
                    } else {
                        // Settled either way (page.tsx :803-806): the flow is over, and the status is read again so
                        // the check reflects a new login.
                        method = null
                        loadStatusNow()
                        return
                    }
                }
            }
        }
    }

    fun cancelDevice() {
        pollJob?.cancel()
        pollJob = null
        scope.launch { api.githubLoginCancel() }
        method = null
        poll = null
    }

    /** The token radio: stop watching the device flow (page.tsx's poll effect ends with the method). */
    fun chooseToken() {
        if (busy) return
        pollJob?.cancel()
        pollJob = null
        method = GitHubMethod.Token
    }

    fun typeToken(value: String) {
        tokenInput = value
        tokenError = ""
        tokenSaved = false
    }

    fun saveToken() {
        if (busy || tokenInput.trim().isEmpty()) return
        scope.launch { saveTokenNow() }
    }

    internal suspend fun saveTokenNow() {
        busy = true
        tokenError = ""
        tokenSaved = false
        try {
            when (val call = api.githubSaveToken(tokenInput.trim())) {
                is SetupCall.Ok -> {
                    tokenSaved = true
                    tokenInput = ""
                    loadStatusNow()
                }
                is SetupCall.Failed -> tokenError = call.message.ifEmpty { SetupCopy.GITHUB_TOKEN_FAILED }
            }
        } finally {
            busy = false
        }
    }
}

/** page.tsx ClaudeLoginStatus. */
enum class ClaudeLoginStage(val wire: String) {
    Idle("idle"),
    PendingUrl("pending-url"),
    AwaitingCode("awaiting-code"),
    Success("success"),
    Error("error"),
    ;

    val waiting: Boolean get() = this == PendingUrl || this == AwaitingCode

    companion object {
        fun of(wire: String?): ClaudeLoginStage? = entries.firstOrNull { it.wire == wire }
    }
}

class SetupClaudeModel(
    private val api: SetupApi,
    private val scope: CoroutineScope,
    /** page.tsx :388 `engines.includes("claude")`: with no Claude harness the step makes no request at all. */
    private val claudeAvailable: () -> Boolean,
    private val timing: SetupAccountTiming = SetupAccountTiming(),
) {
    var accounts by mutableStateOf<List<ClaudeAccountProfile>>(emptyList())
        private set
    var statuses by mutableStateOf<Map<String, SetupClaudeStatus>>(emptyMap())
        private set
    var loading by mutableStateOf(true)
        private set
    var listError by mutableStateOf("")
        private set

    var nickname by mutableStateOf("")
        private set
    var addBusy by mutableStateOf(false)
        private set
    var addError by mutableStateOf("")
        private set

    var activeLoginId by mutableStateOf<String?>(null)
        private set
    var loginStage by mutableStateOf(ClaudeLoginStage.Idle)
        private set
    var loginUrl by mutableStateOf<String?>(null)
        private set
    var loginError by mutableStateOf("")
        private set
    var loginBusy by mutableStateOf(false)
        private set
    var codeInput by mutableStateOf("")
        private set
    var codeBusy by mutableStateOf(false)
        private set

    private var entered = false
    private var pollJob: Job? = null

    val available: Boolean get() = claudeAvailable()

    fun enter() {
        if (entered) return
        entered = true
        if (available) loadAccounts()
    }

    /** The station left the screen: page.tsx unmounts it, so everything on it is forgotten (the login on the server runs on). */
    fun leave() {
        entered = false
        pollJob?.cancel()
        pollJob = null
        accounts = emptyList()
        statuses = emptyMap()
        loading = true
        listError = ""
        nickname = ""
        addBusy = false
        addError = ""
        activeLoginId = null
        loginStage = ClaudeLoginStage.Idle
        loginUrl = null
        loginError = ""
        loginBusy = false
        codeInput = ""
        codeBusy = false
    }

    fun loadAccounts() {
        scope.launch { loadAccountsNow() }
    }

    internal suspend fun loadAccountsNow() {
        loading = true
        listError = ""
        try {
            when (val call = api.claudeAccounts()) {
                is SetupCall.Ok -> {
                    val list = call.value
                    accounts = list
                    statuses = coroutineScope { list.map { account -> async { account.id to api.claudeAccountStatus(account.id) } }.awaitAll() }.toMap()
                }
                is SetupCall.Failed -> listError = call.message.ifEmpty { SetupCopy.CLAUDE_LIST_FAILED }
            }
        } finally {
            loading = false
        }
    }

    fun typeNickname(value: String) {
        nickname = value
        addError = ""
    }

    fun typeCode(value: String) {
        codeInput = value
    }

    fun addAccount() {
        if (addBusy || nickname.trim().isEmpty()) return
        scope.launch { addAccountNow() }
    }

    internal suspend fun addAccountNow() {
        addBusy = true
        addError = ""
        try {
            when (val call = api.claudeAccountAdd(nickname.trim())) {
                is SetupCall.Ok -> {
                    nickname = ""
                    loadAccountsNow()
                    // A fresh account goes straight to its login (page.tsx :1066, `void startLogin`).
                    call.value?.let { id -> scope.launch { startLoginNow(id) } }
                }
                is SetupCall.Failed -> addError = call.message.ifEmpty { SetupCopy.CLAUDE_ADD_FAILED }
            }
        } finally {
            addBusy = false
        }
    }

    fun startLogin(id: String) {
        if (loginBusy || activeLoginId == id) return
        scope.launch { startLoginNow(id) }
    }

    internal suspend fun startLoginNow(id: String) {
        loginBusy = true
        loginError = ""
        codeInput = ""
        activeLoginId = id
        loginStage = ClaudeLoginStage.PendingUrl
        loginUrl = null
        pollJob?.cancel()
        pollJob = null
        try {
            val call = api.claudeLoginStart(id)
            // Cancelled while the server was starting it: the panel is gone (page.tsx guards it on activeLoginId), so
            // nothing of this answer is shown and nothing watches it.
            if (activeLoginId != id) return
            when (call) {
                is SetupCall.Ok -> {
                    loginStage = ClaudeLoginStage.of(call.value.status) ?: ClaudeLoginStage.PendingUrl
                    call.value.url?.let { loginUrl = it }
                    // Watched from here on, once the server has the login (page.tsx's poll effect starts 500ms in).
                    if (loginStage.waiting) pollJob = scope.launch { pollLoop(id) }
                }
                is SetupCall.Failed -> {
                    loginStage = ClaudeLoginStage.Error
                    loginError = if (call.status == 409) SetupCopy.CLAUDE_LOGIN_BUSY else call.message.ifEmpty { SetupCopy.CLAUDE_LOGIN_FAILED }
                }
            }
        } finally {
            loginBusy = false
        }
    }

    private suspend fun pollLoop(id: String) {
        delay(timing.initialMs)
        while (true) {
            when (val call = api.claudeLoginPoll(id)) {
                is SetupCall.Failed -> delay(timing.retryMs)
                is SetupCall.Ok -> {
                    val data = call.value
                    if (!data.ok) {
                        loginStage = ClaudeLoginStage.Error
                        loginError = data.error?.takeIf { it.isNotEmpty() } ?: SetupCopy.GITHUB_LOGIN_ERROR
                        return
                    }
                    loginStage = ClaudeLoginStage.of(data.status) ?: ClaudeLoginStage.Error
                    data.url?.let { loginUrl = it }
                    when {
                        loginStage == ClaudeLoginStage.Success -> {
                            loadAccountsNow()
                            return
                        }
                        loginStage.waiting -> delay(timing.pollMs)
                        else -> return
                    }
                }
            }
        }
    }

    fun submitCode() {
        if (codeBusy || activeLoginId == null || codeInput.trim().isEmpty()) return
        scope.launch { submitCodeNow() }
    }

    internal suspend fun submitCodeNow() {
        val id = activeLoginId ?: return
        codeBusy = true
        loginError = ""
        try {
            when (val call = api.claudeLoginCode(id, codeInput.trim())) {
                // Handed to the server: it must not linger here any longer. The poll loop sees the change.
                is SetupCall.Ok -> codeInput = ""
                is SetupCall.Failed -> loginError = call.message.ifEmpty { SetupCopy.CLAUDE_CODE_FAILED }
            }
        } finally {
            codeBusy = false
        }
    }

    fun cancelLogin() {
        val id = activeLoginId
        pollJob?.cancel()
        pollJob = null
        if (id != null) scope.launch { api.claudeLoginCancel(id) }
        activeLoginId = null
        loginStage = ClaudeLoginStage.Idle
        loginUrl = null
        loginError = ""
        codeInput = ""
    }
}
