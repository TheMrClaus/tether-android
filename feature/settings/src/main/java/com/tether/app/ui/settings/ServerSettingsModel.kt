package com.tether.app.ui.settings

import com.tether.app.client.LabelText
import com.tether.app.client.ServerSetting
import com.tether.app.client.ServerSettingsView
import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where the Advanced and Metadata tabs send their writes. The app's is the client
 * ([com.tether.app.client.TetherClient.setServerSettings] / `setAdvancedSettings`), which delivers
 * a write only on a socket opened for the [origin] it names.
 */
interface ServerSettingsWriter {
    fun patch(patch: JsonObject, origin: String): Boolean
    fun cliVersion(message: ClientMessage.SetAdvancedSettings, origin: String): Boolean

    /** No client (previews, a signed-out frame): nothing is ever sent. */
    object None : ServerSettingsWriter {
        override fun patch(patch: JsonObject, origin: String) = false
        override fun cliVersion(message: ClientMessage.SetAdvancedSettings, origin: String) = false
    }
}

/**
 * ta-t7l: what the Advanced and Metadata tabs draw and where their writes go, for ONE server
 * ([origin]). [settings] is the last `server-settings` frame (null until the server replies, the
 * web's `serverSettings`), [advanced] the last `advanced-settings` frame (the Claude CLI picker).
 * Both arrive from the client's flows; a screenshot builds them by hand, so nothing is fetched in a
 * shot. Nothing here is persisted: a secret it holds lives only while the dialog is open.
 */
data class ServerSettingsBinding(
    val settings: ServerSettingsView?,
    val advanced: ServerMessage.AdvancedSettings?,
    val origin: String?,
    val writer: ServerSettingsWriter = ServerSettingsWriter.None,
) {
    /** A write to the server this binding was drawn from; false (nothing sent) without one. */
    fun send(patch: JsonObject?): Boolean = patch != null && origin != null && writer.patch(patch, origin)

    fun sendCli(message: ClientMessage.SetAdvancedSettings?): Boolean = message != null && origin != null && writer.cliVersion(message, origin)

    companion object {
        val None = ServerSettingsBinding(null, null, null)
    }
}

/** One server-settings row's words (settings-dialog.tsx 887c222 :2257-2449), the web's verbatim. */
data class ServerRow(
    val setting: ServerSetting,
    val label: String,
    val description: String,
    val tip: String,
    val placeholder: String = "",
)

/** One option of a server select row. */
data class ServerChoice(val value: String, val label: String, val danger: Boolean = false)

/** The Advanced tab (settings-dialog.tsx:2321-2449), section by section, in the web's order. */
object AdvancedRows {
    const val NETWORK = "Network"
    const val NETWORK_CAPTION = "Host and port the server listens on."
    val host = ServerRow(ServerSetting.Host, "Host", "Bind address", "The network address the server binds to. 0.0.0.0 listens on all interfaces; 127.0.0.1 restricts to localhost only. Requires restart.", "0.0.0.0")
    val port = ServerRow(ServerSetting.Port, "Port", "Listen port (1–65535)", "TCP port for HTTP and WebSocket connections. Requires restart. Default: 4173.", "4173")

    const val AUTH = "Authentication"
    const val AUTH_CAPTION = "Access credentials for the web console."
    val password = ServerRow(ServerSetting.Password, "Password", "Login password", "Login password for the Tether web console. Requires a server restart to take effect.", "tether")
    val proxyToken = ServerRow(ServerSetting.ProxyToken, "Proxy token", "Reverse-proxy pre-auth token (e.g. Authelia)", "A pre-shared token from a reverse proxy such as Authelia. When the proxy sends this token in its auth header, the password login page is bypassed. Requires restart.")

    const val STORAGE = "Storage"
    const val STORAGE_CAPTION = "Directories for state files and workspaces."
    val stateDir = ServerRow(ServerSetting.StateDir, "State directory", "Journals, manifests, and settings (set via TETHER_STATE_DIR)", "Root directory for all Tether state: session journals, manifests, and server-settings.json. Always set via the TETHER_STATE_DIR environment variable — cannot be changed from the UI.", "~/.local/state/tether")
    val workspaceRoot = ServerRow(ServerSetting.WorkspaceRoot, "Workspace root", "Default folder for new sessions", "The default starting folder shown in the session picker. Not a security boundary — any directory the process can read may host a session. Requires restart.", "~")

