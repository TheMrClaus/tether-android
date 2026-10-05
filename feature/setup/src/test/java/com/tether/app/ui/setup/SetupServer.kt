package com.tether.app.ui.setup

import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * A fake first-run server shaped from tether 90fbb9f lib/setup-server.mjs (the handlers) and
 * lib/origin-guard.mjs (the write guard): `/healthz` answers `{ok, setupRequired:true, runtime}`; every
 * `/api/setup routes` write must pass `ambientWriteAllowed` (no Origin and no Sec-Fetch-Site, so it passes
 * only as a POST with `Content-Type: application/json`, bodiless ones included; DELETE passes), and a
 * `/api/setup routes` call after completion is the normal server's 401 "Authentication required." (page.tsx
 * :139-145). No real server, nothing started: a MockWebServer dispatcher.
 */
class SetupServer : Dispatcher() {
    class Seen(
        val method: String,
        val target: String,
        val contentType: String?,
        val origin: String?,
        val authorization: String?,
        val cookie: String?,
        val body: String,
    ) {
        val path: String get() = target.substringBefore('?')
    }

    val seen = CopyOnWriteArrayList<Seen>()

    /** After completion the server is the normal one: `/api/setup routes` answers 401. */
    @Volatile var configured = false

    /** `/healthz` keeps saying `setupRequired: true` for this many calls after completion (the restart). */
    @Volatile var healthzBouncing = 0

    @Volatile var state: String = DEFAULT_STATE
    @Volatile var detect: String = DEFAULT_DETECT
    @Volatile var browse: (String?) -> MockResponse = { path -> json(200, listing(path ?: "/home/op")) }
    @Volatile var validate: (String) -> String = { """{"ok":true,"message":"Found here."}""" }
    @Volatile var complete: (String) -> MockResponse = { json(200, """{"ok":true,"runtime":"native","restart":"manual"}""") }

    // ---- GitHub (setup-server.mjs :857-887) ---------------------------------------------------
    @Volatile var githubStatus: String = """{"ghInstalled":true,"ghVersion":"2.60.0","authenticated":false,"account":null,"scopes":[],"managedToken":false}"""
    @Volatile var githubLoginStatus = 200
    /** What `GET /api/setup/github/login/poll` answers next (the last one repeats). */
    val githubPolls = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile var githubTokenReply: (String) -> MockResponse = { json(200, """{"ok":true,"account":"octo","scopes":["repo"]}""") }

    // ---- Claude accounts (setup-server.mjs :908-1020) ------------------------------------------
    /** Once `setupCompletedAt` is persisted these routes answer 403 (setupAccountRoutesClosed) while setup mode still serves. */
    @Volatile var accountRoutesClosed = false
    val claudeAccounts = CopyOnWriteArrayList<Pair<String, String>>()
    val claudeLoggedIn = java.util.concurrent.ConcurrentHashMap<String, String>()
    val claudeLoginsRunning: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val claudePolls = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile var claudeCodeReply: (String) -> MockResponse = { json(200, """{"ok":true}""") }

    fun calls(path: String): List<Seen> = seen.filter { it.path == path }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val target = request.path.orEmpty()
        val path = target.substringBefore('?')
        seen += Seen(
            method = request.method.orEmpty(),
            target = target,
            contentType = request.getHeader("Content-Type"),
            origin = request.getHeader("Origin"),
            authorization = request.getHeader("Authorization"),
            cookie = request.getHeader("Cookie"),
            body = request.body.clone().readUtf8(),
        )
        if (path == "/healthz") {
            val bouncing = configured && healthzBouncing > 0
            if (bouncing) healthzBouncing -= 1
            return if (!configured || bouncing) json(200, """{"ok":true,"setupRequired":true,"runtime":"native"}""")
            else json(200, """{"ok":true,"protocolVersion":143,"nativeProtocolFloor":129,"pairing":true}""")
        }
        if (!path.startsWith("/api/setup/")) return json(503, """{"error":"Setup required.","setupRequired":true}""")
        if (configured) return json(401, """{"error":"Authentication required."}""")
        // setupWriteRefusal: an unsafe method must be provably same-origin, or one a browser cannot send
        // cross-origin without a preflight (no Origin, no Sec-Fetch-Site: a JSON POST, or DELETE).
        val method = request.method.orEmpty()
        if (method != "GET" && method != "HEAD") {
            val mediaType = request.getHeader("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            val allowed = request.getHeader("Origin") == null && request.getHeader("Sec-Fetch-Site") == null &&
                (method == "DELETE" || method == "PUT" || method == "PATCH" || (method == "POST" && mediaType == "application/json"))
            if (!allowed) return json(403, """{"ok":false,"error":"Cross-origin setup writes are refused."}""")
        }
        return when {
            method == "GET" && path == "/api/setup/state" -> json(200, state)
            method == "GET" && path == "/api/setup/detect" -> json(200, detect)
            method == "GET" && path == "/api/setup/browse" -> browse(request.requestUrl?.queryParameter("path"))
            method == "POST" && path == "/api/setup/validate" -> json(200, validate(request.body.clone().readUtf8()))
            method == "POST" && path == "/api/setup/complete" -> complete(request.body.clone().readUtf8()).also {
                if (it.status.contains(" 200 ")) configured = true
            }
            path.startsWith("/api/setup/github/") -> github(method, path, request.body.clone().readUtf8())
            path == "/api/setup/claude-accounts" || path.startsWith("/api/setup/claude-accounts/") ->
                if (accountRoutesClosed) json(403, """{"error":"Setup already completed — restart Tether and use the Settings dialog."}""")
                else claude(method, path, request.body.clone().readUtf8())
            else -> json(404, """{"error":"Unknown setup endpoint."}""")
        }
    }