    /** settings-dialog.tsx:968 `GitHubConnectionSection`, drawn between Storage and Session lifecycle. */
    const val GITHUB = "GitHub connection"
    const val GITHUB_CAPTION = "Drives the issues and pull-requests features. Reuses a host gh login when present, or stores a personal access token (masked after entry)."
    const val GITHUB_LATER = "Connecting GitHub from the app arrives with the issues and pull-requests features. Connect it from the web console for now."

    const val LIFECYCLE = "Session lifecycle"
    const val LIFECYCLE_CAPTION = "Warm-session persistence, eviction, and background-task handling."
    val claudePersistent = ServerRow(ServerSetting.ClaudePersistent, "Persistent mode", "Keep Claude sessions warm between turns (enables background tasks)", "Keeps Claude SDK processes alive between turns so background tasks can continue running. When off, each turn cold-starts a new process. Requires restart.")
    val claudeTaskTelemetry = ServerRow(ServerSetting.ClaudeTaskTelemetry, "Task telemetry", "Wait for background tasks before evicting idle sessions", "Uses the SDK's background_tasks_changed signal to detect pending background work before evicting a session. Prevents premature eviction when tasks are still running. Requires restart.")
    val warmMaxSessions = ServerRow(ServerSetting.WarmMaxSessions, "Max warm sessions", "Cap on concurrent warm sessions", "How many Claude sessions can stay warm simultaneously. When the cap is reached, the oldest idle session is evicted. Applies immediately. Default: 8.", "8")
    val maxConcurrentTurns = ServerRow(ServerSetting.MaxConcurrentTurns, "Max concurrent turns", "Cap on simultaneous turns across all sessions (0 = unlimited)", "The maximum number of turns that can run at once across ALL sessions. 0 (the default) = unlimited — Tether never refuses or delays a turn because other sessions are mid-flight. Set a positive number to cap simultaneous turns; when the cap is reached new sends are rejected and queued continuations wait for a slot. Applies immediately. Default: 0 (unlimited).", "0")
    val warmIdleEvictionMs = ServerRow(ServerSetting.WarmIdleEvictionMs, "Idle eviction (ms)", "Evict idle sessions after this duration", "How long a warm session can sit idle before being evicted. In milliseconds — 900000 = 15 minutes. Applies immediately.", "900000")
    val warmBgHardCapMs = ServerRow(ServerSetting.WarmBgHardCapMs, "Background hard cap (ms)", "Force-evict regardless of background state", "Force-evict a warm session after this duration even if background tasks are still running. Safety net for runaway processes. Applies immediately. Default: 1800000 (30 min).", "1800000")
    val warmSweepMs = ServerRow(ServerSetting.WarmSweepMs, "Sweep interval (ms)", "How often the eviction sweeper runs", "How frequently Tether checks for idle or expired warm sessions. Lower values detect idle sessions faster but use more CPU. Requires restart. Default: 60000 (1 min).", "60000")
    val shutdownDrainMs = ServerRow(ServerSetting.ShutdownDrainMs, "Shutdown drain (ms)", "Grace period for background work on shutdown", "How long to wait for background tasks to finish during a graceful server shutdown. Set to 0 to shut down immediately. Requires restart. Default: 0.", "0")
    val messageInterruptMode = ServerRow(ServerSetting.MessageInterruptMode, "Message while busy", "What happens when you send while the agent is working", "What happens when you send a message while the agent is still working. Interrupt stops the current turn and sends your message right away (the default). Next call queues it and delivers at the agent's next tool call. End queues it and delivers after the turn finishes. Applies immediately.")
    val claudeModelFallback = ServerRow(ServerSetting.ClaudeModelFallback, "Claude model fallback", "Whether the Claude CLI may switch a turn to a fallback model", "When the model you picked fails with a capacity or availability error, the Claude CLI can re-run the rest of that turn on the fallback model configured in your Claude settings (fallbackModel). 'Let the CLI decide' keeps that behaviour (the default); Tether shows a notice whenever it happens. 'Never — fail the turn' sets CLAUDE_CODE_NO_MODEL_FALLBACK so the turn fails visibly instead of being downgraded. Applies when a Claude engine next starts: a warm session keeps its current CLI until it is evicted or restarted. Issue #179.")
    val archiveOnMerge = ServerRow(ServerSetting.ArchiveOnMerge, "Archive on merge", "Archive a session when its linked pull request is observed merged", "When on, a session whose linked pull request is observed MERGED (on open or an explicit refresh in the inspector) is archived automatically, and its worktree is removed if it is clean and merged — the same rule a manual archive already applies. Off by default. Never fires on an ambiguous read: a failed PR lookup stays unknown, never merged. Applies immediately. Issue #159.")

    const val DEFAULTS = "Session defaults"
    const val DEFAULTS_CAPTION = "What a new session starts with when the create dialog omits a choice. A client-sent value always wins. Only the spawn settings apply immediately; the rest require a restart."
    val defaultPermissionMode = ServerRow(ServerSetting.DefaultPermissionMode, "Default permission mode", "Preselected approval posture for new sessions", "The Claude permission mode a new session starts in when the create dialog does not specify one. Claude-shaped; Codex ignores it. Requires restart.")
    val defaultSandboxPolicy = ServerRow(ServerSetting.DefaultSandboxPolicy, "Default sandbox tier", "Preselected sandbox for new sessions", "The sandbox tier a fresh session starts under when the create dialog omits it. 'Provider default' keeps each engine's own default. In containers, 'Full access' is recommended — nested sandboxing fail-closes. Requires restart.")
    val defaultUseWorktree = ServerRow(ServerSetting.DefaultUseWorktree, "Default to isolated worktree", "New sessions default to a separate Git worktree", "Whether new sessions default to running in an isolated Git worktree instead of the selected folder directly. The create dialog can still override per session. Requires restart.")
    val preferSpawnAgent = ServerRow(ServerSetting.PreferSpawnAgent, "Detached agent launches", "What a Claude session does with a hand-rolled background codex exec / claude -p", "When a Claude session's Bash call starts an agent CLI in the background (nohup, setsid, disown, a trailing &, or run_in_background with codex exec / claude -p), Tether can refuse it and point the agent at the spawn_agent tool instead — a run linked to the session, with tracked status and a notice when it ends. Require spawn_agent refuses every such launch (the default). Steer once refuses only the first per session, for repos whose own recipe insists on the hand-rolled form. Off never refuses. Foreground runs are always allowed, and sessions without spawn_agent (delegate children, read-only sessions) are never refused. This is steering, not a sandbox. Applies immediately.")

    /** ServerRootsRow's two uses (settings-dialog.tsx:2358-2375). */
    data class RootsRow(val setting: ServerSetting, val title: String, val tip: String, val addLabel: String, val description: (Int) -> String)

    val allowedRoots = RootsRow(
        ServerSetting.AllowedRoots,
        "Allowed folders",
        "Opt-in folder containment for the session picker and create path. When empty, sessions can open any folder the server process can read (the default). When set, sessions may only be created inside these folders. NOT a security sandbox — authentication is the boundary. Requires restart.",
        "Add allowed folder",
    ) { count -> if (count == 0) "Unrestricted — the picker can open any readable folder" else "$count allowed folder${if (count == 1) "" else "s"}" }
    val spawnExtraWritableRoots = RootsRow(
        ServerSetting.SpawnExtraWritableRoots,
        "Spawn extra writable roots",
        "Extra directories an agent may name in a spawned CLI child's writableRoots (spawn_agent) — beyond the child's own session folder, the repo's other worktrees, a root the repo declares in its committed tether.json (spawn.writableRoots), and codex's generated_images cache. Use it for a project's out-of-repo deliverable directory (e.g. a game's art/output folder). Absolute paths only. This widens what YOU allow; the child still has to name the path explicitly, and a path outside every declared root is refused. Applies immediately — no restart.",
        "Add spawn writable root",
    ) { count -> if (count == 0) "None — spawned children stay inside their session folder (and codex's image cache)" else "$count extra root${if (count == 1) "" else "s"} a spawned child may name" }
    const val ROOT_PLACEHOLDER = "/absolute/path"