    private fun github(method: String, path: String, body: String): MockResponse = when {
        method == "GET" && path == "/api/setup/github/status" -> json(200, githubStatus)
        method == "POST" && path == "/api/setup/github/login" -> json(githubLoginStatus, if (githubLoginStatus == 200) """{"ok":true,"status":"pending"}""" else """{"error":"gh is not installed."}""")
        method == "GET" && path == "/api/setup/github/login/poll" ->
            json(200, if (githubPolls.size > 1) githubPolls.poll() else githubPolls.peek() ?: """{"ok":true,"status":"pending","deviceCode":null,"verificationUri":null,"error":null}""")
        method == "POST" && path == "/api/setup/github/login/cancel" -> json(200, """{"ok":true}""")
        method == "POST" && path == "/api/setup/github/token" -> githubTokenReply(body)
        else -> json(404, """{"error":"Unknown setup endpoint."}""")
    }

    private fun claude(method: String, path: String, body: String): MockResponse {
        if (path == "/api/setup/claude-accounts") {
            if (method == "GET") {
                val rows = claudeAccounts.joinToString(",") { (id, label) -> """{"id":"$id","label":"$label","configDir":"/x/$id","hasConfigDir":true,"managed":true}""" }
                return json(200, """{"accounts":[$rows]}""")
            }
            if (method == "POST") {
                val nickname = Regex(""""nickname"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1).orEmpty()
                if (nickname.isBlank()) return json(400, """{"error":"Give the account a nickname."}""")
                val id = "claude-" + nickname.lowercase().replace(Regex("[^a-z0-9]+"), "-")
                claudeAccounts += id to nickname
                return json(201, """{"profile":{"id":"$id","label":"$nickname","configDir":"/x/$id","hasConfigDir":true,"managed":true}}""")
            }
        }
        val rest = path.removePrefix("/api/setup/claude-accounts/")
        val id = java.net.URLDecoder.decode(rest.substringBefore('/'), "UTF-8")
        val action = rest.substringAfter('/', "")
        return when {
            method == "GET" && action == "status" ->
                json(200, claudeLoggedIn[id]?.let { """{"loggedIn":true,"authMethod":"claude.ai","email":"$it"}""" } ?: """{"loggedIn":false}""")
            method == "POST" && action == "login" ->
                if (!claudeLoginsRunning.add(id)) json(409, """{"ok":false,"error":"already-in-progress"}""")
                else json(200, """{"ok":true,"status":"pending-url"}""")
            method == "POST" && action == "login/code" -> claudeCodeReply(body)
            method == "GET" && action == "login/poll" ->
                json(200, if (claudePolls.size > 1) claudePolls.poll() else claudePolls.peek() ?: """{"ok":true,"status":"pending-url"}""")
            method == "DELETE" && action == "login" -> {
                claudeLoginsRunning.remove(id)
                json(200, """{"ok":true}""")
            }
            else -> json(404, """{"error":"Unknown Claude account endpoint."}""")
        }
    }

    companion object {
        fun json(code: Int, body: String): MockResponse =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

        fun listing(current: String): String {
            val parent = if (current == "/") "null" else "\"${current.substringBeforeLast('/').ifEmpty { "/" }}\""
            return """{"ok":true,"listing":{"current":"$current","parent":$parent,"entries":[
                {"name":"projects","path":"${current.trimEnd('/')}/projects"},
                {"name":"work","path":"${current.trimEnd('/')}/work"}]}}"""
        }

        const val DEFAULT_STATE = """{"ok":true,"setupRequired":true,"runtime":"native","dev":false,
            "supportedModes":["fake","claude","codex","opencode","reasonix","pi","acp","dsh"],"problems":[],
            "envForced":{"password":false,"headlessModes":false,"workspaceRoot":false},"settings":{},
            "hasPassword":false,"defaultFolder":"/home/op","workspaceCandidates":[],"stateDirectory":"/home/op/.tether"}"""

        const val DEFAULT_DETECT = """{"ok":true,"detected":{
            "claude":{"engine":"claude","found":true,"binPath":"/home/op/.local/bin/claude","version":"2.1.0","source":"host install","configDir":"/home/op/.claude","configPresent":true},
            "codex":{"engine":"codex","found":true,"binPath":"/usr/bin/codex","version":"0.40.0","source":"user PATH","configDir":null,"configPresent":false},
            "opencode":{"engine":"opencode","found":false,"binPath":null,"version":null,"source":"not found","configDir":null,"configPresent":false}}}"""
    }
}