    // lib/protocol.ts 887c222 option lists (the select rows' options, the web's labels).
    val messageInterruptModes = listOf(
        ServerChoice("interrupt", "Interrupt"),
        ServerChoice("next-call", "Next call"),
        ServerChoice("end", "End of turn"),
    )
    val claudeModelFallbacks = listOf(
        ServerChoice("cli", "Let the CLI decide"),
        ServerChoice("never", "Never — fail the turn"),
    )
    val permissionModes = listOf(
        ServerChoice("default", "Manual"),
        ServerChoice("acceptEdits", "Accept Edits"),
        ServerChoice("plan", "Plan"),
        ServerChoice("bypassPermissions", "Auto", danger = true),
    )
    val sandboxPolicies = listOf(
        ServerChoice("", "Provider default"),
        ServerChoice("read-only", "Bash: workspace read-only"),
        ServerChoice("workspace-write", "Bash: workspace write", danger = true),
    )
    val preferSpawnAgents = listOf(
        ServerChoice("deny", "Require spawn_agent"),
        ServerChoice("steer", "Steer once"),
        ServerChoice("off", "Off"),
    )

    const val LOADING = "Loading the server's settings…"
}

/**
 * The Claude CLI section (settings-dialog.tsx:2426-2449): the warning, then the env-forced row or
 * the version picker.
 */
object ClaudeCliCopy {
    const val TITLE = "Claude CLI"
    const val FORCED_TITLE = "Claude CLI"
    const val FORCED_TIP = "The Claude CLI path is overridden by an environment variable (TETHER_CLAUDE_CLI_PATH or an absolute TETHER_CLAUDE_COMMAND). The in-app picker is disabled when an env override is set."
    const val FORCED_LEAD = "Forced by env — "
    const val PICKER_TITLE = "Claude CLI version"
    const val PICKER_TIP = "Auto = the newest installed host version from ~/.local/share/claude/versions/ (Tether behaves like an extension of your own CLI); Bundled = the SDK-shipped CLI. A mismatched CLI can break turns or silently disable approval prompts."
    const val AUTO_SOURCE = "host install (auto)"

    /** The picker row's caption (:2431). */
    fun caption(advanced: ServerMessage.AdvancedSettings?): String = when {
        advanced?.effectiveSource == AUTO_SOURCE && !advanced.effectiveVersion.isNullOrEmpty() ->
            "Auto resolves to ${LabelText.visibleValue(advanced.effectiveVersion)} (newest installed)"
        advanced?.effectiveSource == "bundled" -> "No host versions installed — using the bundled CLI"
        else -> "Applies to sessions started after the change"
    }

    /** The picker's options (:2439-2443): Auto, Bundled, then each discovered version (its own string, shown by the value rule). */
    fun options(advanced: ServerMessage.AdvancedSettings?): List<ServerChoice> = buildList {
        val auto = if (advanced?.effectiveSource == AUTO_SOURCE && !advanced.effectiveVersion.isNullOrEmpty()) {
            "Auto — newest installed (${LabelText.visibleValue(advanced.effectiveVersion)})"
        } else {
            "Auto — newest installed"
        }
        add(ServerChoice("", auto))
        add(ServerChoice("bundled", "Bundled (SDK)"))
        advanced?.discovered?.forEach { add(ServerChoice(it.version, LabelText.visibleValue(it.version))) }
    }.distinctBy { it.value }
}

/** The Metadata tab (settings-dialog.tsx:2257-2320). */
object MetadataRows {
    const val GENERATION = "Metadata generation"
    const val GENERATION_CAPTION = "Atomic one-shot LLM calls that draft session titles, branch names, commit messages, and PR title+body. Runs alongside the agent without interrupting the turn."
    val enabled = ServerRow(
        ServerSetting.MetadataGenerationEnabled,
        "Enable metadata generation",
        "When on, session titles, branches, commits, and PR drafts are produced automatically (or with your chosen provider in manual mode). When off, no LLM metadata runs.",
        "Master switch for v84 metadata generation. When off, no metadata LLM calls are made — the agent's own artifacts stand on their own. Default: on. Applies immediately.",
    )

    const val SELECTION = "Provider selection"
    const val SELECTION_CAPTION = "Automatic uses the built-in default candidate order. Manual lets you pick a specific provider/model to try first; if it is unavailable, the fallback list still applies."
    val mode = ServerRow(
        ServerSetting.MetadataGenerationMode,
        "Mode",
        "Automatic (default candidate order) or manual (your provider first)",
        "When automatic (the default), candidates are tried in the built-in order. When manual, the provider/model below is tried first; if it fails, the rest of the fallback list still runs.",
    )
    val modes = listOf(ServerChoice("automatic", "Automatic"), ServerChoice("manual", "Manual"))

    const val PROVIDER = "Provider / model"
    const val PROVIDER_SELECT_TIP = "Flat key format is <providerId>:<modelId> (or just <providerId>). The chosen entry replaces only the FIRST entry of metadataGenerationProviders when mode is Manual."
    const val PROVIDER_TEXT_TIP = "Flat key format is <providerId>:<modelId> (or just <providerId>). When mode is Manual, this value is tried first; the rest of the fallback list still runs."
    const val PROVIDER_PLACEHOLDER = "anthropic:claude-3-5-haiku-latest"

    fun providerSelectDescription(manual: Boolean) = if (manual) "Tried first; the fallback list still runs if this one fails" else "Only used when mode is Manual"
    fun providerTextDescription(manual: Boolean) = if (manual) "Flat key, e.g. anthropic:claude-3-5-haiku-latest" else "Only used when mode is Manual"

    /** settings-dialog.tsx:56 `MetadataProviderEntry`. */
    data class ProviderEntry(val provider: String, val model: String)

    /**
     * settings-dialog.tsx:58-76 `parseMetadataProviders`: the JSON-encoded fallback list, or null on
     * any shape error (the tab then offers the free-text row).
     */
    fun parseProviders(raw: String): List<ProviderEntry>? {
        if (raw.isEmpty()) return null
        val parsed = runCatching { TetherJson.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return null
        val out = ArrayList<ProviderEntry>(parsed.size)
        for (item in parsed) {
            val entry = item as? JsonObject ?: return null
            val provider = (entry["provider"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: return null
            val model = (entry["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            out += ProviderEntry(provider, model)
        }
        return out.ifEmpty { null }
    }

    /** settings-dialog.tsx:78 `metadataProviderValue`. */
    fun providerValue(entry: ProviderEntry) = if (entry.model.isNotEmpty()) "${entry.provider}:${entry.model}" else entry.provider

    /**
     * The provider select's options (:2290-2296): Default order, a "Custom: …" row when the current
     * value is not in the list, then each entry. Server text is drawn by the label rule.
     */
    fun providerOptions(view: ServerSettingsView, providers: List<ProviderEntry>): List<ServerChoice> {
        val current = view.text(ServerSetting.MetadataGenerationProvider)
        val matches = current.isEmpty() || providers.any { providerValue(it) == current }
        return buildList {
            add(ServerChoice("", "Default order"))
            if (!matches) add(ServerChoice(current, "Custom: ${LabelText.label(current)}"))
            providers.forEach { add(ServerChoice(providerValue(it), "${LabelText.label(it.provider)} / ${LabelText.label(it.model).ifEmpty { "(default)" }}")) }
        }.distinctBy { it.value }
    }
}

/** Server-settings rows' shared words (settings-dialog.tsx:144-146). */
object ServerRowCopy {
    const val SET_BY_ENV = "Set by environment"
    const val MASK = "••••••••"
    fun reveal(label: String) = "Reveal $label"
    fun hide(label: String) = "Hide $label"
    fun maskedDescription(label: String, empty: Boolean) = if (empty) "$label, not set" else "$label, hidden"
}
